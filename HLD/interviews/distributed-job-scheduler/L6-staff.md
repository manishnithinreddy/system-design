# Distributed Job Scheduler — L6 (Staff) Interview

> **Level expectation:** the [L4](L4-mid.md)/[L5](L5-senior.md) mechanics are assumed. The staff conversation is about the scheduler as a **shared platform**:
> - fairness between tenants;
> - multi-region and what "on time" means during a region failure;
> - observability that tells a team *why* their job didn't run;
> - build vs buy (k8s CronJob, Quartz, Airflow, Temporal, cloud schedulers);
> - migrating hundreds of hand-made crons safely.

> 🆕 Read [00-understand-the-product.md](00-understand-the-product.md), [L4](L4-mid.md) and [L5](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Framing

**🧑‍💼 Interviewer:** Every team here runs its own cron boxes and k8s CronJobs. Leadership wants one scheduling platform. Where do you start?

**🧑‍💻 Candidate:** With an inventory and a classification, because "job" means four different things:

| Kind | Example | What it needs |
|---|---|---|
| **Triggers** | "call /renew at 00:00 IST" | Precise timing, huge counts, idempotency |
| **Batch jobs** | Nightly reindex, 2 hours of compute | Resources (CPU/memory), isolation: really a k8s problem |
| **Workflows** | ETL → report → email | Dependencies, backfills, lineage |
| **Delayed retries** | "retry in 5 minutes" | Massive volume, short horizon |

One platform with **three engines behind one API and one UI** beats one engine forced to do everything. Then: the SLOs (firing lag p99, missed-run rate), the tenancy model, and what we'll buy.

> 📝 **Note:** The staff signal is refusing to treat "scheduler" as one thing, and saying which parts are commodity.

---

## 2. Multi-tenant fairness

**🧑‍💼 Interviewer:** Team A schedules 20M jobs at 00:00. Team B's 50 critical jobs at 00:00 are late.

**🧑‍💻 Candidate:**
- **Per-tenant quotas** on schedules and on firing rate per minute, set at onboarding and visible in the UI.
- **Fair queuing:** the dispatcher pulls from per-tenant queues in round-robin (weighted by tier), so 20M jobs from A can't push B's 50 to the back. It's the same idea as Linux's fair scheduler or a k8s priority class.
- **Priority tiers:** `critical` (billing, security) is isolated in its own shards, workers and queues.
- **Backpressure from targets:** per-target concurrency limits (L5 §3.4). A slow target only delays its own jobs.
- **Chargeback:** runs × duration per team, so "every minute" for a job that needs "every hour" shows up on someone's bill.

---

## 3. Multi-region

**🧑‍💼 Interviewer:** We run in two regions. A region goes down at 23:58.

**🧑‍💻 Candidate:** Design goals: a job fires **once**, and fires **somewhere** if its home region is down.
- Each job has a **home region** (where its target lives). Its shard's leader runs there.
- The schedule store is replicated across regions. For "fire exactly once" across regions, shard ownership must come from a **cross-region consensus** store (etcd/Spanner-style, or a database with a single writer region plus fast failover). Two regions each believing they own shard 17 is the "charged twice" story at region scale.
- **Failover:** shard leases expire (tens of seconds); the other region takes the shards and applies **misfire policies** to whatever came due in between (L5 §3.3). Targets in the dead region fail anyway, unless they're multi-region too.
- **Clocks:** firing compares "now" with `next_run_at`, so a node with a skewed clock fires early or late. Use NTP/chrony with skew monitoring; leaders refuse to run if skew exceeds a threshold. Firing within seconds doesn't need special clocks; sub-second cross-region precision does (and almost nobody needs it).

---

## 4. Build vs buy

| Option | Fits | Watch out for |
|---|---|---|
| **k8s CronJob** | Batch jobs per team; already have k8s | Not designed for millions of schedules; controller does one sweep loop; minute precision |
| **Quartz (clustered, JDBC store)** | A Java service with thousands to ~millions of jobs | DB lock contention at large scale; the app owns the ops |
| **Airflow** | Data workflows (DAGs, backfills, lineage) | Not a high-volume trigger engine; scheduler loop latency |
| **Temporal / Cadence** | Long-running business workflows with retries, timers, human steps | New programming model (deterministic workflow code); cluster to run or buy |
| **Cloud schedulers** (EventBridge Scheduler, Cloud Scheduler) | Triggers at large scale, zero ops | Per-schedule pricing, quotas, vendor lock-in; check limits for your volume |
| **Build** | Very high volume triggers with custom fairness/tenancy | Everything in L4/L5 is now yours to run |

**🧑‍💻 Candidate:** For our mix:
- **Triggers:** build a thin trigger service (L5 design) or a cloud scheduler.
- **Batch:** k8s CronJob/Jobs with platform defaults (`concurrencyPolicy: Forbid`, deadlines, time zones).
- **Workflows:** a workflow engine (Airflow for data, Temporal for business processes).
- **One API, one UI:** a portal that shows all of them.

Product facts and limits change; I'd re-check each vendor's current quotas before deciding.

---

## 5. Observability: "why didn't my job run?"

The most common support ticket. Every run gets a timeline:

```text
job_42 run 1093   scheduled_for 00:00:00 IST
  00:00:00.120  claimed by shard 17 leader (token 8)
  00:00:00.180  enqueued (queue: billing-critical)
  00:00:02.900  picked by worker w-31 (queue wait 2.7 s)
  00:00:03.400  target returned 503 → retry 1 in 30 s
  00:00:33.600  target returned 200 → SUCCEEDED
```

Plus:
- **Missed-run detection:** independently from the scheduler, a checker compares "expected runs" (from schedules) with "actual runs" and alerts on gaps. Never trust the system to report its own silence.
- **SLIs:** firing lag p50/p99 per tier, missed-run rate, dead runs per team, queue wait ([observability](../../concepts/observability.md), [alerting & SLOs](../../concepts/alerting-and-slos.md)).
- **A dead-man's switch for critical jobs:** the job pings a monitor on success; no ping by 00:30 → page the owning team.

---

## 6. Migration: hundreds of hand-made crons

1. **Inventory** every crontab, CronJob and in-app timer (grep, cluster API, interviews).
2. **Shadow mode:** register each job on the platform with target `noop`, and compare its firing times with the old cron for two weeks (catches time-zone and DST mismatches).
3. **Cut over per job** with a kill switch: disable the old cron in the same change that enables the new target. Never run both "just for safety": that's the double-charge story again.
4. **Make targets idempotent first** (run ID header). It's the precondition for safe retries and for the cutover itself.

---

## 7. Curveballs

**🧑‍💼 Interviewer:** A bad deploy made 2M jobs fire every minute instead of every day.

**🧑‍💻 Candidate:** Global and per-tenant **kill switches** (pause a tenant, pause a target). Firing-rate anomaly alerts per tenant ("10× yesterday"). And schedule changes go through validation: preview the next 5 run times in the API response and UI, and require confirmation for schedules faster than every 5 minutes.

**🧑‍💼 Interviewer:** Legal needs proof that a regulatory job ran every day last year.

**🧑‍💻 Candidate:** Run history is an append-only audit log, retained per policy and exported to cold storage ([object storage](../../technologies/object-storage.md)), with the missed-run checker's results alongside it.

---

## 8. What the interviewer was evaluating (L6)

- [ ] Split "jobs" into triggers / batch / workflows / delayed retries, with different engines
- [ ] Fairness: quotas, fair queuing, priority isolation, chargeback
- [ ] Multi-region: home region, cross-region ownership, failover + misfires, clock skew
- [ ] Reasoned build vs buy with each option's limits
- [ ] Run timelines, independent missed-run detection, dead-man's switches
- [ ] Safe migration: shadow mode, single cutover, idempotency first

## 9. Common mistakes at this level

| Mistake | Why it hurts |
|---|---|
| One engine for triggers, batch and workflows | Each workload is served badly |
| Active-active scheduling across regions without single ownership | Double firing during partitions |
| Trusting the scheduler to report missed runs | Silence looks like success |
| Running old and new crons in parallel during migration | Duplicate side effects |
| No per-tenant limits | One team's midnight job delays everyone |

⬅️ Back to [README.md](README.md)
