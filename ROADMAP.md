# Roadmap: What We Cover, in What Order, and Why

The goal isn't to memorise answers to famous questions. It's to learn the **ideas** that keep showing up: storage, replication, caching, queues, consistency, concurrency, modelling, failure handling. A new question you've never seen should then feel like a recombination of things you already understand.

Each step is an **HLD + LLD pair**. The pairs are ordered so each one adds new ideas on top of the previous ones, and HLD/LLD halves often reinforce each other (e.g. a distributed key-value store and an in-memory key-value store with transactions).

---

## ✅ Done

| # | HLD | What it taught | LLD | What it taught |
|---|---|---|---|---|
| 1 | [URL Shortener](HLD/interviews/url-shortener/README.md) | Read-heavy design, caching, ID generation, KV storage | [Rate Limiter](LLD/interviews/rate-limiter/README.md) | Algorithms, thread safety, testable time, distributed atomicity |
| 2 | [Notification System](HLD/interviews/notification-system/README.md) | Queues, retries, idempotency, priorities, third parties | [Parking Lot](LLD/interviews/parking-lot/README.md) | OOP modelling, Strategy/Observer, money, atomic claiming |
| 3 | [Chat System](HLD/interviews/chat-system/README.md) | Stateful connections, ordering, offline sync, E2EE | [LRU Cache](LLD/interviews/lru-cache/README.md) | Data structures, O(1), lock striping, stampedes |
| 4 | [News Feed](HLD/interviews/news-feed/README.md) | Fan-out, precomputation, ranking, counters | [Elevator System](LLD/interviews/elevator-system/README.md) | Scheduling algorithms, state machines, single-writer concurrency |
| 5 | [Ride-Sharing](HLD/interviews/ride-sharing/README.md) | Geospatial indexing, leases, sagas, stream processing | [Splitwise](LLD/interviews/splitwise/README.md) | Exact money, sealed types, ledgers, greedy algorithms |
| 6 | [API Gateway](HLD/interviews/api-gateway/README.md) | Request path, auth, resilience, control/data plane | [Movie Booking](LLD/interviews/movie-booking/README.md) | Holds with TTL, optimistic vs pessimistic locking, deadlocks |
| 7 | [Distributed KV Store](HLD/interviews/distributed-kv-store/README.md) | Quorums, sloppy quorum, vector clocks, Merkle repair, gossip, LSM trees, Raft | [KV Store with transactions](LLD/interviews/kv-store/README.md) | Undo-log transactions, TTL, write-ahead log, crash recovery |

## 🔜 Next (in order)

| # | HLD | New ideas it brings | LLD | New ideas it brings |
|---|---|---|---|---|
| 8 | **Web Crawler** | Massive BFS, politeness, URL frontier, dedup (Bloom, fingerprints), robots.txt | **Task Scheduler** (cron-like) | Delay queues, priority queues, worker pools, retries, time handling |
| 9 | **Search Autocomplete / Typeahead** | Tries, inverted indexes, top-k, Elasticsearch, offline vs online pipelines | **Logging Framework** | Chain of Responsibility, async appenders, back-pressure, levels |
| 10 | **Video Streaming** (YouTube / Netflix) | Upload pipelines, transcoding DAGs, adaptive bitrate, CDN economics | **Vending Machine** | State pattern done properly, inventory, change-making |
| 11 | **Metrics & Monitoring System** (Prometheus-like) | Time-series storage, compression, downsampling, alerting at scale | **Thread Pool / Connection Pool** | Building concurrency primitives yourself: queues, workers, shutdown, leaks |
| 12 | **Collaborative Editor** (Google Docs) | Operational transforms vs CRDTs, real-time sync, presence | **Text Editor with Undo/Redo** | Command + Memento, gap buffers / ropes |
| 13 | **File Storage & Sync** (Dropbox / Drive) | Chunking, content-addressed dedup, sync conflicts, metadata vs blobs | **File System** (in-memory) | Composite pattern, path resolution, permissions |
| 14 | **Payment System** | Double-entry ledgers at scale, PSP integration, reconciliation, exactly-once money | **ATM / Digital Wallet** | State machines with money, idempotent transfers, auditing |
| 15 | **Distributed Message Queue** (Kafka internals) | Log segments, partitions, replication (ISR), consumer groups, retention | **Pub-Sub Broker** (in-memory) | Observer at scale, back-pressure, delivery guarantees in code |
| 16 | **Distributed Job Scheduler** | Leader election, exactly-once execution, sharding schedules, time zones | **Chess** | Rich OOP modelling, rules engines, move validation, undo |
| 17 | **Ad Click Aggregation / Real-time Analytics** | Lambda vs Kappa, windowed aggregation, dedup at scale, reconciliation | **Hotel / Meeting-Room Booking** | Interval overlap, availability search, overbooking rules |

After these 17 pairs, every major building block (storage engines, replication, consensus, caching, queues, streams, search, CDNs, consistency models, concurrency primitives, core design patterns) will have been used in at least one full interview.

## 🧹 Planned clean-ups

- **Jargon sweep** of the early files (rule added after they were written).
- **Toy playgrounds** per problem (deferred idea; see `CLAUDE.md`).
- **"How to approach any design interview"** guide: a reusable 45-minute framework distilled from all the interviews.

---

*This roadmap is the plan of record: future sessions continue from the first unfinished row unless the reader asks for something else.*
