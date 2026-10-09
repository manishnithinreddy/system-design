# Redis

## 1. One-line summary

Redis is an **in-memory key-value store** with rich data structures (strings, hashes, lists, sets, sorted sets...). It answers in well under a millisecond, so we mostly use it as a **cache**, a **counter**, or a **shared fast scratchpad** between stateless app servers.

💡 **In-memory / key-value / cache:** data is kept in RAM (which is roughly 1000x faster than SSD) and looked up by a key, like a giant shared `ConcurrentHashMap`. A cache is a fast copy of data that lives somewhere slower, so repeated reads skip the slow place.

---

## 2. The problem it solves

**The pain:** your app reads the same rows from Postgres again and again. Every read is a network hop + a query planner + maybe a disk read: typically **1–10 ms**, and the DB has a ceiling of maybe **10k–50k simple queries/sec** per node before CPU or I/O becomes the bottleneck. When traffic grows, the DB melts even though 90% of the queries ask for the same few thousand rows.

💡 **Query planner / network hop / I/O:** the planner is the part of the database that decides how to run a query (which index, which join order). A network hop is one trip between machines (~0.5 ms in a data center). I/O is reading or writing disk or network.

A second pain: you run 20 stateless app pods behind a load balancer and they need to **share small pieces of state** — a rate-limit counter, a session, a "next ID" counter. A `HashMap` in each JVM does not work, because each pod has its own copy.

**The fix:** put the hot data in RAM on a separate server that every pod talks to.

| | Postgres (indexed lookup) | Redis `GET` |
|---|---|---|
| Latency | ~1–10 ms | ~0.1–0.5 ms (mostly network) |
| Throughput per node | ~10k–50k simple queries/s | **~100k+ ops/s** (single-threaded), more with pipelining |
| Where data lives | Disk (with page cache) | RAM |

So a cache hit is roughly **10x faster** and one Redis node can absorb load that would need several DB replicas.

💡 **Cache hit/miss, pipelining, ops/s:** a hit = the value was in the cache; a miss = it was not, so you go to the DB. Pipelining = sending many commands without waiting for each reply, which saves round trips. ops/s = operations per second.

---

## 3. How it works

### 3.1 Single-threaded event loop

Redis executes commands on **one main thread**. That sounds slow, but every command is an in-memory operation taking microseconds, so one core can do ~100k ops/sec. The big win: **every single command is atomic** — no two commands interleave. That is why `INCR` is safe without locks.

💡 **Atomic / event loop / epoll:** atomic = cannot be interrupted halfway; no other command sees a half-done state. An event loop is one thread that waits for any of thousands of sockets to become ready and handles them in turn; epoll is the Linux call that tells it which are ready.

> Infra analogy: like nginx, it uses an event loop (epoll) to handle thousands of connections on one thread instead of thread-per-connection like classic Tomcat.

### 3.2 Data structures (the real reason to pick Redis)

| Type | What it is | Typical use | Example |
|---|---|---|---|
| **String** | bytes up to 512 MB (a number counts too) | cache a value, counters | `SET url:abc123 "https://..." EX 86400` |
| **Hash** | map of field → value under one key | an object (user profile) | `HSET user:42 name "Asha" plan "pro"` |
| **List** | linked list, push/pop at both ends | simple queue, recent-items list | `LPUSH recent:42 item9` |
| **Set** | unordered unique members | tags, "who liked this" | `SADD liked:post7 user42` |
| **Sorted set (ZSET)** | members ordered by a score | leaderboards, top-K, time-ordered feeds | `ZINCRBY clicks:today 1 abc123` |
| **HyperLogLog** | approximate distinct count in 12 KB | unique visitors | `PFADD uv:abc123 ip1` |
| **Stream** | append-only log with consumer groups | lightweight Kafka-like queue | `XADD events * type click` |

Rule of thumb: if you find yourself serializing a whole object to JSON, editing one field and writing it back, you probably wanted a **hash**. If you want "top 10 by X", you want a **sorted set**.

💡 **Type notes:** a *linked list* has cheap push/pop at the ends. A *sorted set* keeps members ordered by a number (score) so "top 10" is cheap. **HyperLogLog** gives a count of distinct items with ~1% error using a fixed tiny amount of memory. A *Stream* with *consumer groups* is Redis's version of Kafka's log. **Top-K** = the K largest/most frequent items. **Serializing** = converting an object to bytes/JSON.

### 3.3 TTL (time to live)

