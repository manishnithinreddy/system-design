# HLD Interview: Design a Distributed Message Queue (Kafka Internals)

> "Design a distributed message queue like Kafka: producers publish millions of messages per second, many consumer groups read them independently, and nothing acknowledged is ever lost."

You've almost certainly *used* Kafka; this interview asks you to build it. It teaches the storage idea behind half of modern infrastructure (the **append-only replicated log**). It covers what "committed" really means when servers crash, why consumer groups and offsets are so simple and still so subtle, the honest meaning of "exactly-once", and how a shared log becomes a company-wide platform.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** The "checkout went down with analytics" story, queue vs log, and the log-with-bookmarks picture.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why queues exist and how a log differs from a queue |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates (~1 GB/s, ~0.9 PB, ~50 brokers), partitions + keys, consumer groups + offsets, leader/follower replication with acks, retention, segment files + sparse index |
| [L5-senior.md](L5-senior.md) | Senior | High watermark + ISR + `min.insync.replicas`, leader epochs and truncation, unclean election, KRaft controller, rebalancing storms, idempotent producer + transactions, throughput tricks, compaction |
| [L6-staff.md](L6-staff.md) | Staff | Multi-tenancy and quotas, tiered storage, multi-region with offset translation, reassignment and SLIs, schemas, log vs queue, build vs buy, cross-zone cost |

**Suggested order:** product page → L4 → L5 → L6 → [Kafka's speed tricks](../../../under-the-hood/kafka-speed-tricks.md) → [Pub-Sub Broker LLD](../../../LLD/interviews/pub-sub-broker/README.md).

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Storage | Append-only partitions, segments, sparse index | Page cache, zero-copy, compaction | Tiered storage to S3, cost model |
| Replication | Leader/followers, acks 0/1/all | ISR, high watermark, min ISR, leader epochs | Cross-region mirroring, offset translation |
| Coordination | "A controller picks leaders" | Raft controller quorum, fencing, failover time vs partition count | Reassignment, balancing, rolling upgrades |
| Consumers | Groups, offsets, commit timing | Rebalancing protocols, static membership | Lag SLIs, replay isolation |
| Guarantees | At-least-once + idempotent consumers | Idempotent producer, transactions | End-to-end verification, outbox + CDC |
| Platform | One cluster | Tuning batching and acks | Quotas, schemas, log vs queue, build vs buy |

## Building blocks used

**Concepts (new for this problem):** [Log replication & ISR](../../concepts/log-replication-and-isr.md) · [Log segments, retention & compaction](../../concepts/log-segments-retention-and-compaction.md) · [Consumer groups & rebalancing](../../concepts/consumer-groups-and-rebalancing.md)

**Concepts (reused):** [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Consensus & Raft](../../concepts/consensus-and-raft.md) · [Distributed locks & leases](../../concepts/distributed-locks-and-leases.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Sagas & distributed transactions](../../concepts/sagas-and-distributed-transactions.md) · [Alerting & SLOs](../../concepts/alerting-and-slos.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Technologies:** [Kafka](../../technologies/kafka.md) · [Message queues (SQS / RabbitMQ)](../../technologies/message-queues.md) · [Pub/sub](../../technologies/pub-sub.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md) · [Object storage (S3)](../../technologies/object-storage.md)

**Under the Hood:** [Kafka's speed tricks](../../../under-the-hood/kafka-speed-tricks.md): sequential I/O, page cache and zero-copy, with a runnable demo · [B-tree](../../../under-the-hood/b-tree.md) (the random-write structure a log avoids)

**Case study:** [Amazon: from the Dynamo paper to DynamoDB](../../../case-studies/amazon-dynamo-to-dynamodb.md): another "research design → managed service" story, with partitioning, replication and the trade between tunability and predictability.

**Related LLD:** [Pub-Sub Broker](../../../LLD/interviews/pub-sub-broker/README.md): topics, subscribers, back-pressure, acks, redelivery and consumer groups inside one process, in Java and JavaScript.

## The core insight

1. **A log is the simplest durable data structure:** append at the end, read from a position. Everything else (consumer groups, replay, replication, compaction) is built on "a position is just a number".
2. **"Committed" is a replication fact, not a disk fact.** A message is safe when every in-sync replica has it, and readers never see past that line.
3. **Partitions are the unit of everything:** ordering, parallelism, replication and failover. Choose their count and keys carefully, because changing them later is hard.
