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
| 8 | [Web Crawler](HLD/interviews/web-crawler/README.md) | Politeness (per-host queues + heap), URL/content dedup (Bloom, SimHash), traps, recrawl, crawl budget | [Task Scheduler](LLD/interviews/task-scheduler/README.md) | Heap + dispatcher wake-up, fixed-rate/delay, cron + DST, retries + dead letters, misfires |

## 🔜 Next (in order)

| # | HLD | New ideas it brings | LLD | New ideas it brings |
|---|---|---|---|---|
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

## 🧭 Side tracks (optional, bounded)

The core track teaches interview-shaped designs. Two small side tracks add what interviews leave out. Both are about **understanding first**: explanations and diagrams, with code only where running something makes an idea click.

### 📚 Case studies: how real companies actually built it
How a real system works, from public engineering blogs, docs and talks, with sources cited and anything unverified clearly marked. No code.

| # | Case study | Why it's worth it | Slot |
|---|---|---|---|
| C1 | ✅ [**WhatsApp vs Telegram**](case-studies/whatsapp-vs-telegram.md) | Same product, opposite choices: E2EE + delete-after-delivery vs cloud storage + multi-DC; large media; multi-device | Done |
| C2 | **Discord: storing trillions of messages** | Real Cassandra → ScyllaDB migration, hot partitions, request coalescing in practice | After #9 |
| C3 | **Uber: from monolith to H3 and microservices** | Geospatial indexing at scale, service sprawl and how they tamed it | After #11 |
| C4 | **Netflix: Open Connect and chaos engineering** | Running your own CDN inside ISPs; designing for failure on purpose | After #13 |
| C5 | **Amazon: from the Dynamo paper to DynamoDB** | How a research design became a managed service, and what changed | After #15 |

### 🔬 See it work: small runnable pieces of HLD systems
The most instructive *distributed* mechanism from an HLD interview, as a small simulation you can run and break (typically 50–150 lines), wrapped in plain-language explanation. This replaces the earlier "toy playground" idea.

| # | Piece | From | Slot |
|---|---|---|---|
| S1 | ✅ [**Hash ring + quorum reads/writes + hinted handoff**](see-it-work/hash-ring-quorum/README.md), with nodes you can "kill" | Distributed KV store | Done |
| S2 | **Raft leader election**: terms, votes, timeouts, a partitioned leader | Distributed KV store / etcd | After #10 |
| S3 | **Chat sequencer + gap detection + offline sync** | Chat system | After #12 |
| S4 | **Saga with compensations**: book → pay → fail → undo | Ride-sharing / payments | After #14 |
| S5 | **Fan-out worker with retries, backoff and a DLQ** | Notification system | After #16 |

## 🗺️ Learning path pages

- **[Distributed systems learning path](DISTRIBUTED-SYSTEMS-PATH.md)**: the concept files in the order to read them, with why each comes next.

## 🧹 Planned clean-ups

- **Jargon sweep** of the early files (rule added after they were written).
- **"How to approach any design interview"** guide: a reusable 45-minute framework distilled from all the interviews.

---

*This roadmap is the plan of record: future sessions continue from the first unfinished core row, doing any side-track item whose slot has come up, unless the reader asks for something else.*
