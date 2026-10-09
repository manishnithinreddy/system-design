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
| 9 | [Search Autocomplete](HLD/interviews/search-autocomplete/README.md) | Offline build + in-memory serving, tries with top-k, trending streams, Count-Min Sketch, suggestion safety | [Logging Framework](LLD/interviews/logging-framework/README.md) | Logger hierarchy, Strategy/Chain of Responsibility, async appender + back-pressure, MDC |
| 10 | [Video Streaming](HLD/interviews/video-streaming/README.md) | Resumable uploads, chunked transcoding DAG, ABR segments, CDN + tiering, live, cost model | [Vending Machine](LLD/interviews/vending-machine/README.md) | State pattern, paise, bounded change-making DP, refunds, idempotent UPI |
| 11 | [Metrics & Monitoring](HLD/interviews/metrics-monitoring/README.md) | Time-series storage, compression, cardinality, downsampling, SLO alerting | [Thread Pool / Connection Pool](LLD/interviews/thread-pool/README.md) | Pools from scratch, rejection policies, sizing, fair borrowing, leak detection |
| 12 | [Collaborative Editor](HLD/interviews/collaborative-editor/README.md) | OT vs CRDTs, one sequencer per doc, op log + snapshots, presence | [Text Editor](LLD/interviews/text-editor/README.md) | Command undo/redo, coalescing, gap buffer / piece table / rope |
| 13 | [File Storage & Sync](HLD/interviews/file-storage-sync/README.md) | Metadata vs blocks, dedup, journal + cursor, conflicts, erasure coding | [In-memory File System](LLD/interviews/file-system/README.md) | Composite, path resolution, symlinks, permissions, locking |
| 14 | [Payment System](HLD/interviews/payment-system/README.md) | State machine, idempotency end to end, unknown outcomes, double-entry ledger, outbox + saga, reconciliation, payouts, multi-PSP routing, fraud | [ATM / Digital Wallet](LLD/interviews/digital-wallet/README.md) | ATM states, note planning, reversals, idempotent transfers, lock ordering, holds |

## 🔜 Next (in order)

| # | HLD | New ideas it brings | LLD | New ideas it brings |
|---|---|---|---|---|
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
| C2 | ✅ [**Discord: storing trillions of messages**](case-studies/discord-message-storage.md) | Real Cassandra → ScyllaDB migration, hot partitions, request coalescing in practice | Done |
| C3 | ✅ [**Uber: from monolith to H3 and microservices**](case-studies/uber-from-monolith-to-h3-and-microservices.md) | Geospatial indexing at scale, service sprawl and how they tamed it, M3 metrics | Done |
| C4 | ✅ [**Netflix: Open Connect and chaos engineering**](case-studies/netflix-open-connect-and-chaos-engineering.md) | Running your own CDN inside ISPs; designing for failure on purpose | Done |
| C5 | **Amazon: from the Dynamo paper to DynamoDB** | How a research design became a managed service, and what changed | After #15 |
| C6 | ✅ [**Video platforms: upload, transcode, store every quality, stream**](case-studies/video-upload-transcode-and-storage.md) (YouTube, Netflix, others) | Resumable chunked uploads, transcoding into a bitrate ladder, where each rendition lives (object storage, CDN, ISP caches), adaptive streaming | Done (reader request) |

### 🔬 See it work: small runnable pieces of HLD systems
The most instructive *distributed* mechanism from an HLD interview, as a small simulation you can run and break (typically 50–150 lines), wrapped in plain-language explanation. This replaces the earlier "toy playground" idea.

| # | Piece | From | Slot |
|---|---|---|---|
| S1 | ✅ [**Hash ring + quorum reads/writes + hinted handoff**](see-it-work/hash-ring-quorum/README.md), with nodes you can "kill" | Distributed KV store | Done |
| S2 | ✅ [**Raft leader election**](see-it-work/raft-leader-election/README.md): terms, votes, timeouts, a partitioned leader | Distributed KV store / etcd | Done |
| S3 | ✅ [**Chat sequencer + gap detection + offline sync**](see-it-work/chat-sequencer-sync/README.md) | Chat system | Done |
| S4 | ✅ [**Saga with compensations**](see-it-work/saga-compensations/README.md): reserve → charge → ship, retries, compensations, crash recovery | Payments / ride-sharing | Done |
| S5 | **Fan-out worker with retries, backoff and a DLQ** | Notification system | After #16 |

