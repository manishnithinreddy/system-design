# HLD — High-Level Design

## Interviews
| Problem | What it mainly teaches |
|---|---|
| [URL Shortener](interviews/url-shortener/README.md) | Read-heavy design, caching, ID generation, KV storage, async analytics, multi-region consistency |
| [Notification System](interviews/notification-system/README.md) | Async pipelines, queues, priorities, retries/backoff/DLQ, idempotency, fan-out, third-party providers, cost |
| [Chat System](interviews/chat-system/README.md) | Stateful connection fleets, ordering with sequence numbers, offline sync, group fan-out, presence, media, E2EE consequences |
| [News Feed](interviews/news-feed/README.md) | Hybrid fan-out (celebrity problem), precomputed feed caches, read-time filtering, ranking pipelines, counters, cursor pagination |

## Technologies (tools)
| | What problem it solves |
|---|---|
| [Load balancer](technologies/load-balancer.md) | Spread traffic across servers, route around dead ones |
| [Redis](technologies/redis.md) | Sub-millisecond reads and atomic counters in memory |
| [PostgreSQL](technologies/postgresql.md) | Relational data, transactions, constraints |
| [Cassandra / DynamoDB](technologies/cassandra.md) | Huge write volume / data size with simple key-based access |
| [Kafka](technologies/kafka.md) | Durable event stream that decouples producers from consumers |
| [CDN](technologies/cdn.md) | Serve responses close to users, absorb traffic at the edge |
| [ZooKeeper / etcd](technologies/zookeeper-etcd.md) | Small, strongly consistent coordination data: leaders, locks, config, ID ranges |
| [Message queues (SQS / RabbitMQ)](technologies/message-queues.md) | Hand work to background workers with acks, retries and dead-letter queues |
| [Push / email / SMS providers](technologies/push-email-sms-providers.md) | Actually delivering messages to phones and inboxes (APNs, FCM, Twilio, SES) |
| [WebSockets & SSE](technologies/websockets-and-sse.md) | Pushing data from server to an open app/browser in real time |
| [Pub/sub](technologies/pub-sub.md) | Broadcasting events to whoever is subscribed (Redis Pub/Sub, Kafka, NATS) |
| [Object storage (S3)](technologies/object-storage.md) | Storing and serving files/blobs cheaply and durably |
| [Graph databases & adjacency lists](technologies/graph-databases.md) | Storing who-follows-whom; when a real graph DB is worth it |

## Concepts (ideas)
| | Question it answers |
|---|---|
| [Back-of-the-envelope](concepts/back-of-the-envelope.md) | How big is this, really? |
| [ID generation](concepts/id-generation.md) | How do I create unique IDs at scale? |
| [Caching strategies](concepts/caching-strategies.md) | When do I read/write the cache vs the DB, and what goes wrong? |
| [Consistent hashing](concepts/consistent-hashing.md) | How do I spread keys across nodes that come and go? |
| [Sharding & replication](concepts/sharding-and-replication.md) | How do I outgrow one database machine? |
| [CAP & consistency](concepts/cap-and-consistency.md) | What happens to correctness when the network breaks? |
| [Idempotency & delivery semantics](concepts/idempotency-and-delivery-semantics.md) | How do retries not cause duplicates? What does "exactly once" really mean? |
| [Retries, backoff & DLQ](concepts/retries-backoff-and-dlq.md) | When and how to retry without making an outage worse? |
| [Fan-out](concepts/fan-out.md) | How does one event become millions of deliveries? (and the outbox pattern) |
| [Message ordering & sequencing](concepts/message-ordering-and-sequencing.md) | How do all devices see messages in the same order when clocks disagree? |
| [Presence & heartbeats](concepts/presence-and-heartbeats.md) | How do we know who's online, cheaply? |
| [End-to-end encryption](concepts/end-to-end-encryption.md) | What can (and can't) the server do when it can't read messages? |
| [Feed ranking](concepts/feed-ranking.md) | How do "best first" feeds pick and order posts? |
| [Pagination](concepts/pagination.md) | Why cursors beat page numbers on live data |
| [Counters at scale](concepts/counters-at-scale.md) | How do you count millions of likes without a hot row? |
| [Bloom filters](concepts/bloom-filters.md) | "Definitely not / probably yes" membership in tiny memory |
