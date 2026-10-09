# Ad Click Aggregation — L6 (Staff) Interview

> **Level expectation:** the [L4](L4-mid.md)/[L5](L5-senior.md) pipeline is assumed. The staff conversation:
> - treating click counts as **money**: auditability, data contracts, data-quality SLOs;
> - multi-region collection without losing or double-counting;
> - cost (storage, compute, query);
> - build vs buy for the streaming engine and the OLAP store;
> - evolving the pipeline without breaking billing.

> 🆕 Read [00-understand-the-product.md](00-understand-the-product.md), [L4](L4-mid.md) and [L5](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Framing

**🧑‍💼 Interviewer:** You own the ads measurement platform. What do you worry about first?

**🧑‍💻 Candidate:** That **counts are money**. A 0.5% undercount at ₹4/click on 1B clicks/day is 5M clicks × ₹4 = **₹2 crore a day** of lost revenue. A 0.5% overcount is the same amount overcharged to advertisers: a legal and trust problem. So:
1. **Correctness is measured**, not assumed: data-quality SLOs alongside latency SLOs.
2. **Every number is explainable:** from an invoice line back to raw clicks and the rules applied.
3. **Changes are versioned:** a fraud rule or pipeline change can be replayed, diffed and approved before it touches billing.

---

## 2. Data quality as an SLO

| SLI | How measured | Example target |
|---|---|---|
| Freshness | Now − latest window emitted | p99 < 2 min |
| Completeness | Clicks in Kafka vs clicks in aggregates (per hour, per partition) | ≥ 99.9% before batch, 100% after |
| Stream vs batch drift | Reconciliation diff per ad, excluding labelled reasons | < 0.1% unexplained |
| Duplicate rate | Duplicates caught / total | Tracked; spikes alert (a client bug) |
| Late rate | Events beyond watermark / total | Tracked; spikes mean an ingestion problem |

**Canary events:** synthetic clicks with known IDs injected every minute in every region, verified end to end (the same idea as the canary producer in the [message queue L6](../distributed-message-queue/L6-staff.md)). If a canary is missing from the aggregates, the pipeline is losing data, even when everything looks green.

---

## 3. Multi-region

**🧑‍💼 Interviewer:** Clicks come from India, Europe and the US. Where do we aggregate?

**🧑‍💻 Candidate:**
- **Collect locally:** each region's click service writes to a regional Kafka cluster. Low latency for the redirect, no cross-ocean dependency.
- **Aggregate regionally, merge globally:** regional stream jobs produce per-(ad, minute) partial counts, and a global job sums the regional partials. Partials are tiny compared with raw clicks, so cross-region traffic drops by roughly 1,000×.
- **Raw archives** stay in-region where data residency requires it; the batch recount runs per region, and only aggregates cross borders.
- **Region outage:** regional Kafka retains data; when the region recovers, its jobs catch up. Watermarks for those partitions lag, and the global view marks that region "delayed" rather than silently showing lower numbers.
- **Dedup across regions:** a click ID is unique globally (region prefix + ID), and an ad's clicks from two regions are different clicks, so no cross-region dedup is needed. The only risk is the same event written to two regions by a failover in the click service. Handle it by dedup in the batch path on click ID across regions.

---

## 4. Cost

| Item | Arithmetic | Lever |
|---|---|---|
| Raw archive | 200 GB/day raw → ~40 GB/day in compressed Parquet (assume 5×) → ~15 TB/year | Tiered storage classes; delete after the audit period |
| Minute aggregates | ~1.4B rows/day at full dimensions | Roll up to hours after 7 days; limit high-cardinality dimensions |
| Stream compute | Dominated by dedup and state | Bloom filters for dedup; salting only hot ads |
| Queries | Dashboards refreshing every 10 s × thousands of advertisers | Cache results per (ad, range) for ~30 s; pre-compute top-N |

**Dimension explosion** is the classic cost trap. Adding "device model" (thousands of values) to the key multiplies aggregate rows. Every new dimension needs a cost estimate and an owner. Some dimensions belong only in batch tables queried on demand, not in real-time aggregates.

---

## 5. Build vs buy

| Layer | Options | Notes |
|---|---|---|
| Stream engine | Flink, Kafka Streams, Spark Structured Streaming, managed (Kinesis Data Analytics / Dataflow) | Flink: strongest event-time and exactly-once support; Kafka Streams: a library, no separate cluster, Kafka-only |
| OLAP store | Druid, Pinot, ClickHouse, BigQuery/Snowflake (batch-ish) | Druid/Pinot: real-time ingestion from Kafka with sub-second queries; ClickHouse: very fast SQL, simpler ops; warehouses: cheap for batch, slower for live dashboards |
| Batch | Spark on object storage, warehouse SQL | Billing recount, fraud model features |
| Fraud | In-house rules + ML; third-party verification vendors | Advertisers often require third-party verification anyway |

**My pick for this scale:** Flink for streaming, Pinot or ClickHouse for live aggregates, Spark or the warehouse for the batch recount. The same Kafka topics feed both paths. Product capabilities change, so I'd validate with a load test on our own data first.

---

## 6. Evolving the pipeline safely

1. **Data contracts:** the click event has a versioned schema (registry, compatibility rules). The click team can't silently rename `country`.
2. **Shadow pipelines:** a new job version runs in parallel on the same input, writing to a shadow table. Diff per ad per hour for a week, then switch.
3. **Backfills** use the same code as the live job (Kappa-style replay from the archive), so there's one definition of "a click".
4. **Billing freeze:** invoices are generated from a snapshot of the batch tables with a version ID. A later recount produces adjustments, never silent edits.

---

## 7. Curveballs

**🧑‍💼 Interviewer:** An advertiser claims they were charged for 30% fraudulent clicks.

**🧑‍💻 Candidate:** We can answer from the archive: their clicks broken down by validity reason, device, IP range and time, and how our filters scored them. If the claim holds, credit notes plus a rule fix, replayed across affected advertisers. Explainability (stored reasons per click) is what makes this a query rather than an investigation.

**🧑‍💼 Interviewer:** A bug duplicated 3% of events for 6 hours.

**🧑‍💻 Candidate:** The dashboard was inflated for those hours; the batch dedup on click ID removes duplicates before billing. Reconciliation flags the drift (that's how we'd notice), we fix the producer, and we re-run the stream aggregates for those hours from Kafka to correct the dashboard history.

---

## 8. What the interviewer was evaluating (L6)

- [ ] Framed counts as money with the cost of 0.5% error
- [ ] Data-quality SLOs, canary events, reconciliation as monitoring
- [ ] Multi-region: local collection, regional partials, residency, outage behaviour
- [ ] Cost model and dimension explosion
- [ ] Reasoned build vs buy for stream engine and OLAP store
- [ ] Safe evolution: contracts, shadow pipelines, versioned billing snapshots

## 9. Common mistakes at this level

| Mistake | Why it hurts |
|---|---|
| Only latency SLOs | Silent data loss looks healthy |
| Shipping raw clicks across regions for one global job | Cost, latency, residency problems |
| Adding dimensions without cost review | Aggregate tables explode |
| Editing past invoices after a recount | Breaks audit trails and trust |
| Separate code for backfill and live paths | Two definitions of a click drift apart |

⬅️ Back to [README.md](README.md)