Any key can expire: `SET k v EX 3600` or `EXPIRE k 3600`. Redis deletes expired keys lazily (when you touch them) plus a background sampler. TTL is your safety net: even if your invalidation logic has a bug, stale data dies on its own.

💡 **Lazy deletion / invalidation:** "lazily" = only when the key is next accessed. Invalidation = removing or updating cached data when the source changes.

### 3.4 Eviction: what happens when RAM is full

You set `maxmemory 8gb` and a `maxmemory-policy`:

| Policy | Behaviour | When |
|---|---|---|
| `noeviction` (default) | writes **fail** with OOM error | Redis used as a store you must not lose from |
| `allkeys-lru` | evict least-recently-used key, any key | **pure cache — the usual choice** |
| `allkeys-lfu` | evict least-frequently-used | cache with a stable hot set (e.g. popular short URLs) |
| `volatile-lru` / `volatile-lfu` | evict only keys that have a TTL | mixed: cache keys + keys you must keep |
| `volatile-ttl` | evict keys closest to expiring | rare |
| `allkeys-random` | random | rare |

> Interview tip: when you say "Redis cache", say "with `allkeys-lru` (or LFU) and a TTL". It shows you know the cache can't grow forever.

💡 **LRU / LFU / OOM:** LRU = least recently used (evict what has not been touched for longest); LFU = least frequently used (evict what is touched least often). OOM = out of memory.

### 3.5 Persistence: RDB and AOF

Redis is in-memory, but it *can* write to disk:

- **RDB (snapshot)**: every N minutes, fork and dump the whole dataset to a file. Cheap, compact, but you **lose everything since the last snapshot** on a crash (could be minutes).
- **AOF (append-only file)**: log every write command. With `appendfsync everysec` you lose **up to ~1 second** of writes. `always` fsyncs every write — much slower and still not the same guarantees as a real DB.

💡 **fork / fsync:** fork makes a cheap copy-on-write clone of the process so it can write the snapshot without pausing Redis. fsync forces buffered data onto the physical disk; `everysec` does it once a second.

**Why Redis is not a primary database by default:**
1. Default persistence loses data on crash (seconds to minutes).
2. Replication is **asynchronous** — a primary can ack a write, then die before the replica gets it; failover promotes a replica that never saw the write.
3. Dataset must fit in RAM. RAM costs ~10x+ more per GB than SSD.
4. No secondary indexes, no ad-hoc queries, no joins, no schema.

💡 **Ack / failover / promote:** "ack" = the server told the client the write succeeded. Failover = switching to a replica when the primary dies; promote = making that replica the new primary.

It is fine to *choose* Redis as a store for data you can afford to lose or rebuild (sessions, rate-limit counters, leaderboards rebuilt from a DB). Just say so explicitly.

### 3.6 Replication, Sentinel, Cluster

```mermaid
flowchart LR
    subgraph OA["Option A: Primary + replicas + Sentinel"]
        S1[Sentinel] -.monitors.-> P[(Primary)]
        S2[Sentinel] -.monitors.-> P
        S3[Sentinel] -.monitors.-> P
        P -- async replication --> R1[(Replica 1)]
        P -- async replication --> R2[(Replica 2)]
    end
    subgraph OB["Option B: Redis Cluster, 16384 hash slots"]
        C1[(Shard A primary<br/>slots 0-5460)] --> C1r[(replica)]
        C2[(Shard B primary<br/>slots 5461-10922)] --> C2r[(replica)]
        C3[(Shard C primary<br/>slots 10923-16383)] --> C3r[(replica)]
    end
    App[App servers] --> P
    App --> C1 & C2 & C3
```

- **Replication**: one primary takes writes; replicas copy asynchronously and can serve reads (possibly slightly stale).
- **Sentinel**: a small group of watcher processes (run 3 for a majority vote). If the primary dies, they agree on it and promote a replica, and tell clients the new address. This is **high availability**, not more capacity: you still have one primary's worth of RAM and write throughput.
- **Redis Cluster**: **sharding** built in. The key space is split into **16,384 hash slots**; `slot = CRC16(key) mod 16384`; each primary owns a range of slots. Clients learn the slot map and go straight to the right node. Add a node → move some slots to it. Multi-key operations only work if all keys are in the same slot — force that with **hash tags**: `{user42}:cart` and `{user42}:profile` hash only the part inside `{}`.

💡 **Majority vote (quorum):** with 3 watchers, 2 must agree the primary is dead, so one watcher with a broken network cannot trigger a wrong failover. **High availability (HA)** = the service stays up when a machine dies.

