# HLD — High-Level Design

## Interviews
| Problem | What it mainly teaches |
|---|---|
| [URL Shortener](interviews/url-shortener/README.md) | Read-heavy design, caching, ID generation, KV storage, async analytics, multi-region consistency |

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

## Concepts (ideas)
| | Question it answers |
|---|---|
| [Back-of-the-envelope](concepts/back-of-the-envelope.md) | How big is this, really? |
| [ID generation](concepts/id-generation.md) | How do I create unique IDs at scale? |
| [Caching strategies](concepts/caching-strategies.md) | When do I read/write the cache vs the DB, and what goes wrong? |
| [Consistent hashing](concepts/consistent-hashing.md) | How do I spread keys across nodes that come and go? |
| [Sharding & replication](concepts/sharding-and-replication.md) | How do I outgrow one database machine? |
| [CAP & consistency](concepts/cap-and-consistency.md) | What happens to correctness when the network breaks? |
