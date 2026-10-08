# HLD — High-Level Design

## Interviews
| Problem | What it mainly teaches |
|---|---|
| [URL Shortener](interviews/url-shortener/README.md) | Read-heavy design, caching, ID generation, KV storage, async analytics, multi-region consistency |
| [Notification System](interviews/notification-system/README.md) | Async pipelines, queues, priorities, retries/backoff/DLQ, idempotency, fan-out, third-party providers, cost |

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
