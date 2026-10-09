# HLD Interview: Design a Distributed Job Scheduler

> "Design a system that runs millions of scheduled jobs (one-off, cron, interval, in users' time zones) on time, without running any of them twice."

Every company has cron jobs; few have thought about what happens at 50 million of them. This interview teaches:
- finding "what's due now" without scanning everything;
- sharing work between scheduler nodes safely (`SKIP LOCKED`, leader per shard);
- why leases need fencing tokens, and why "exactly-once" really depends on the job's target;
- daylight-saving traps, misfires after outages, and the midnight stampede;
- turning cron chaos into a platform.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** The "charged twice" story, cron and k8s CronJob as things you already know, and the due-index + lease + run-ID picture.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why duplicates, DST and midnight are the hard parts |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates (~1,160/s average, 10M at midnight), `next_run_at` + `SKIP LOCKED` claiming, queue + workers, retries, heartbeats, both DST cases |
| [L5-senior.md](L5-senior.md) | Senior | Leases + fencing + idempotent targets, sharding with a leader per shard, time buckets / timing wheels, misfire policies, stable jitter, DAG workflows |
| [L6-staff.md](L6-staff.md) | Staff | Triggers vs batch vs workflows, tenant fairness, multi-region ownership, build vs buy, run timelines, safe migration |

**Suggested order:** product page → L4 → L5 → L6 → [Task Scheduler LLD](../../../LLD/interviews/task-scheduler/README.md) (the same ideas inside one process).

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Finding due jobs | Index on `next_run_at`, `SKIP LOCKED` | Time buckets, sorted sets, timing wheel, sharding | Per-tenant quotas, fair queuing |
| No duplicates | Claim + advance in one transaction, run ID | Leases + fencing tokens + idempotent targets | Single ownership across regions; migration without double crons |
| Failures | Retries, heartbeats, timeouts | Misfire policies, backlog through rate limits | Region failover, independent missed-run checker |
| Time | IANA zones, both DST cases | Stable jitter for the midnight herd | Clock skew across regions |
| Scope | Triggers | DAG workflows | Triggers / batch / workflows as separate engines; build vs buy |

## Building blocks used

**Concepts (new for this problem):** [Distributed scheduling & time buckets](../../concepts/distributed-scheduling-and-time-buckets.md) · [Workflow orchestration & DAGs](../../concepts/workflow-orchestration-and-dags.md)

**Concepts (reused):** [Distributed locks & leases](../../concepts/distributed-locks-and-leases.md) · [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Sagas & distributed transactions](../../concepts/sagas-and-distributed-transactions.md) · [Alerting & SLOs](../../concepts/alerting-and-slos.md) · [Observability](../../concepts/observability.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**LLD concepts:** [Cron & recurring schedules](../../../LLD/concepts/cron-and-recurring-schedules.md) · [Timers, delay queues & timing wheels](../../../LLD/concepts/timers-delay-queues-and-timing-wheels.md)

**Technologies:** [PostgreSQL](../../technologies/postgresql.md) · [Redis](../../technologies/redis.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md) · [Message queues](../../technologies/message-queues.md) · [Kafka](../../technologies/kafka.md)

**Related LLD:** [Task Scheduler](../../../LLD/interviews/task-scheduler/README.md) (heap + dispatcher, cron + DST, retries) · roadmap pair: [Chess](../../../LLD/interviews/chess/README.md)

## The core insight

1. **Index by time, claim before running.** Finding due work is an indexing problem; sharing it is a claiming problem.
2. **Leases aren't locks.** Pauses make two owners possible; fencing protects your state and idempotency protects everyone else's.
3. **Time is a product requirement.** Time zones, DST, misfires and midnight peaks are where real schedulers fail, not in the happy path.