💡 **Shard / hash slot / CRC16:** a shard is one slice of the data held by one primary. A hash slot is one of 16,384 buckets a key falls into; CRC16 is a simple checksum function used as the hash. `mod` is the remainder after division.

Note this is *not* classic consistent hashing (see [consistent hashing](../concepts/consistent-hashing.md)) — it's a fixed number of slots mapped to nodes, which gives the same benefit (only some data moves when nodes change).

### 3.7 Atomic operations: INCR and Lua

- `INCR counter` → returns the new value atomically. 1,000 pods calling it at once never get a duplicate. Great for counters and simple ID generation.
- `INCRBY counter 1000` → reserve a **block** of 1000 IDs in one round trip (see [ID generation](../concepts/id-generation.md)).
- `SET lock:x <token> NX PX 30000` → set only if not exists, with expiry: a basic lock / dedup marker.
- **Lua scripts** (`EVAL`): run several commands as one atomic unit on the server. Example: a rate limiter "read count, if < limit then INCR and set TTL" in one script, no race between the read and the write.
- `MULTI/EXEC` transactions batch commands atomically but can't branch on intermediate results — that's what Lua is for.

💡 **Round trip:** one request plus its response over the network. **Lock / dedup marker:** a key whose presence means "someone is already doing this" or "already processed". **Lua:** a small scripting language embedded in Redis; the whole script runs without anything else interleaving. **Race:** the bug where two clients read then write and overwrite each other.

### 3.8 GEO commands: "who is near me?"

Redis can store positions and answer radius searches. Under the hood a GEO key is just a **sorted set**: each member's latitude/longitude is encoded as a 52-bit **geohash** (a number where nearby places share leading bits, see [geospatial indexing](../concepts/geospatial-indexing.md)) and used as the score, so a nearby search is a few range scans over the rider's cell and its neighbours.

💡 **Geohash / range scan:** a geohash turns (latitude, longitude) into one number so nearby places have close numbers; a range scan reads all entries between two scores. **O(log N)** = cost grows very slowly with data size. **Shard** = see Redis Cluster above.

```
GEOADD drivers:blr 77.5946 12.9716 driver:42                 # longitude first! overwrites the old position
GEOSEARCH drivers:blr FROMLONLAT 77.60 12.97 BYRADIUS 2 km ASC COUNT 20 WITHDIST
GEODIST drivers:blr driver:42 driver:77 km                   # straight-line distance
ZREM drivers:blr driver:42                                   # driver goes offline (it's a ZSET, so ZREM works)
```

- `GEOADD` is O(log N); one node takes ~100k updates/s, so 1M drivers pinging every 4 s (250k/s) needs a few shards, e.g. **one key per city**.
- `GEOSEARCH` (Redis 6.2+, replaces `GEORADIUS`) supports `BYRADIUS` or `BYBOX`, sorted by distance, with a `COUNT` limit.
- **No per-member TTL**: a driver whose app crashed stays in the set. Pair it with a `driver:42:alive` key with a short TTL (or store last-ping time) and filter / clean up stale members.
- Distances are straight-line; rank final candidates by road ETA elsewhere.

---

## 4. When to use it

- **Read-heavy cache** in front of a DB (cache-aside, see [caching strategies](../concepts/caching-strategies.md)). Example: short code → long URL lookups, 100:1 read:write.
- **Counters and rate limiters** shared by many stateless servers (`INCR` + `EXPIRE`).
- **Session store** for stateless web servers (data loss = user logs in again; acceptable).
- **Leaderboards / top-K** with sorted sets.
- **Distributed lock** for *efficiency* (avoid doing the same work twice), not for correctness (see section 6).
- **Dedup / idempotency keys** with `SET NX EX`.

💡 **Cache-aside:** the app checks the cache first, and on a miss reads the DB and stores the result in the cache itself. **Idempotency key:** a unique ID used to detect a repeated request.

## 5. When NOT to use it (and why it's a mistake)

| Situation | Why it's a mistake |
|---|---|
| As the **only copy** of data you can't lose (orders, payments, URL mappings) | Async replication + periodic fsync = acknowledged writes can vanish on failover. |
| Data much larger than RAM (multi-TB) | You'd pay for terabytes of RAM; a disk-based store (Cassandra, Postgres) is far cheaper. |
| You need queries like "all URLs created by user X last week" | Redis has no secondary indexes or query language; you'd hand-build indexes and keep them consistent yourself. |
| The backing store is already fast and not overloaded | You add a network hop, a second source of truth and invalidation bugs for ~no gain. |
| Data read once and never again | 0% hit rate: every request pays Redis miss + DB read. Pure overhead. |
| Huge pub/sub fan-out that must not lose messages | Redis pub/sub is fire-and-forget; offline subscribers miss messages. Use [Kafka](kafka.md). |

