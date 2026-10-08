# HLD Interview: Design a Metrics & Monitoring System (Prometheus-like)

> "Design a system that collects metrics from 50,000 services and hosts, stores them for a year, powers dashboards and pages the right engineer when something breaks."

This is the infra engineer's home ground. It teaches **write-heavy time-series storage** (millions of samples per second), **compression** that turns terabytes into gigabytes, **cardinality** as the hidden scaling limit, **downsampling** for long history, and how to run alerting so it pages less and catches more. And it asks a question unique to this system: how do you keep monitoring alive during the outage it's supposed to show?

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains series, labels, counters vs gauges vs histograms, and the scrape → store → query → alert loop, using your own Spring Boot `/actuator/prometheus` output.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why each part exists, from an on-call point of view |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates from series count, data model, pull vs push, head + WAL + blocks + label index, sharding by series, basic alerting |
| [L5-senior.md](L5-senior.md) | Senior | Gorilla compression arithmetic, RF 3 + shuffle sharding, cardinality limits, downsampling with min/max/sum/count, query splitting + caching, clustered Alertmanager |
| [L6-staff.md](L6-staff.md) | Staff | Independent failure domain + dead man's switch, platform quotas and showback, SLO burn-rate alerts, metrics/logs/traces together, global view, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Collection | Pull with service discovery vs push | OTLP push for batch jobs; bounded out-of-order window | Local per region |
| Storage | Head + WAL, 2 h blocks, inverted label index, retention | Compression ~1.37 B/sample, RF 3, compactor, downsampling tiers | Aggregate before storing (M3 lesson) |
| Scale-out | Shard by series hash | Shuffle sharding per tenant | Quotas, showback, global query layer |
| Biggest risk | Series count | Cardinality explosions | Cost governed by other teams' labels |
| Alerting | Rules with `for:`, group, route, dedupe | Sharded rulers, clustered Alertmanager, inhibition, absent-data alerts | SLO burn-rate alerts; dead man's switch |

## Building blocks used

**Concepts (new for this problem):** [Time-series compression & downsampling](../../concepts/time-series-compression-and-downsampling.md) · [Alerting & SLOs](../../concepts/alerting-and-slos.md)

**Concepts (reused):** [Observability](../../concepts/observability.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Inverted index](../../concepts/inverted-index.md) · [Service discovery](../../concepts/service-discovery.md) · [Gossip & failure detection](../../concepts/gossip-and-failure-detection.md) · [Distributed locks & leases](../../concepts/distributed-locks-and-leases.md)

**Technologies:** [Prometheus & time-series databases](../../technologies/prometheus-and-time-series-databases.md) (new) · [Kafka](../../technologies/kafka.md) · [Object storage](../../technologies/object-storage.md)

**Related LLD:** [Thread Pool / Connection Pool](../../../LLD/interviews/thread-pool/README.md): the active/idle/pending pool gauges you scrape from every service, and why saturation metrics matter.

**Case study:** [Uber: from monolith to H3 and microservices](../../../case-studies/uber-from-monolith-to-h3-and-microservices.md), including M3, Uber's metrics platform.

## The core insight

1. **Series count drives everything:** memory, ingestion, cost. Control cardinality and the rest is engineering.
2. **Time-series data is special:** append-only, ordered, similar neighbours. Exploit it with per-series chunks, delta-of-delta and XOR compression, immutable blocks and downsampling.
3. **Alert on symptoms, against an error budget.** Fewer pages, each one meaning users are hurting.
4. **Monitoring must fail independently** of what it monitors, and something must watch the watcher.
