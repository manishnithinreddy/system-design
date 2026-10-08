# Metrics & Monitoring (Prometheus-like) — L6 (Staff) Interview

> **Level expectation:** the L5 platform (sharded ingesters, compression, cardinality limits, downsampling, clustered alerting) is known. The staff conversation is about **monitoring that survives the outage it reports**, running it as a **multi-tenant platform with a budget**, **SLO-based alerting** that reduces pages, how metrics fit with **logs, traces and events**, global views across regions, what the biggest companies built, and build vs buy. Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Monitoring must outlive what it monitors

**🧑‍💼 Interviewer:** Last quarter, a network incident took down production and the monitoring system together. Engineers debugged blind for 40 minutes. Fix that.

**🧑‍💻 Candidate:** Design for **independent failure**:
- **Separate failure domain:** monitoring runs on its own cluster, its own nodes, ideally its own network path and region. It must not depend on production's service mesh, DNS, identity provider or database.
- **Minimal dependencies on the alert path:** the path "evaluate rule → send page" must work even if dashboards, long-term storage and object storage are down. Keep rule evaluation on recent in-memory data.
- **Per-region monitoring with a global view on top:** each region has its own stack (a region outage doesn't blind the others), and global dashboards query across them (§5).
- **Meta-monitoring:** a small, separate system (or a second provider) watches the monitoring stack itself: ingestion lag, rule evaluation failures, Alertmanager health.
- **Dead man's switch:** an alert that *always* fires ("Watchdog"), routed to an external service that pages **if it stops receiving it**. That's how you learn the alerting pipeline is broken.

> 📝 **Note:** "Who monitors the monitoring?" is the first staff-level question here. The dead man's switch is the concrete answer interviewers like.
>
> 💡 **Infra analogy:** the same reason your out-of-band management network for servers doesn't run through the production switches.

---

## 2. A multi-tenant platform with a budget

**🧑‍💻 Candidate:** With 200 teams, metrics is a platform, and its main cost driver (series × retention) is controlled by *other* teams' code. So:
- **Quotas per tenant:** active series, samples/s, query concurrency. Defaults are generous; increases are a request with a reason.
- **Showback / chargeback:** each team sees its own series count and cost. Arithmetic example (assumed prices): 50M series at ~4 KB RAM each = 200 GB RAM in ingesters × 3 replicas = 600 GB; if a team owns 10M of those series, it drives ~20% of ingester cost. Showing that number changes behaviour faster than any guideline.
- **Defaults that prevent mistakes:** client libraries that refuse label values from unbounded sets, linting in CI for new metrics, and dashboards of "series added this week by team".
- **Retention tiers per tenant:** not every team needs raw data for 15 days.

---

## 3. SLO-based alerting: fewer, better pages

**🧑‍💼 Interviewer:** On-call gets 40 pages a week, most not actionable. What would you change?

**🧑‍💻 Candidate:** Page on **user-visible symptoms measured against an SLO**, not on causes like CPU ([alerting & SLOs](../../concepts/alerting-and-slos.md)):
- **SLI:** fraction of checkout requests that succeed in under 300 ms.
- **SLO:** 99.9% over 30 days. **Error budget:** 0.1% of 30 days = 0.001 × 30 × 24 × 60 = **43.2 minutes** of full outage equivalent.
- **Burn rate:** how fast the budget is being spent relative to "exactly on target". A burn rate of 1 uses the whole budget in 30 days.

Multi-window, multi-burn-rate alerts (the approach in Google's SRE Workbook):

| Burn rate | Long window | Short window | Budget used if it continues for the long window | Action |
|---|---|---|---|---|
| 14.4 | 1 h | 5 min | 14.4 × 1 h ÷ 720 h = **2%** | Page |
| 6 | 6 h | 30 min | 6 × 6 ÷ 720 = **5%** | Page |
| 1 | 3 days | 6 h | 1 × 72 ÷ 720 = **10%** | Ticket |

The **short window** makes the alert stop quickly after recovery; the **long window** ignores brief blips. CPU, memory and queue-depth alerts become **tickets or dashboard annotations**, not pages, unless they predict an imminent SLO breach (e.g. disk full in 4 hours).

> 📝 **Note:** This converts an alerting debate into arithmetic. It also gives product and engineering a shared number (the budget) for "can we ship risky changes this week?".

---

## 4. Metrics, logs, traces, events: one investigation

| Signal | Good at | Cost profile |
|---|---|---|
| **Metrics** | "Is something wrong? Since when? How bad, where?" | Cheap per data point; limited by cardinality |
| **Logs** | "What exactly happened in this request?" | Expensive per byte; high cardinality is fine |
| **Traces** | "Where did the time go across services?" | Sampled; per-request detail |
| **Events** (deploys, config changes, failovers) | "What changed right before?" | Tiny volume, huge value |

Connect them ([observability](../../concepts/observability.md)):
- **Exemplars:** a histogram bucket stores one sample trace ID ("a request in the 2–5 s bucket: trace abc123"), so a latency spike on a graph links to a real slow trace.
- **Shared labels:** `service`, `region`, `version` named identically in metrics, logs and traces (OpenTelemetry semantic conventions help).
- **Deploy and config events as annotations** on every dashboard; most incidents are changes.
- High-cardinality questions ("which customer?") go to logs/traces, which removes the pressure to put user IDs in metric labels (L5 §3.3).

---

## 5. Multi-region and the global view

- **Write locally:** each region scrapes and stores its own data. Cross-region ingestion would make monitoring depend on the WAN and double the bandwidth.
- **Query globally:** a global query layer fans out to each region's queriers (or reads all regions' blocks from replicated object storage) and merges. Global dashboards are slower and degrade gracefully: if one region is unreachable, show the rest with a warning.
- **Alert locally** for regional symptoms (works during a WAN partition); evaluate global SLOs centrally with recording rules that each region pre-aggregates.
- **Pre-aggregate before shipping:** a global "requests per service" needs a few thousand series, not all 50M.

---

## 6. What the biggest companies built

- **Facebook Gorilla** (VLDB 2015): an in-memory time-series database for the last 26 hours, with the compression scheme everyone now uses (L5 §3.1).
- **Uber M3** ([Uber blog, Aug 2018](https://www.uber.com/blog/m3/)): M3DB (distributed time-series store with its own Gorilla-derived compression), an aggregator, a Prometheus-compatible query layer; at the time Uber reported over **6.6 billion time series**, about **500 million metrics/s aggregated** and **20 million metrics/s persisted** after aggregation. Note the shape: aggregation before storage cut what's stored by ~25× (500M ÷ 20M). See the [Uber case study](../../../case-studies/uber-from-monolith-to-h3-and-microservices.md).
- **Prometheus at scale** in the open-source world: Thanos (sidecar + object storage), Cortex → Grafana Mimir (horizontally scaled, multi-tenant), VictoriaMetrics.

> 📝 **Lesson from M3:** at extreme scale, **aggregate early**. Most per-pod series are only ever queried as sums; storing the sum instead of every pod's series is the single biggest saving.

---

## 7. Build vs buy

| Option | When |
|---|---|
| **Single Prometheus per cluster + Grafana** | Small/medium: a few million series, short retention |
| **Prometheus + Thanos / Mimir / VictoriaMetrics** (self-run) | Many clusters, long retention, multi-tenant; a platform team to run it |
| **Managed Prometheus** (cloud providers' managed services, Grafana Cloud) | Want PromQL and Grafana without operating storage |
| **SaaS observability** (Datadog, New Relic, …) | Speed and integrations matter more than cost; watch per-series/per-host pricing at scale |
| **Build your own TSDB** | Only at Uber/Meta scale with unique needs |

**🧑‍💻 Candidate:** Most companies should run or buy a Prometheus-compatible stack and invest in **cardinality governance, SLOs and alert quality**, which no vendor solves for you.

---

## 8. Curveballs

**🧑‍💼 Interviewer:** The metrics bill doubled in six months while traffic grew 20%.

**🧑‍💻 Candidate:** Cost tracks series, not traffic. Look at series growth by tenant and metric: typically a few new labels (pod names from frequent deploys creating churn, `path` with IDs, new histograms with many buckets × many labels). Fix with limits, relabelling, dropping unused metrics (query logs show which series are never read), and shorter raw retention for high-churn sources.

**🧑‍💼 Interviewer:** During a big incident, dashboards time out exactly when 300 engineers open them.

**🧑‍💻 Candidate:** Incident load is predictable: everyone opens the same few dashboards. Results caching (L5 §3.6) and recording rules make those cheap; per-user query concurrency limits stop one heavy ad-hoc query from starving others; and a small set of "incident dashboards" built only from recording rules should be guaranteed fast.

**🧑‍💼 Interviewer:** A team wants per-customer latency metrics for their top 50,000 customers.

**🧑‍💻 Candidate:** 50,000 customers × endpoints × histogram buckets is millions of series. Offer alternatives: metrics for the top ~100 customers by name plus an "other" bucket; per-customer detail via traces/logs queried on demand; or a separate analytics store (columnar/OLAP) built for high-cardinality aggregation rather than the real-time TSDB.

---

## 9. What the interviewer was evaluating (L6)

- [ ] Independent failure domain, minimal alert-path dependencies, meta-monitoring, dead man's switch
- [ ] Platform thinking: quotas, showback with arithmetic, defaults that prevent cardinality mistakes
- [ ] SLOs, error budgets and multi-window burn-rate alerts with arithmetic; causes as tickets
- [ ] Metrics/logs/traces/events connected via exemplars, shared labels, change annotations
- [ ] Local write, global query, local alerting, pre-aggregation
- [ ] Awareness of Gorilla/M3/Thanos/Mimir and the "aggregate early" lesson
- [ ] Build vs buy grounded in scale and team capacity

## 10. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| Monitoring in the same failure domain as production | Blind during the incidents that matter most |
| Paging on CPU and memory | Alert fatigue; real user pain gets lost |
| Treating metrics as free | Cost tracks series count, which other teams control |
| Putting every question into metrics | Cardinality explosions; use logs/traces for high-cardinality detail |
| Centralising ingestion across regions | WAN outage = no monitoring anywhere |
| No test of the alerting path | You find out it was broken during an outage |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
