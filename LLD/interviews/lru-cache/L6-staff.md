# LRU Cache — L6 (Staff) LLD Interview

> **Level expectation:** you can build the L5 component, but the conversation is about *running caches in production*: stampedes, memory and GC cost, hit-rate economics, scan resistance, consistency with the source of truth, local vs distributed caches, and why the right answer is usually Caffeine plus good configuration. You still write the critical code (single-flight loading). Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Start with "should we cache at all?"

**🧑‍💼 Interviewer:** Our profile service is slow; a team wants to add an LRU cache. Design it.

**🧑‍💻 Candidate:** Before the cache, a few questions, because caches add a whole class of bugs (staleness, inconsistency, memory pressure):
- **Why is it slow?** A missing DB index is a better fix than a cache.
- **Access pattern:** how skewed? If 1% of profiles get 80% of reads, a small cache gets a high hit rate. If reads are uniform over 100M profiles, a cache of 1M entries gets ~1% hits and is pure overhead.
- **Staleness tolerance:** can a user see their old profile picture for 60 seconds after changing it?
- **Read:write ratio:** caching write-heavy data mostly generates invalidations.

**🧑‍💼 Interviewer:** Reads are 50k/s, heavily skewed; 60 s staleness is OK, except users must see their *own* edits immediately.

**🧑‍💻 Candidate:** Then a cache is justified. The "own edits immediately" requirement I'll handle explicitly ([§6](#6-consistency-with-the-source-of-truth)).

> 📝 **Note:** Expected hit rate ≈ "how much of the traffic falls on the keys that fit". Estimating it before building is the staff habit. See [caching strategies](../../../HLD/concepts/caching-strategies.md).

---

## 2. Cache stampede: single-flight loading

**🧑‍💻 Candidate:** The production failure mode of every cache: a **hot key** expires (or the cache restarts), and 2,000 concurrent requests all miss and all hit the database for the same row. The DB slows down, requests pile up, timeouts cascade. That's a **cache stampede** (also called "thundering herd" or "dog-piling").

Fix: **single-flight**. The first thread to miss starts the load; everyone else waits for *that* result. [LoadingCache.java](java/src/lrucache/LoadingCache.java):

```java
private final Map<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

public V get(K key) {
    Optional<V> cached = cache.get(key);
    if (cached.isPresent()) return cached.get();

    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);   // atomic: exactly one winner
    if (existing != null) return existing.join();                      // losers wait for the winner

    try {
        Optional<V> loadedMeanwhile = cache.get(key);                  // re-check: a load may have just finished
        if (loadedMeanwhile.isPresent()) { mine.complete(loadedMeanwhile.get()); return loadedMeanwhile.get(); }
        V value = loader.apply(key);
        cache.put(key, value);
        mine.complete(value);
        return value;
    } catch (RuntimeException e) {
        mine.completeExceptionally(e);   // current waiters get the same error...
        throw e;
    } finally {
        inFlight.remove(key, mine);      // ...but it's NOT cached: the next caller retries
    }
}
```

Tested: `loadingCacheLoadsHotKeyOnce` (100 threads miss at once, loader runs **once**) and `loadingCacheDoesNotCacheFailures`. ([CompletableFuture](../../libraries/java/completablefuture.md).)

