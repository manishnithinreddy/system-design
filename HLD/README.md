# HLD — High-Level Design

## Interviews
| Problem | What it mainly teaches |
|---|---|
| [URL Shortener](interviews/url-shortener/README.md) | Read-heavy design, caching, ID generation, KV storage, async analytics, multi-region consistency |
| [Notification System](interviews/notification-system/README.md) | Async pipelines, queues, priorities, retries/backoff/DLQ, idempotency, fan-out, third-party providers, cost |
| [Chat System](interviews/chat-system/README.md) | Stateful connection fleets, ordering with sequence numbers, offline sync, group fan-out, presence, media, E2EE consequences |
| [News Feed](interviews/news-feed/README.md) | Hybrid fan-out (celebrity problem), precomputed feed caches, read-time filtering, ranking pipelines, counters, cursor pagination |
| [Ride-Sharing (Uber)](interviews/ride-sharing/README.md) | Geospatial indexing of moving objects, exclusive assignment with leases, trip state machines, surge via stream processing, payment sagas, city cells |
| [API Gateway](interviews/api-gateway/README.md) | Request-path engineering: routing, edge auth, rate limiting, resilience, control vs data plane, safe config, observability, platform ownership |
| [Distributed KV Store](interviews/distributed-kv-store/README.md) | Database internals: partitioning, quorums, conflicts, repair, gossip, LSM storage, Raft, AP vs CP |
| [Web Crawler](interviews/web-crawler/README.md) | Politeness-driven scheduling (per-host queues + heap), URL/content dedup (Bloom filters, SimHash), spider traps, recrawl freshness, partition by host, crawl budget |
| [Search Autocomplete](interviews/search-autocomplete/README.md) | Offline build vs in-memory serving, tries with top-k, replicate vs shard, trending via streams, Count-Min Sketch, safety and privacy of suggestions |
| [Video Streaming](interviews/video-streaming/README.md) | Resumable uploads, chunked parallel transcoding, bitrate ladders, segments + manifests, CDN at Tbps, storage tiering, live streaming, cost model |

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
| [Stream processing (Flink, Kafka Streams)](technologies/stream-processing.md) | Continuous computations over event streams: windows, state, late events |
| [Elasticsearch](technologies/elasticsearch.md) | Full-text and search-as-you-type over documents; shards, refresh, suggesters |
| [DNS](technologies/dns.md) | Turning names into IPs; TTLs, GeoDNS, why DNS failover is slow, DNS as a crawler bottleneck |
| [Service mesh & Envoy](technologies/service-mesh-and-envoy.md) | Programmable proxies for routing, mTLS, retries and telemetry |

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
| [Geospatial indexing](concepts/geospatial-indexing.md) | How do you find what's near a point, fast? (geohash, H3, quadtrees) |
| [Distributed locks & leases](concepts/distributed-locks-and-leases.md) | How do you make "only one" happen across many servers? |
| [Sagas & distributed transactions](concepts/sagas-and-distributed-transactions.md) | How do multi-service workflows (like payments) stay consistent? |
| [Authentication, OAuth & JWT](concepts/authentication-oauth-jwt.md) | Who is calling, and how do we know without asking every time? |
| [Service discovery](concepts/service-discovery.md) | How does a caller find healthy instances of a service? |
| [TLS & mTLS](concepts/tls-and-mtls.md) | Encryption and identity on the wire |
| [Observability](concepts/observability.md) | Metrics, logs and traces: knowing what your system is doing |
| [Resilience patterns](concepts/resilience-patterns.md) | Timeouts, circuit breakers, bulkheads, load shedding |
| [LSM trees & storage engines](concepts/lsm-trees-and-storage-engines.md) | How do databases write fast and read efficiently from disk? |
| [URL frontier & politeness](concepts/url-frontier-and-politeness.md) | How does a crawler fetch important pages first without hammering any one site? |
| [Content fingerprinting & dedup](concepts/content-fingerprinting-and-dedup.md) | How do you spot identical and near-identical pages (hashes, SimHash, MinHash)? |
| [Tries & prefix search](concepts/tries-and-prefix-search.md) | How do you find everything starting with a prefix, fast? |
| [Inverted index](concepts/inverted-index.md) | How do search engines find documents containing words? |
| [Top-k & heavy hitters](concepts/top-k-and-heavy-hitters.md) | How do you find the most frequent items, even in an endless stream? |
| [Adaptive bitrate streaming](concepts/adaptive-bitrate-streaming.md) | How does video keep playing when your connection changes? (HLS, DASH) |
| [Video transcoding pipeline](concepts/video-transcoding-pipeline.md) | How does one upload become many qualities, fast? |
| [Resumable & chunked uploads](concepts/resumable-and-chunked-uploads.md) | How do big uploads survive bad networks? |
| [Gossip & failure detection](concepts/gossip-and-failure-detection.md) | How do hundreds of nodes know who's alive without a central list? |
| [Vector clocks & conflict resolution](concepts/vector-clocks-and-conflict-resolution.md) | What happens when two replicas accept different writes? |
| [Merkle trees & anti-entropy](concepts/merkle-trees-and-anti-entropy.md) | How do replicas find their differences cheaply? |
| [Hinted handoff & sloppy quorum](concepts/hinted-handoff-and-sloppy-quorum.md) | How do writes succeed when replicas are down, and what does it cost? |
| [Consensus & Raft](concepts/consensus-and-raft.md) | How do machines agree on one order of operations despite failures? |