💡 **Pub/sub:** publishers send to a channel; whoever is subscribed right now receives it, and nobody else ever does (nothing is stored). **Secondary index:** an extra lookup structure on a non-key field.

---

## 6. Commonly confused with

| | **Redis** | **Memcached** | **Local in-process cache (Caffeine / Guava)** | **A database (Postgres)** |
|---|---|---|---|---|
| Where | Separate server, network hop | Separate server, network hop | Inside your JVM heap | Separate server |
| Latency | ~0.1–0.5 ms | ~0.1–0.5 ms | **~100 ns** (no network) | ~1–10 ms |
| Data types | Many (hash, zset, list...) | Only strings/blobs | Any Java object | Tables, rows |
| Shared across pods | Yes | Yes | **No** — each pod has its own copy | Yes |
| Persistence | Optional (RDB/AOF) | None | None (lost on restart) | Yes, durable (WAL) |
| Threading | Single-threaded command execution | Multi-threaded | n/a | Process/thread per connection |
| Replication / HA | Yes (replicas, Sentinel, Cluster) | No (client-side sharding only) | n/a | Yes |
| Pick when | You need structures, counters, HA | Very simple big blob cache, multi-core per node | Tiny, very hot, rarely-changing data (config, top 1k URLs) | Source of truth |

