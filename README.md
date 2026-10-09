# System Design Interview Prep

Interview walkthroughs written as **interviewer ↔ candidate dialogues**, each answered at three levels (**L4 mid**, **L5 senior**, **L6 staff**), plus a separate reference library explaining every technology, library and concept they use: what problem it solves, when to use it, when **not** to, and what it gets confused with.

## Layout

```
HLD/                         High-level design (architecture)
├── interviews/<problem>/    00-understand-the-product.md + README + L4-mid.md + L5-senior.md + L6-staff.md
├── technologies/            Redis, Kafka, Postgres, Cassandra, CDN, ...
└── concepts/                ID generation, caching, sharding, CAP, ...
LLD/                         Low-level design (classes + code)
├── interviews/<problem>/    00-understand-the-product.md + README + L4/L5/L6 + runnable java/ and js/ code
├── libraries/java/          ConcurrentHashMap, atomics, locks, ...
├── libraries/js/            event loop, Map, middleware, ...
└── concepts/                design patterns, SOLID, thread safety
case-studies/                How real companies built it (sources cited, no code)
see-it-work/                 Small runnable pieces of HLD mechanisms
under-the-hood/              How clever inventions work (epoll, B-trees, HyperLogLog, ...)
```

## Roadmap

See **[ROADMAP.md](ROADMAP.md)** for the full learning path: what's done, what's next, and which new ideas each problem adds.

