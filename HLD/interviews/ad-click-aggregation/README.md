# HLD Interview: Design Ad Click Aggregation (Real-time Analytics)

> "Design a system that counts a billion ad clicks a day, shows advertisers live per-minute numbers, and bills them exactly."

This interview teaches streaming analytics: counting events in **time windows**, deciding when a window is "done" when events arrive late (**watermarks**), keeping counts exact across crashes, handling hot keys, and why the live dashboard and the invoice are computed twice on purpose (**Lambda vs Kappa**). The same pipeline shape powers metrics, product analytics, fraud detection and leaderboards.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** The "dashboard says 12,408, invoice says 12,131" story, and the count-in-windows picture.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why fast counts and correct counts differ |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates (~11.6k/s average, 200 GB/day raw), Kafka keyed by ad, 1-minute tumbling windows, OLAP store + roll-ups, raw archive, dedup, upsert sinks |
| [L5-senior.md](L5-senior.md) | Senior | Event time + watermarks + late events (with state arithmetic), exactly-once via checkpoints + idempotent sinks, salted two-stage aggregation for hot ads, Lambda reconciliation, fraud |
| [L6-staff.md](L6-staff.md) | Staff | Counts as money, data-quality SLOs and canaries, multi-region partials, cost and dimension explosion, build vs buy, safe pipeline evolution |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Windows | 1-minute tumbling | Event time, watermarks, late events | Freshness and completeness SLOs |
| Correctness | Dedup by click ID, upserts | Checkpoints + offsets, exactly-once effect | Canary events, versioned billing snapshots |
| Scale | Partition by ad | Salted two-stage aggregation for hot ads | Regional partials merged globally |
| Two numbers | Batch recount exists | Lambda vs Kappa, nightly reconciliation | Drift as a monitored SLI |
| Fraud | — | Real-time rules + batch models | Explainable disputes, credit notes |

## Building blocks used

**Concepts (new for this problem):** [Windowing, watermarks & late events](../../concepts/windowing-watermarks-and-late-events.md) · [Lambda vs Kappa architecture](../../concepts/lambda-vs-kappa-architecture.md)

**Concepts (reused):** [Counters at scale](../../concepts/counters-at-scale.md) · [Top-k & heavy hitters](../../concepts/top-k-and-heavy-hitters.md) · [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Bloom filters](../../concepts/bloom-filters.md) · [Time-series compression & downsampling](../../concepts/time-series-compression-and-downsampling.md) · [Payment reconciliation](../../concepts/payment-reconciliation.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Technologies:** [Kafka](../../technologies/kafka.md) · [Stream processing (Flink, Kafka Streams)](../../technologies/stream-processing.md) · [Object storage (S3)](../../technologies/object-storage.md)

**Under the Hood:** [HyperLogLog](../../../under-the-hood/hyperloglog.md) (unique clickers in 12 KB)

**Related HLD:** [Metrics & Monitoring](../metrics-monitoring/README.md) (the same "count in time buckets" problem for machines) · [Distributed Message Queue](../distributed-message-queue/README.md) (the Kafka underneath)

**Roadmap pair (LLD):** [Meeting-Room Booking](../../../LLD/interviews/meeting-room-booking/README.md)

## The core insight

1. **Count by when it happened, not when it arrived.** Watermarks decide when a window is done, and that's a trade-off between freshness and completeness.
2. **Exactly-once is a property of the whole pipeline:** replayable input, checkpointed state, idempotent output.
3. **Two numbers on purpose:** a fast one for decisions, an exact one for money, reconciled every night.