Key insight: **local cache and Redis stack**. A Caffeine cache (a popular Java library for in-memory caching inside your own process) of the top 10k hot keys in each pod (L1) in front of Redis (L2) in front of the DB is a common pattern. "L1" and "L2" are borrowed from CPU caches (see [latency numbers](../concepts/back-of-the-envelope.md#32-latency-numbers-every-engineer-should-know)): **L1** = the smallest, fastest layer closest to the code (memory inside each app server), **L2** = the next layer out (shared Redis), then the database. The catch with L1: invalidation — each pod has its own copy, so use short TTLs or immutable data (short URLs never change, which makes them perfect for L1).

💡 **JVM heap / ns:** the heap is the memory area where a Java process keeps its objects. ns = nanoseconds (billionths of a second); 100 ns is about 1000x faster than a network call.

---

## 7. Common mistakes / misuse

1. **Caching without a TTL.** Memory fills, eviction kicks in unpredictably (or with `noeviction` writes start failing at 3 a.m.), and stale data lives forever after a missed invalidation. Always set a TTL, even a long one.
2. **Treating Redis as durable.** "We write to Redis and a job copies to the DB later" — a crash between the two loses data. If it's the source of truth, write the DB first.
3. **Caching data that's rarely read.** Cache value comes from the hit rate. A key read once costs a Redis write + memory and saves nothing. Cache what's hot.
4. **Putting Redis in front of something already fast.** If Postgres serves a primary-key lookup in 1 ms at 5% CPU, a cache adds complexity, a consistency problem and another thing to page you, for ~0.5 ms gain.
5. **Hot keys.** One viral short URL gets 50k reads/sec; in Redis Cluster that key lives on **one** shard, so one node (one thread!) takes all of it. Fixes: local in-process cache for the hottest keys, or replicate the key (`url:abc123#1..#N`, read a random suffix), or read from replicas.
6. **Big keys / O(N) commands.** `KEYS *`, `HGETALL` on a 1M-field hash, or `SMEMBERS` on a giant set block the single thread for everyone. Use `SCAN`, keep values small (KB, not MB).
7. **Cache stampede.** A hot key expires; 1,000 requests miss at once and all hit the DB. Fixes: lock/single-flight so one request refills, jittered TTLs, refresh-ahead.

💡 **Hot key / replicate the key:** a hot key is one key getting a disproportionate share of traffic. Replicating it under several names spreads reads across shards.

💡 **Stampede / single-flight / jitter / refresh-ahead:** single-flight = only one request does the refill while the others wait. Jitter = add a random offset to TTLs so keys do not all expire together. Refresh-ahead = reload a key shortly before it expires.

8. **Redis locks for correctness.** `SET NX PX` locks can be held by two clients at once (GC pause past the TTL, or failover losing the lock). For correctness use fencing tokens or a consensus system like [ZooKeeper / etcd](zookeeper-etcd.md).
9. **Forgetting the network.** 10 sequential `GET`s = 10 round trips. Use `MGET` or pipelining.

💡 **GC pause / fencing token / consensus:** a stop-the-world garbage collection pause can freeze a Java process longer than the lock TTL, so it still thinks it holds a lock that expired. A fencing token is an increasing number given with each lock grant; the resource rejects older numbers. Consensus systems (ZooKeeper/etcd) make nodes agree on one answer even if some fail.

---

## 8. Interview cheat-sheet

> "I'll put Redis in front of the database as a cache-aside cache: check Redis, on a miss read the DB and populate Redis with a TTL. A single Redis node handles around 100k ops/sec at sub-millisecond latency, so with a ~90% hit rate the DB only sees a tenth of the reads. I'd configure `allkeys-lru` eviction so it behaves as a bounded cache, and I won't treat it as the source of truth because replication is asynchronous and persistence can lose the last second of writes. For scale I'd use Redis Cluster, which shards keys across 16,384 hash slots, and for very hot keys I'd add a small in-process cache in each app server. I can also use atomic `INCR` / `INCRBY` for counters or handing out ID blocks."

---

## 9. Used in

- [URL shortener](../interviews/url-shortener/README.md) — **cache for redirects** (short code → long URL, the read-heavy hot path) and an **atomic counter** (`INCR`/`INCRBY`) for generating IDs or counting clicks.
- [Notification system](../interviews/notification-system/README.md) — **idempotency/dedup keys** (`SET NX` with TTL), **per-user and per-provider rate limiting** (token buckets), the **WebSocket connection registry** (user → gateway), and caching user preferences.
- [Chat system](../interviews/chat-system/README.md): the **session/presence registry** (userId → gateway with a heartbeat-refreshed TTL), last-seen, Pub/Sub channels for routing messages to the right gateway, per-conversation sequence counters (`INCR`) and client-message-ID dedup keys.
- [LLD: Design an LRU Cache](../../LLD/interviews/lru-cache/README.md) — what's inside an in-process cache: the LRU/LFU data structures that Redis approximates with sampling, and when a local L1 cache sits in front of Redis.
- [News feed](../interviews/news-feed/README.md): the **per-user feed cache** (list/sorted set of ~500–800 post IDs written by fan-out on write), the **post object cache** used to hydrate feed items, like/view **counters** (`INCR`, HyperLogLog), and per-user **seen-post Bloom filters** (RedisBloom). See [counters at scale](../concepts/counters-at-scale.md) and [Bloom filters](../concepts/bloom-filters.md).
- [Ride-sharing](../interviews/ride-sharing/README.md): the **live driver location index** (`GEOADD` on every ~4 s ping, `GEOSEARCH` for nearby available drivers, one key per city), driver online TTL keys, offer locks/leases (`SET NX PX`), and the per-cell **surge multipliers** written by the stream job. See [geospatial indexing](../concepts/geospatial-indexing.md) and [distributed locks and leases](../concepts/distributed-locks-and-leases.md).
- [API gateway](../interviews/api-gateway/README.md): **global rate limits** per API key/user (token-bucket counters shared across gateway nodes, with local buckets in front to cut Redis calls), the API-key lookup cache, and the token **deny list** for revoked JWTs ([authentication, OAuth and JWT](../concepts/authentication-oauth-jwt.md)).
- [LLD: Design an In-Memory Key-Value Store with Transactions](../../LLD/interviews/kv-store/README.md) — build a mini Redis: single-threaded command execution, lazy TTL expiry, AOF with `appendfsync` policies and rewrite/snapshots, and why `MULTI/EXEC` has no rollback while the interview's `BEGIN/ROLLBACK` does.
- [Distributed key-value store](../interviews/distributed-kv-store/README.md): **comparison point**: an in-memory KV store with fixed hash slots and async leader-follower replication, vs the disk-based, leaderless, quorum-replicated design built in the interview.
- [Search autocomplete](../interviews/search-autocomplete/README.md): per-user **recent search history** for personalisation, fetched in parallel with the global index under a strict timeout.
- [Payment system](../interviews/payment-system/README.md): sliding-window velocity counters for the fraud check (L6 §2).
- Related concepts: [caching strategies](../concepts/caching-strategies.md), [ID generation](../concepts/id-generation.md), [consistent hashing](../concepts/consistent-hashing.md), [sharding and replication](../concepts/sharding-and-replication.md).

💡 **Sliding window / velocity counters (next bullet):** counting events in the last N minutes (a window that moves with time), e.g. "how many payments from this card in 10 min", to detect fraud. **Surge multiplier** = the price boost when demand exceeds driver supply.
