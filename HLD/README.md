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
| [Metrics & Monitoring](interviews/metrics-monitoring/README.md) | Time-series storage, Gorilla compression, cardinality limits, downsampling, alerting at scale, SLO burn rates, monitoring that survives outages |
| [Collaborative Editor](interviews/collaborative-editor/README.md) | Operational transformation vs CRDTs, one sequencer per document, local-first latency, op log + snapshots, presence, failover with fencing |
| [File Storage & Sync](interviews/file-storage-sync/README.md) | Metadata vs content-addressed blocks, commit-then-upload-missing, journal + cursor sync, three-way conflicts, erasure coding, safe GC, storage economics |
| [Payment System](interviews/payment-system/README.md) | Payment state machine, idempotency keys end to end, PENDING for unknown outcomes, double-entry ledger, outbox + saga, reconciliation, payouts, multi-PSP routing, fraud |
| [Distributed Message Queue (Kafka internals)](interviews/distributed-message-queue/README.md) | Partitioned append-only logs, consumer groups and offsets, ISR + high watermark, leader epochs, rebalancing, idempotent producer + transactions, compaction, tiered storage, multi-region |
| [Distributed Job Scheduler](interviews/distributed-job-scheduler/README.md) | next_run_at index + SKIP LOCKED claiming, leases + fencing + idempotent targets, sharding with a leader per shard, misfires, stable jitter, DST, DAGs, build vs buy |
| [Ad Click Aggregation](interviews/ad-click-aggregation/README.md) | Event-time windows, watermarks and late events, exactly-once via checkpoints + upserts, salted hot keys, Lambda reconciliation, fraud, data-quality SLOs |

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
| [Prometheus & time-series databases](technologies/prometheus-and-time-series-databases.md) | Storing and querying metrics: series, labels, PromQL, TSDB blocks, cardinality |
| [Elasticsearch](technologies/elasticsearch.md) | Full-text and search-as-you-type over documents; shards, refresh, suggesters |
| [DNS](technologies/dns.md) | Turning names into IPs; TTLs, GeoDNS, why DNS failover is slow, DNS as a crawler bottleneck |
| [Service mesh & Envoy](technologies/service-mesh-and-envoy.md) | Programmable proxies for routing, mTLS, retries and telemetry |
| [Payment gateways & PSPs](technologies/payment-gateways-and-psps.md) | Accepting cards and UPI without integrating every bank: authorisation, capture, settlement, webhooks, tokens |
| [gRPC & Protocol Buffers](technologies/grpc-and-protobuf.md) | Fast typed service-to-service calls over HTTP/2, with schema evolution rules |

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
| [Time-series compression & downsampling](concepts/time-series-compression-and-downsampling.md) | How do metrics take ~1.4 bytes per point, and stay queryable for a year? |
| [Alerting & SLOs](concepts/alerting-and-slos.md) | How do you page less and catch more? Error budgets and burn rates |
| [Operational transformation & CRDTs](concepts/operational-transformation-and-crdts.md) | How do concurrent edits to the same text converge? |
| [Real-time collaboration & presence](concepts/real-time-collaboration-and-presence.md) | Sessions per document, op logs, cursors, reconnects |
| [File sync & conflict resolution](concepts/file-sync-and-conflict-resolution.md) | How do devices stay in sync, and what counts as a conflict? |
| [Chunking & block-level dedup](concepts/chunking-and-block-level-dedup.md) | How do sync systems upload only changed blocks and store duplicates once? |
| [Payment reconciliation](concepts/payment-reconciliation.md) | Matching your ledger against PSP settlement files and bank statements every day, and working the breaks |
| [Log replication & ISR](concepts/log-replication-and-isr.md) | How a replicated log decides a message is committed: in-sync replicas, high watermark, acks, leader epochs |
| [Log segments, retention & compaction](concepts/log-segments-retention-and-compaction.md) | How a partition lives on disk: segment files, sparse index, deleting old data, keeping the latest value per key |
| [Consumer groups & rebalancing](concepts/consumer-groups-and-rebalancing.md) | Sharing partitions among consumers, offset commits, and keeping rebalances cheap |
| [Distributed scheduling & time buckets](concepts/distributed-scheduling-and-time-buckets.md) | Finding "due now" among millions of jobs: indexes, time buckets, SKIP LOCKED, leader per shard, jitter |
| [Workflow orchestration & DAGs](concepts/workflow-orchestration-and-dags.md) | Jobs with dependencies: topological order, per-task retries, backfills, Airflow vs Temporal |
| [Windowing, watermarks & late events](concepts/windowing-watermarks-and-late-events.md) | Counting events in time windows by when they happened, and deciding when a window is done |
| [Lambda vs Kappa architecture](concepts/lambda-vs-kappa-architecture.md) | Fast streaming numbers plus an exact batch recount, or one replayable stream |
| [RAG & vector search](concepts/rag-and-vector-search.md) | Giving an LLM your own documents: embeddings, chunking, ANN indexes (HNSW), hybrid search |
| [LLM evals, guardrails & prompt injection](concepts/llm-evals-guardrails-and-prompt-injection.md) | Testing non-deterministic features and keeping untrusted text from steering the model |
| [Gossip & failure detection](concepts/gossip-and-failure-detection.md) | How do hundreds of nodes know who's alive without a central list? |
| [Vector clocks & conflict resolution](concepts/vector-clocks-and-conflict-resolution.md) | What happens when two replicas accept different writes? |
| [Merkle trees & anti-entropy](concepts/merkle-trees-and-anti-entropy.md) | How do replicas find their differences cheaply? |
| [Hinted handoff & sloppy quorum](concepts/hinted-handoff-and-sloppy-quorum.md) | How do writes succeed when replicas are down, and what does it cost? |
| [Consensus & Raft](concepts/consensus-and-raft.md) | How do machines agree on one order of operations despite failures? |