Design points to say out loud:
- `putIfAbsent` is the atomic election; `remove(key, mine)` only removes *our* future, never a newer one.
- **Failures aren't cached**: a DB timeout shouldn't poison the key. (Optionally cache "not found" briefly: **negative caching**, so a flood of lookups for a deleted user doesn't hit the DB.)
- **Waiters need a timeout** in production (`orTimeout`), or a stuck loader stalls every thread asking for that key.
- Complementary fixes: **refresh-ahead** (reload a hot entry in the background *before* it expires; Caffeine's `refreshAfterWrite`) and **jittered TTLs** (so entries loaded together don't all expire in the same second).

---

## 3. Memory: count bytes, not entries

**🧑‍💼 Interviewer:** You set capacity = 1,000,000. How much memory is that?

**🧑‍💻 Candidate:** "Entries" is the wrong unit; the JVM cares about bytes. Rough per-entry overhead in our `LruCache`:

| Part | Approx. bytes (64-bit JVM, compressed pointers) |
|---|---|
| `HashMap` entry (`Node`: hash, key, value, next) | ~32 |
| Map's bucket array slot | ~4–8 |
| Our list `Node` (header + key, value, prev, next, expiresAt) | ~40 |
| **Overhead per entry** | **~80 bytes** before the key and value themselves |

So 1M entries ≈ 80 MB of bookkeeping **plus** keys and values. A user profile of ~2 KB → ~2 GB. That's a heap-sizing conversation.

- **Bound by weight, not count:** Caffeine's `maximumWeight` + a `weigher` (e.g. serialized size) caps *bytes* when values vary in size.
- **GC cost:** millions of long-lived small objects mean more work for the garbage collector's marking phase, and they get promoted to the old generation, which can lengthen GC pauses. Mitigations: fewer, bigger values; or **off-heap** caches (memory outside the Java heap, e.g. Chronicle Map, OHC) for very large caches ([references & GC](../../libraries/java/references-and-gc.md)).
- **Don't use `SoftReference` caches** "so the GC frees memory when needed": the GC clears them in bulk under pressure, causing hit rate to collapse exactly when the system is struggling.

---

## 4. Hit rate is the product: scan resistance

**🧑‍💻 Candidate:** Pure LRU has a known weakness: a **scan**, e.g. a nightly batch job reading every profile once, pushes out all the genuinely hot entries, and the morning traffic hits a cold cache.

| Policy | Scan-resistant? | Notes ([eviction policies](../../concepts/cache-eviction-policies.md)) |
|---|---|---|
| LRU | ❌ | Simple, good default for recency-heavy workloads |
| LFU | ✅ mostly | But old popularity never fades without decay |
| SLRU / 2Q | ✅ | New entries go to a "probation" area and only get promoted when hit again |
| **W-TinyLFU** (Caffeine) | ✅ | A tiny compact frequency counter ("sketch") decides whether a new entry deserves to evict an existing one. Near-optimal hit rates in published benchmarks |

**🧑‍💻 Candidate:** This is the strongest argument for Caffeine over a hand-rolled LRU: same API, measurably higher hit rate on real workloads, plus lock-free reads (it records accesses in buffers and replays them onto the policy in batches), plus stampede-safe loading, refresh, weights, stats. ([Caffeine](../../libraries/java/caffeine-and-guava-cache.md).)

```java
LoadingCache<UserId, Profile> profiles = Caffeine.newBuilder()
    .maximumWeight(500_000_000)                    // ~500 MB
    .weigher((UserId id, Profile p) -> p.estimatedBytes())
    .expireAfterWrite(Duration.ofSeconds(60))
    .refreshAfterWrite(Duration.ofSeconds(45))     // hot entries reload in the background
    .recordStats()
    .build(profileRepository::load);               // single-flight loading built in
```

---

## 5. Local cache, distributed cache, or both?

| | In-process (this code / Caffeine) | Distributed ([Redis](../../../HLD/technologies/redis.md)) |
|---|---|---|
| Latency | ~100 ns–1 µs | ~0.5 ms (network round trip) |
| Shared across instances | ❌ each pod has its own copy | ✅ |
| Survives deploys | ❌ cold after every restart | ✅ |
| Memory | Competes with your heap | Separate fleet |
| Invalidation | Hard: N copies | One place |

**🧑‍💻 Candidate:** For 50k reads/s of skewed, 60 s-stale-OK data: **both**. A small local cache (hot 10k profiles, 30 s TTL) in front of Redis in front of the DB. The local layer absorbs hot keys and protects Redis from **hot-key** overload; Redis keeps the hit rate high after deploys. (The two-layer setup from the [URL shortener](../../../HLD/interviews/url-shortener/L5-senior.md#54-caching--beyond-add-redis).)

**Deploys:** 30 pods restart → 30 cold local caches → a burst on Redis/DB. Rolling deploys, and optionally warming the cache on startup from a "top keys" list.

---

## 6. Consistency with the source of truth

**🧑‍💼 Interviewer:** Users must see their own edits immediately.

**🧑‍💻 Candidate:** With N local caches, invalidating "everywhere" is the hard part. Options, cheapest first:
1. **Read-your-own-writes bypass:** after a user edits their profile, that user's requests skip the cache for 60 s (flag in their session). Everyone else may see stale data for up to the TTL. This is exactly the stated requirement, and costs almost nothing.
2. **Invalidate on write via pub/sub:** the writer publishes `invalidate(userId)`; every pod drops it from its local cache. Fast, but messages can be lost, so the TTL remains the safety net.
3. **Versioned keys:** cache key includes a version number bumped on every write; old entries simply stop being read.

Ordering trap with option 2: *write DB → invalidate cache* can race with a concurrent reader that loaded the old value just before the write and puts it back *after* the invalidation. Short TTLs bound the damage; for strict cases, delete-after-write plus a delayed second delete, or versioned values.

---

## 7. Operating it

- **Metrics per cache:** hit rate, load latency, load failures, eviction count by cause, size/weight. Alert on hit-rate *drops*, which usually precede DB overload.
- **Kill switch:** a config flag to bypass the cache, for when it's serving bad data.
- **Capacity reviews:** hit rate vs size curve. Doubling the cache from 1 GB to 2 GB might move the hit rate from 92% to 93%: not worth it.
- **Don't hide outages:** a cache in front of a dead DB serves stale data until TTL, then everything fails at once. Decide whether "serve stale on error" is acceptable (Caffeine refresh keeps the old value if reload fails).

---

## 8. Curveballs

**🧑‍💼 Interviewer:** Cache hit rate dropped from 95% to 60% after a release, nothing else changed.

**🧑‍💻 Candidate:** Usual suspects: the cache **key changed** (e.g. a new field added to the key object, or `equals/hashCode` broken on a key class so every lookup misses); TTL shortened by a config change; a new code path doing scans (batch export through the cache); a value grew in size so weight-bounded capacity now fits fewer entries. I'd compare the key cardinality and eviction-cause metrics before and after.

**🧑‍💼 Interviewer:** Why not just put everything in Redis and skip local caching?

**🧑‍💻 Candidate:** Often that's the right call, since it's simpler to reason about. Local caching earns its place when Redis round trips dominate latency (many lookups per request), or when hot keys overload a single Redis shard. Every extra cache layer is another place for stale data to hide.

---

## 9. What the interviewer was evaluating (L6)

- [ ] Asked whether to cache at all; estimated hit rate from access skew
- [ ] Single-flight loading with correct failure handling; refresh-ahead and jitter
- [ ] Memory in bytes, per-entry overhead, GC implications, weight-based bounds
- [ ] Scan resistance and why W-TinyLFU / Caffeine beats hand-rolled LRU
- [ ] Local vs distributed trade-offs; two-layer design; deploy cold-start
- [ ] Concrete consistency strategy matching the requirement (read-your-writes bypass), aware of invalidation races
- [ ] Operability: metrics, kill switch, "stale on error" decision

## 10. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| Building a custom cache in production code | Caffeine is better on every axis that matters |
| Sizing by entry count only | OOM when values grow |
| No stampede protection on hot keys | The cache causes the outage it was meant to prevent |
| Caching failures/exceptions | One DB blip poisons a key for the whole TTL |
| Pub/sub invalidation with no TTL backstop | Lost message = stale forever |
| Not measuring hit rate before and after | No idea whether the cache helps |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