**Side tracks** (optional, understanding-first):
- 🗺️ **[Distributed systems learning path](DISTRIBUTED-SYSTEMS-PATH.md)**: the concept files in reading order, from "split the data" to consensus and repair.
- 📚 **Case studies** (`case-studies/`): how real companies built it, with sources. [WhatsApp vs Telegram](case-studies/whatsapp-vs-telegram.md) · [Discord: trillions of messages](case-studies/discord-message-storage.md) · [Uber: monolith to H3, microservices and M3](case-studies/uber-from-monolith-to-h3-and-microservices.md) · [Netflix: Open Connect and chaos engineering](case-studies/netflix-open-connect-and-chaos-engineering.md) · [Video platforms: upload, transcode, store, stream](case-studies/video-upload-transcode-and-storage.md) · [Amazon: Dynamo → DynamoDB](case-studies/amazon-dynamo-to-dynamodb.md).
- 🔍 **[Under the Hood](under-the-hood/README.md)**: curiosity-driven deep dives into one clever invention each. [epoll](under-the-hood/epoll.md) · [B-tree](under-the-hood/b-tree.md) · [HyperLogLog](under-the-hood/hyperloglog.md) · [Git's object store](under-the-hood/git-object-store.md) · [Postgres MVCC](under-the-hood/postgres-mvcc.md) · [rsync's rolling hash](under-the-hood/rsync-rolling-hash.md) · [Content-defined chunking](under-the-hood/content-defined-chunking.md) · [Why your video drops to 360p](under-the-hood/adaptive-bitrate-player.md) · [Erasure coding](under-the-hood/erasure-coding.md) · [Double-entry ledgers](under-the-hood/double-entry-ledgers.md) · [UPI](under-the-hood/upi.md) · [Kafka's speed tricks](under-the-hood/kafka-speed-tricks.md) · [Video compression](under-the-hood/video-compression.md) · [Upscaling & super-resolution](under-the-hood/upscaling-and-super-resolution.md) · [How a voice is stored](under-the-hood/how-sound-is-stored.md) · [Kubernetes scheduler](under-the-hood/kubernetes-scheduler.md) · [Containers: namespaces & cgroups](under-the-hood/containers-namespaces-cgroups.md) · [JVM garbage collectors](under-the-hood/jvm-garbage-collectors.md) · [TLS 1.3 handshake](under-the-hood/tls-1-3-handshake.md) · [Password hashing](under-the-hood/password-hashing.md) · [Shazam's audio fingerprints](under-the-hood/shazam-audio-fingerprinting.md) · [Anycast](under-the-hood/anycast.md) · [Signal's double ratchet](under-the-hood/signal-double-ratchet.md) · [Route planning](under-the-hood/route-planning.md) · [QR codes and Reed–Solomon](under-the-hood/qr-codes-reed-solomon.md) · [Maglev hashing](under-the-hood/maglev-hashing.md) · [TCP](under-the-hood/tcp.md) · [HTTP/1.1 → 2 → 3](under-the-hood/http-1-2-3.md) · [Kubernetes networking](under-the-hood/kubernetes-networking.md) · [NAT & conntrack](under-the-hood/nat-and-conntrack.md) · [What happens when you type a URL](under-the-hood/what-happens-when-you-type-a-url.md).
- 🔬 **See it work** (`see-it-work/`): small runnable simulations of distributed mechanisms. [Hash ring + quorums + hinted handoff](see-it-work/hash-ring-quorum/README.md) (`java HashRing.java`) · [Raft leader election](see-it-work/raft-leader-election/README.md) (`java RaftElection.java`) · [Chat sequencer + gap detection](see-it-work/chat-sequencer-sync/README.md) (`java ChatSync.java`) · [Saga with compensations](see-it-work/saga-compensations/README.md) (`java Saga.java`) · [Fan-out worker with retries + DLQ](see-it-work/fanout-worker-dlq/README.md) (`java FanoutWorker.java`).

## Interviews

| Type | Problem | Files |
|---|---|---|
| HLD | [URL Shortener](HLD/interviews/url-shortener/README.md) | [Product intro](HLD/interviews/url-shortener/00-understand-the-product.md) · [L4](HLD/interviews/url-shortener/L4-mid.md) · [L5](HLD/interviews/url-shortener/L5-senior.md) · [L6](HLD/interviews/url-shortener/L6-staff.md) |
| HLD | [Notification System](HLD/interviews/notification-system/README.md) | [Product intro](HLD/interviews/notification-system/00-understand-the-product.md) · [L4](HLD/interviews/notification-system/L4-mid.md) · [L5](HLD/interviews/notification-system/L5-senior.md) · [L6](HLD/interviews/notification-system/L6-staff.md) |
| HLD | [Chat System](HLD/interviews/chat-system/README.md) | [Product intro](HLD/interviews/chat-system/00-understand-the-product.md) · [L4](HLD/interviews/chat-system/L4-mid.md) · [L5](HLD/interviews/chat-system/L5-senior.md) · [L6](HLD/interviews/chat-system/L6-staff.md) |
| HLD | [News Feed](HLD/interviews/news-feed/README.md) | [Product intro](HLD/interviews/news-feed/00-understand-the-product.md) · [L4](HLD/interviews/news-feed/L4-mid.md) · [L5](HLD/interviews/news-feed/L5-senior.md) · [L6](HLD/interviews/news-feed/L6-staff.md) |
| HLD | [Ride-Sharing (Uber)](HLD/interviews/ride-sharing/README.md) | [Product intro](HLD/interviews/ride-sharing/00-understand-the-product.md) · [L4](HLD/interviews/ride-sharing/L4-mid.md) · [L5](HLD/interviews/ride-sharing/L5-senior.md) · [L6](HLD/interviews/ride-sharing/L6-staff.md) |
| HLD | [API Gateway](HLD/interviews/api-gateway/README.md) | [Product intro](HLD/interviews/api-gateway/00-understand-the-product.md) · [L4](HLD/interviews/api-gateway/L4-mid.md) · [L5](HLD/interviews/api-gateway/L5-senior.md) · [L6](HLD/interviews/api-gateway/L6-staff.md) |
| HLD | [Distributed KV Store](HLD/interviews/distributed-kv-store/README.md) | [Product intro](HLD/interviews/distributed-kv-store/00-understand-the-product.md) · [L4](HLD/interviews/distributed-kv-store/L4-mid.md) · [L5](HLD/interviews/distributed-kv-store/L5-senior.md) · [L6](HLD/interviews/distributed-kv-store/L6-staff.md) |
| HLD | [Web Crawler](HLD/interviews/web-crawler/README.md) | [Product intro](HLD/interviews/web-crawler/00-understand-the-product.md) · [L4](HLD/interviews/web-crawler/L4-mid.md) · [L5](HLD/interviews/web-crawler/L5-senior.md) · [L6](HLD/interviews/web-crawler/L6-staff.md) |
| HLD | [Search Autocomplete](HLD/interviews/search-autocomplete/README.md) | [Product intro](HLD/interviews/search-autocomplete/00-understand-the-product.md) · [L4](HLD/interviews/search-autocomplete/L4-mid.md) · [L5](HLD/interviews/search-autocomplete/L5-senior.md) · [L6](HLD/interviews/search-autocomplete/L6-staff.md) |
| HLD | [Video Streaming (YouTube / Netflix)](HLD/interviews/video-streaming/README.md) | [Product intro](HLD/interviews/video-streaming/00-understand-the-product.md) · [L4](HLD/interviews/video-streaming/L4-mid.md) · [L5](HLD/interviews/video-streaming/L5-senior.md) · [L6](HLD/interviews/video-streaming/L6-staff.md) |
| HLD | [Metrics & Monitoring (Prometheus-like)](HLD/interviews/metrics-monitoring/README.md) | [Product intro](HLD/interviews/metrics-monitoring/00-understand-the-product.md) · [L4](HLD/interviews/metrics-monitoring/L4-mid.md) · [L5](HLD/interviews/metrics-monitoring/L5-senior.md) · [L6](HLD/interviews/metrics-monitoring/L6-staff.md) |
| HLD | [Collaborative Editor (Google Docs)](HLD/interviews/collaborative-editor/README.md) | [Product intro](HLD/interviews/collaborative-editor/00-understand-the-product.md) · [L4](HLD/interviews/collaborative-editor/L4-mid.md) · [L5](HLD/interviews/collaborative-editor/L5-senior.md) · [L6](HLD/interviews/collaborative-editor/L6-staff.md) |
| HLD | [File Storage & Sync (Dropbox / Drive)](HLD/interviews/file-storage-sync/README.md) | [Product intro](HLD/interviews/file-storage-sync/00-understand-the-product.md) · [L4](HLD/interviews/file-storage-sync/L4-mid.md) · [L5](HLD/interviews/file-storage-sync/L5-senior.md) · [L6](HLD/interviews/file-storage-sync/L6-staff.md) |
| HLD | [Payment System (marketplace checkout)](HLD/interviews/payment-system/README.md) | [Product intro](HLD/interviews/payment-system/00-understand-the-product.md) · [L4](HLD/interviews/payment-system/L4-mid.md) · [L5](HLD/interviews/payment-system/L5-senior.md) · [L6](HLD/interviews/payment-system/L6-staff.md) |
| HLD | [Distributed Message Queue (Kafka internals)](HLD/interviews/distributed-message-queue/README.md) | [Product intro](HLD/interviews/distributed-message-queue/00-understand-the-product.md) · [L4](HLD/interviews/distributed-message-queue/L4-mid.md) · [L5](HLD/interviews/distributed-message-queue/L5-senior.md) · [L6](HLD/interviews/distributed-message-queue/L6-staff.md) |
| HLD | [Distributed Job Scheduler](HLD/interviews/distributed-job-scheduler/README.md) | [Product intro](HLD/interviews/distributed-job-scheduler/00-understand-the-product.md) · [L4](HLD/interviews/distributed-job-scheduler/L4-mid.md) · [L5](HLD/interviews/distributed-job-scheduler/L5-senior.md) · [L6](HLD/interviews/distributed-job-scheduler/L6-staff.md) |
| HLD | [Ad Click Aggregation (real-time analytics)](HLD/interviews/ad-click-aggregation/README.md) | [Product intro](HLD/interviews/ad-click-aggregation/00-understand-the-product.md) · [L4](HLD/interviews/ad-click-aggregation/L4-mid.md) · [L5](HLD/interviews/ad-click-aggregation/L5-senior.md) · [L6](HLD/interviews/ad-click-aggregation/L6-staff.md) |
| LLD | [Rate Limiter](LLD/interviews/rate-limiter/README.md) | [Product intro](LLD/interviews/rate-limiter/00-understand-the-product.md) · [L4](LLD/interviews/rate-limiter/L4-mid.md) · [L5](LLD/interviews/rate-limiter/L5-senior.md) · [L6](LLD/interviews/rate-limiter/L6-staff.md) |
| LLD | [Parking Lot](LLD/interviews/parking-lot/README.md) | [Product intro](LLD/interviews/parking-lot/00-understand-the-product.md) · [L4](LLD/interviews/parking-lot/L4-mid.md) · [L5](LLD/interviews/parking-lot/L5-senior.md) · [L6](LLD/interviews/parking-lot/L6-staff.md) |
| LLD | [LRU Cache](LLD/interviews/lru-cache/README.md) | [Product intro](LLD/interviews/lru-cache/00-understand-the-product.md) · [L4](LLD/interviews/lru-cache/L4-mid.md) · [L5](LLD/interviews/lru-cache/L5-senior.md) · [L6](LLD/interviews/lru-cache/L6-staff.md) |
| LLD | [Elevator System](LLD/interviews/elevator-system/README.md) | [Product intro](LLD/interviews/elevator-system/00-understand-the-product.md) · [L4](LLD/interviews/elevator-system/L4-mid.md) · [L5](LLD/interviews/elevator-system/L5-senior.md) · [L6](LLD/interviews/elevator-system/L6-staff.md) |
| LLD | [Splitwise](LLD/interviews/splitwise/README.md) | [Product intro](LLD/interviews/splitwise/00-understand-the-product.md) · [L4](LLD/interviews/splitwise/L4-mid.md) · [L5](LLD/interviews/splitwise/L5-senior.md) · [L6](LLD/interviews/splitwise/L6-staff.md) |
| LLD | [Movie Ticket Booking (BookMyShow)](LLD/interviews/movie-booking/README.md) | [Product intro](LLD/interviews/movie-booking/00-understand-the-product.md) · [L4](LLD/interviews/movie-booking/L4-mid.md) · [L5](LLD/interviews/movie-booking/L5-senior.md) · [L6](LLD/interviews/movie-booking/L6-staff.md) |
| LLD | [KV Store with Transactions (mini Redis)](LLD/interviews/kv-store/README.md) | [Product intro](LLD/interviews/kv-store/00-understand-the-product.md) · [L4](LLD/interviews/kv-store/L4-mid.md) · [L5](LLD/interviews/kv-store/L5-senior.md) · [L6](LLD/interviews/kv-store/L6-staff.md) |
| LLD | [Task Scheduler (cron-like)](LLD/interviews/task-scheduler/README.md) | [Product intro](LLD/interviews/task-scheduler/00-understand-the-product.md) · [L4](LLD/interviews/task-scheduler/L4-mid.md) · [L5](LLD/interviews/task-scheduler/L5-senior.md) · [L6](LLD/interviews/task-scheduler/L6-staff.md) |
| LLD | [Logging Framework](LLD/interviews/logging-framework/README.md) | [Product intro](LLD/interviews/logging-framework/00-understand-the-product.md) · [L4](LLD/interviews/logging-framework/L4-mid.md) · [L5](LLD/interviews/logging-framework/L5-senior.md) · [L6](LLD/interviews/logging-framework/L6-staff.md) |
| LLD | [Vending Machine](LLD/interviews/vending-machine/README.md) | [Product intro](LLD/interviews/vending-machine/00-understand-the-product.md) · [L4](LLD/interviews/vending-machine/L4-mid.md) · [L5](LLD/interviews/vending-machine/L5-senior.md) · [L6](LLD/interviews/vending-machine/L6-staff.md) |
| LLD | [Thread Pool / Connection Pool](LLD/interviews/thread-pool/README.md) | [Product intro](LLD/interviews/thread-pool/00-understand-the-product.md) · [L4](LLD/interviews/thread-pool/L4-mid.md) · [L5](LLD/interviews/thread-pool/L5-senior.md) · [L6](LLD/interviews/thread-pool/L6-staff.md) |
| LLD | [Text Editor with Undo/Redo](LLD/interviews/text-editor/README.md) | [Product intro](LLD/interviews/text-editor/00-understand-the-product.md) · [L4](LLD/interviews/text-editor/L4-mid.md) · [L5](LLD/interviews/text-editor/L5-senior.md) · [L6](LLD/interviews/text-editor/L6-staff.md) |
| LLD | [In-memory File System](LLD/interviews/file-system/README.md) | [Product intro](LLD/interviews/file-system/00-understand-the-product.md) · [L4](LLD/interviews/file-system/L4-mid.md) · [L5](LLD/interviews/file-system/L5-senior.md) · [L6](LLD/interviews/file-system/L6-staff.md) |
| LLD | [ATM / Digital Wallet](LLD/interviews/digital-wallet/README.md) | [Product intro](LLD/interviews/digital-wallet/00-understand-the-product.md) · [L4](LLD/interviews/digital-wallet/L4-mid.md) · [L5](LLD/interviews/digital-wallet/L5-senior.md) · [L6](LLD/interviews/digital-wallet/L6-staff.md) |
| LLD | [Pub-Sub Broker (in-memory)](LLD/interviews/pub-sub-broker/README.md) | [Product intro](LLD/interviews/pub-sub-broker/00-understand-the-product.md) · [L4](LLD/interviews/pub-sub-broker/L4-mid.md) · [L5](LLD/interviews/pub-sub-broker/L5-senior.md) · [L6](LLD/interviews/pub-sub-broker/L6-staff.md) |
| LLD | [Chess](LLD/interviews/chess/README.md) | [Product intro](LLD/interviews/chess/00-understand-the-product.md) · [L4](LLD/interviews/chess/L4-mid.md) · [L5](LLD/interviews/chess/L5-senior.md) · [L6](LLD/interviews/chess/L6-staff.md) |
| LLD | [Meeting-Room / Hotel Booking](LLD/interviews/meeting-room-booking/README.md) | [Product intro](LLD/interviews/meeting-room-booking/00-understand-the-product.md) · [L4](LLD/interviews/meeting-room-booking/L4-mid.md) · [L5](LLD/interviews/meeting-room-booking/L5-senior.md) · [L6](LLD/interviews/meeting-room-booking/L6-staff.md) |

Indexes: [HLD](HLD/README.md) · [LLD](LLD/README.md)

## How to study a problem

1. Read the problem's `00-understand-the-product.md`. It explains the product as a user would experience it, so you know *what* you're designing and *why* each feature exists.
2. Read the problem's `README.md` (the level comparison table tells you what changes between levels).
3. Cover the candidate's answers and try to answer each interviewer question yourself first.
4. When a term is unfamiliar, follow its link into `technologies/`, `libraries/` or `concepts/`. Each reference page ends with an "interview cheat-sheet" you can say out loud.
5. For LLD, run the code and break it (e.g. remove `synchronized` and watch the concurrency test fail).

## Running the LLD code

```sh
LLD/interviews/rate-limiter/java/run.sh                 # Java 21+, tests + demo
cd LLD/interviews/rate-limiter/js && node --test        # Node 22+, tests
LLD/interviews/parking-lot/java/run.sh                  # Java 21+, tests + demo
cd LLD/interviews/parking-lot/js && node --test         # Node 22+, tests
LLD/interviews/lru-cache/java/run.sh                    # Java 21+, tests + demo
cd LLD/interviews/lru-cache/js && node --test           # Node 22+, tests
LLD/interviews/elevator-system/java/run.sh              # Java 21+, tests + demo
cd LLD/interviews/elevator-system/js && node --test     # Node 22+, tests
LLD/interviews/splitwise/java/run.sh                    # Java 21+, tests + demo
cd LLD/interviews/splitwise/js && node --test           # Node 22+, tests
LLD/interviews/movie-booking/java/run.sh                # Java 21+, tests + demo
cd LLD/interviews/movie-booking/js && node --test       # Node 22+, tests
LLD/interviews/kv-store/java/run.sh                     # Java 21+, tests + demo
cd LLD/interviews/kv-store/js && node --test            # Node 22+, tests
LLD/interviews/task-scheduler/java/run.sh               # Java 21+, tests + demo
cd LLD/interviews/task-scheduler/js && node --test      # Node 22+, tests
LLD/interviews/logging-framework/java/run.sh            # Java 21+, tests + demo
cd LLD/interviews/logging-framework/js && node --test   # Node 22+, tests
LLD/interviews/vending-machine/java/run.sh              # Java 21+, tests + demo
cd LLD/interviews/vending-machine/js && node --test     # Node 22+, tests
LLD/interviews/thread-pool/java/run.sh                  # Java 21+, tests + demo
cd LLD/interviews/thread-pool/js && node --test         # Node 22+, tests
LLD/interviews/text-editor/java/run.sh                  # Java 21+, tests + demo
cd LLD/interviews/text-editor/js && node --test         # Node 22+, tests
LLD/interviews/file-system/java/run.sh                  # Java 21+, tests + demo
cd LLD/interviews/file-system/js && node --test         # Node 22+, tests
LLD/interviews/digital-wallet/java/run.sh               # Java 21+, tests + demo
cd LLD/interviews/digital-wallet/js && node --test      # Node 22+, tests
LLD/interviews/pub-sub-broker/java/run.sh               # Java 21+, tests + demo
cd LLD/interviews/pub-sub-broker/js && node --test      # Node 22+, tests
LLD/interviews/chess/java/run.sh                        # Java 21+, tests + demo
cd LLD/interviews/chess/js && node --test               # Node 22+, tests
LLD/interviews/meeting-room-booking/java/run.sh         # Java 21+, tests + demo
cd LLD/interviews/meeting-room-booking/js && node --test # Node 22+, tests
```