### 🔍 Under the Hood: how clever things actually work
Curiosity-driven deep dives into one specific invention: the problem before it, the clever idea, how it works step by step, where you've used it without knowing. 1–2 per core pair, picked to match it.

| # | Topic | Hook | Slot |
|---|---|---|---|
| U1 | ✅ [**epoll**](under-the-hood/epoll.md) | How does one thread handle 100k connections? | Done |
| U2 | ✅ [**B-tree**](under-the-hood/b-tree.md) | How does a database find one row among a billion in ~3 disk reads? | Done |
| U3 | ✅ [**HyperLogLog**](under-the-hood/hyperloglog.md) | How do you count a billion unique users in 12 KB? | Done |
| U4 | ✅ [**Git's object store**](under-the-hood/git-object-store.md) | How does Git keep your whole history so cheaply? | Done |
| U5 | ✅ [**Postgres MVCC**](under-the-hood/postgres-mvcc.md) | How do readers and writers not block each other? | Done |
| U6 | ✅ [**rsync's rolling hash**](under-the-hood/rsync-rolling-hash.md) | How do you send only the bytes that changed? | Done |
| U7 | ✅ [**Content-defined chunking**](under-the-hood/content-defined-chunking.md) | How do Dropbox-style systems dedupe files that shift? | Done |
| U8 | ✅ [**Double-entry ledgers in databases**](under-the-hood/double-entry-ledgers.md) | How do payment systems make money impossible to lose? | Done |
| U9 | **Kafka's speed tricks** | Sequential I/O, page cache, zero-copy: how millions of messages/s fit on one disk | With #15 |
| U10 | **Kubernetes scheduler** | How does Kubernetes pick a node for your pod? | With #16 |
| U11 | **TLS 1.3 / QUIC** | How is an encrypted connection set up in one round trip? | Later |
| U12 | **Containers: namespaces & cgroups** | What is a container, really? | Later |
| U13 | **JVM garbage collectors** | How does ZGC pause for under a millisecond? | Later |
| U14 | **Video compression** | I/P/B frames and motion vectors | Later |
| U15 | **Shazam's audio fingerprints** | How is a song recognised in 3 seconds? | Later |
| U16 | **Route planning** | How does a maps app find a route across a country in milliseconds? | Later |
| U17 | ✅ [**Adaptive bitrate in the player**](under-the-hood/adaptive-bitrate-player.md) | Why does your video drop to 360p, and how does it decide to climb back? | Done |
| U18 | ✅ [**Erasure coding**](under-the-hood/erasure-coding.md) | How do storage systems survive lost disks with 1.5× overhead instead of 3 copies? | Done |
| U19 | ✅ [**UPI under the hood**](under-the-hood/upi.md) | What happens between "Pay" and "₹ debited" in about 2 seconds? | Done |
| U20 | **Anycast** | How does one IP address (1.1.1.1, 8.8.8.8) live in hundreds of cities? | Later |
| U21 | **Password hashing (bcrypt / Argon2)** | How do you make cracking stolen passwords deliberately slow? | Later |
| U22 | **QR codes and Reed–Solomon** | How does a QR code still scan with a corner torn off? | Later |
| U23 | **Maglev hashing** | How do load balancers spread connections evenly and survive a server dying? | Later |
| U24 | **Signal's double ratchet** | How does stealing today's key not reveal yesterday's messages? | Later |

## 🗺️ Learning path pages

- **[Distributed systems learning path](DISTRIBUTED-SYSTEMS-PATH.md)**: the concept files in the order to read them, with why each comes next.

## 🧹 Planned clean-ups

- **Jargon sweep** of the early files (rule added after they were written).
- **"How to approach any design interview"** guide: a reusable 45-minute framework distilled from all the interviews.

---

*This roadmap is the plan of record: future sessions continue from the first unfinished core row, doing any side-track item whose slot has come up, unless the reader asks for something else.*
