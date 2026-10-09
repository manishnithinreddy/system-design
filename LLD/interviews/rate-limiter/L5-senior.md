# Rate Limiter — L5 (Senior) LLD Interview

> **Level expectation:** you don't just make one algorithm work — you design a small **extensible library**: pluggable algorithms, per-tier configs, bounded memory, and a reasoned stance on concurrency (locks vs lock-free). You prove correctness under contention with tests. Read [L4-mid.md](L4-mid.md) first; this file covers what's new.

💡 **Extensible library / locks vs lock-free / contention:** a library others plug into without editing its source. A *lock* lets one thread at a time into a section of code; *lock-free* code instead retries atomic updates (see Option 2). *Contention* = many threads fighting for the same lock or variable, so they wait. **Tier** = a customer plan level (free, premium).

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Requirements

**🧑‍💻 Candidate:** Proposing scope (in-process library, used by several services in our org):

- `tryAcquire(key)`; key can be user ID, API key, IP — caller decides.
- **Multiple algorithms**, chosen by config — different endpoints need different behaviour (login: exact; public API: bursty is fine).
- **Tiers:** free = 100/min, premium = 10,000/min. Config resolved per key.
- **Bounded memory:** idle keys evicted.
- **Thread-safe, low overhead:** < 1 µs uncontended; no global lock.
- **Testable** without sleeping.
- Out of scope for now: distributed limiting (that's the [L6 discussion](L6-staff.md)), but the interface must not prevent it.

💡 **µs / uncontended / eviction:** a µs (microsecond) is a millionth of a second. Uncontended = only one thread is using it at the moment, so no waiting. Evicted = deleted from memory because it is no longer needed.

> 📝 **Note:** "The interface must not prevent distributed later" is a senior instinct: design for the next requirement without building it.

💡 **Distributed limiting:** enforcing one shared limit across many servers, usually with a shared store such as [Redis](../../../HLD/technologies/redis.md) (see [L6](L6-staff.md)).

---

## 2. Design

```mermaid
classDiagram
    class RateLimiter { <<interface>> +tryAcquire() boolean }
    class RateLimiterFactory { +create(RateLimitConfig) RateLimiter }
    class KeyedRateLimiter {
        -ConcurrentHashMap limiters
        -Function configResolver
        +tryAcquire(key) boolean
        +evictIdle()
        +close()
    }
    class TimeSource { <<interface>> +nanoTime() long }
    RateLimiter <|.. TokenBucketLimiter
    RateLimiter <|.. LockFreeTokenBucketLimiter
    RateLimiter <|.. FixedWindowLimiter
    RateLimiter <|.. SlidingWindowLogLimiter
    RateLimiter <|.. SlidingWindowCounterLimiter
    KeyedRateLimiter --> RateLimiterFactory
    KeyedRateLimiter o-- RateLimiter
    RateLimiterFactory --> TimeSource
```

**🧑‍💻 Candidate:** Patterns, and why each earns its place ([design patterns](../../concepts/design-patterns.md)):

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | `RateLimiter` interface + 5 implementations | Swap algorithm per endpoint without changing callers (Open/Closed — [SOLID](../../concepts/solid-principles.md)) |
| **Factory** | `RateLimiterFactory.create(config)` | Callers never `new` a concrete class; adding an algorithm touches the enum + factory only |
| **Dependency injection** | `TimeSource` passed in | Tests control time; production uses `System::nanoTime` |

💡 **Strategy / Factory / Open-Closed / DI:** Strategy = swappable implementations behind one interface; Factory = a class that picks which implementation to build; Open/Closed = add behaviour by adding code, not by editing existing code; dependency injection = passing dependencies (like the clock) into a class from outside. All four are explained in [design patterns](../../concepts/design-patterns.md) and [SOLID](../../concepts/solid-principles.md).

**🧑‍💻 Candidate:** I deliberately *didn't* add: a Singleton (makes testing hard; let the app's DI container own one instance), an abstract base class for "window" algorithms (they share ~3 lines; inheritance would couple them for nothing), or a Builder for a 3-field record.

💡 **Singleton / Builder / DI container:** a Singleton is a class allowed exactly one instance, reachable globally, which makes tests share state. A Builder is a helper that assembles an object step by step, overkill for 3 fields. A DI container (like Spring) creates objects and injects their dependencies for you. **Inheritance** = a class extending a base class; **coupling** = how much one piece depends on another.

> 📝 **Note:** Saying what you **didn't** add and why is a strong senior signal. Pattern-stuffing is a common L4→L5 trap.

### Config & tiers

```java
public record RateLimitConfig(Algorithm algorithm, long limit, Duration window) { /* validates in compact constructor */ }

KeyedRateLimiter limiter = new KeyedRateLimiter(
    key -> tierOf(key) == PREMIUM
            ? RateLimitConfig.of(TOKEN_BUCKET, 10_000, Duration.ofMinutes(1))
            : RateLimitConfig.of(TOKEN_BUCKET, 100, Duration.ofMinutes(1)),
    new RateLimiterFactory(TimeSource.SYSTEM),
    TimeSource.SYSTEM,
    Duration.ofMinutes(10),   // idle eviction
    true);
```

The resolver is a `Function<String, RateLimitConfig>` — callers plug in a DB lookup, a config file, a feature flag. The limiter doesn't care. ([KeyedRateLimiter.java](java/src/ratelimiter/KeyedRateLimiter.java))

💡 **Record / compact constructor / feature flag:** a record is a short immutable data class; its compact constructor is where you validate fields. A feature flag is a config switch that turns behaviour on or off without a deploy.

---

## 3. Deep dive: the four algorithms

All code in [java/src/ratelimiter/](java/src/ratelimiter/). Visualising a limit of 5/second:

```text
limit = 5 per second
time (ms):      0 ............ 900 ... 999 | 1000 ... 1100 ............ 2000
Fixed window:   [ window 1          █████ ] [ █████     window 2           ]
                                    └── 10 requests within ~200 ms ──┘   ⚠ 2× the limit
Sliding window: any 1-second span, wherever you place it, contains at most 5  ✅
```

### Fixed window — [FixedWindowLimiter.java](java/src/ratelimiter/FixedWindowLimiter.java)

💡 **Window algorithms:** a *fixed window* counts requests per calendar slot and resets at the boundary; a *sliding window* looks at the last N seconds relative to now. *Deque* = a double-ended queue (add/remove at both ends). *Edge burst* = a spike straddling the boundary of two fixed windows.

`windowIndex = floorDiv(now, windowNanos)`; reset count when the index changes. Two fields. **Edge burst up to 2× limit.**

### Sliding window log — [SlidingWindowLogLimiter.java](java/src/ratelimiter/SlidingWindowLogLimiter.java)
Deque of timestamps; drop those older than `now - window`; allow if `size < limit`. **Exact**, but memory is O(limit) per key: 10,000/hour × 1M users × 8 bytes = 80 GB. Fine for "5 login attempts per 15 min", absurd for high limits.

💡 **O(limit) / bytes:** O(limit) means memory grows in proportion to the limit; 10,000 timestamps × 8 bytes each × 1M users = 80 GB.

### Sliding window counter — [SlidingWindowCounterLimiter.java](java/src/ratelimiter/SlidingWindowCounterLimiter.java)
```text
estimate = previousWindowCount × (1 − elapsedFractionOfCurrentWindow) + currentWindowCount
```
E.g. limit 10/s, previous window had 10, we're 25% into the current window → estimate = 10 × 0.75 + current. Only ~2.5 more allowed. Three numbers of state, near-exact (assumes the previous window's traffic was evenly spread).

### Token bucket — [TokenBucketLimiter.java](java/src/ratelimiter/TokenBucketLimiter.java)
Covered in L4. Two params in reality — **capacity** (burst) and **refill rate** — which my config collapses into one (`limit` per `window`). A real API would expose both: "100/min sustained, bursts up to 20".

**🧑‍💼 Interviewer:** Which would you choose as the default?

**🧑‍💻 Candidate:** **Token bucket** for API traffic — bursts are natural (a page load fires 10 calls) and it's what clients expect from AWS/Stripe-style APIs. **Sliding window log** for security-sensitive low limits (login, OTP). **Sliding window counter** when product says "no bursts" at high volume. Fixed window only for coarse quotas (daily/monthly) where the edge doesn't matter.

---

## 4. Deep dive: concurrency

### Option 1 — `synchronized` per limiter (what most implementations use)

💡 **JVM / ns / contend:** the JVM is the Java runtime. ns = nanoseconds (a billionth of a second); 20 ns is far below any network call. To contend = to compete for the same lock.

Each limiter instance is its own lock. Different keys never contend. Uncontended `synchronized` is ~20 ns on modern JVMs. **This is the right default.** ([locks & synchronized](../../libraries/java/locks-and-synchronized.md))

### Option 2 — lock-free CAS — [LockFreeTokenBucketLimiter.java](java/src/ratelimiter/LockFreeTokenBucketLimiter.java)

```java
private record State(double tokens, long lastRefillNanos) {}
private final AtomicReference<State> state;

public boolean tryAcquire() {
    while (true) {
        State current = state.get();
        long now = time.nanoTime();
        double refilled = Math.min(capacity, current.tokens() + Math.max(0, now - current.lastRefillNanos()) * tokensPerNano);
        if (refilled < 1) return false;
        State next = new State(refilled - 1, Math.max(now, current.lastRefillNanos()));
        if (state.compareAndSet(current, next)) return true;   // else someone else won: retry
    }
}
```

The two fields must change *together*, so they're bundled in one immutable record and swapped with one CAS. ([atomics & CAS](../../libraries/java/atomics-and-cas.md))

💡 **CAS / `AtomicReference` / retry loop:** compare-and-set swaps in a new value only if the current value is still the one you read, as one uninterruptible CPU operation; otherwise it returns false and you loop and try again. `AtomicReference` is the JDK box that offers it. Bundling both fields in one immutable record lets one CAS update them together.

**🧑‍💼 Interviewer:** So is lock-free better?

**🧑‍💻 Candidate:** Only in a narrow case: **many threads hammering the same key** (e.g. one huge tenant, or a global limit). There it avoids threads parking. Otherwise it's more complex, allocates a record per call, and under extreme contention CAS retries can spin. I'd ship `synchronized`, keep the CAS version behind the same interface, and switch only if a profiler shows contention. *The interface makes that a one-line config change.*

💡 **Parking / spin / profiler:** a blocked thread is "parked" (taken off the CPU) by the OS, which is slow to resume; "spinning" is burning CPU in a retry loop instead. A profiler is a tool that shows where a program spends time, so you optimise on evidence. **Allocates** = creates a new object on the heap each call, adding garbage-collection work.

One subtle line: `Math.max(now, current.lastRefillNanos())`. Two threads can read `nanoTime` in one order and CAS in the other; without the `max`, `lastRefill` could move backwards and grant extra tokens.

💡 **`nanoTime`:** a monotonic (never goes backwards) high-resolution timer in Java used for measuring elapsed time, unlike wall-clock time.

### Proving it — a contention test

```java
// 32 threads × 500 calls = 16,000 attempts against a limit of 1,000, time frozen.
// Exactly 1,000 must pass — not 999, not 1,001.
CountDownLatch start = new CountDownLatch(1);   // release all threads together to maximise overlap
...
assertEquals(1_000, allowed.get());
```

💡 **`CountDownLatch` / frozen time / probabilistic:** a latch makes threads wait until a signal, so all start at once. Frozen time = a fake clock that does not advance, so no tokens refill mid-test. A probabilistic bug shows up only on some runs, so one green run proves little.

Runs for **all five** algorithms ([RateLimiterTests.java](java/src/ratelimiter/RateLimiterTests.java)). Try deleting `synchronized` from a limiter and re-running: the count will usually come out above 1,000. (Race bugs are probabilistic — a test passing once proves little, which is why it uses 32 threads and a latch to maximise overlap.)

💡 **Fake time source:** a test stand-in for the clock that you advance manually.

> 📝 **Note:** Freezing time with `FakeTimeSource` makes the expected answer exact. With a real clock, tokens would refill during the test and you could only assert "≤ something".

---

## 5. Deep dive: memory — evicting idle keys

```java
private static final class Entry {
    final RateLimiter limiter;
    volatile long lastAccessNanos;   // written by request threads, read by the evictor thread
}

evictor.scheduleWithFixedDelay(this::evictIdleSafely, period, period, MILLISECONDS);

public void evictIdle() {
    long now = time.nanoTime();
    limiters.entrySet().removeIf(e -> now - e.getValue().lastAccessNanos > idleTimeoutNanos);
}
```

Details worth saying out loud:

💡 **Evictor / sweep:** a background task that periodically scans the map and deletes idle entries. **Visibility:** whether one thread can see another's write (explained in [thread-safety basics](../../concepts/thread-safety-basics.md)).

- **`volatile`** — without it, the evictor thread may never see updates made by request threads (visibility, [thread-safety basics](../../concepts/thread-safety-basics.md)).
- **`scheduleWithFixedDelay`**, not `AtFixedRate` — if a sweep is slow, don't queue up back-to-back sweeps.
- **Catch exceptions** in the scheduled task — an uncaught exception silently cancels all future runs. ([ScheduledExecutorService](../../libraries/java/scheduled-executor-service.md))
- **Daemon thread + `AutoCloseable`** — doesn't block JVM shutdown; tests can close it.

💡 **Fixed delay vs fixed rate / daemon / AutoCloseable:** *fixed delay* waits N after the last run finished; *fixed rate* starts every N regardless, so slow runs can pile up. A *daemon thread* does not stop the JVM from exiting. `AutoCloseable` lets you stop the resource with try-with-resources or `close()`.

**🧑‍💼 Interviewer:** Is there a race between eviction and `tryAcquire`?

**🧑‍💻 Candidate:** Yes, a benign one. Thread A gets the entry via `computeIfAbsent`; the evictor removes it (it was idle); A uses the now-orphaned limiter; the *next* request creates a fresh, **full** bucket. Worst case, a client that was idle for 10 minutes gets one extra burst — but an idle-for-10-minutes client would have a full bucket anyway. So the race is harmless for token bucket. I'd document it rather than add locking. If it mattered, use `compute()` to re-check idleness atomically per key, or use Caffeine's `expireAfterAccess`, which handles it internally.

💡 **Race / benign / orphaned:** a race is a timing-dependent outcome; benign = harmless in effect. An orphaned object is one nobody references from the map anymore. `compute()` runs a function atomically on one key's entry. `expireAfterAccess` is Caffeine's setting to drop entries not read for a given time.

**🧑‍💼 Interviewer:** Why not just use Caffeine?

**🧑‍💻 Candidate:** In production I would — and honestly I'd use [Bucket4j or Resilience4j](../../libraries/java/production-rate-limit-libraries.md) instead of writing the algorithms at all. In the interview I wrote it to show I understand what those libraries do.

💡 **Bucket4j / Resilience4j:** well-tested Java libraries for rate limiting (Bucket4j) and for resilience features including limiting, retries and circuit breakers (Resilience4j).

---

## 6. Extensions the interviewer may ask for

**🧑‍💼 Interviewer:** Clients want to know how many requests they have left.

**🧑‍💻 Candidate:** Change the return type from `boolean` to a result:
```java
public record Decision(boolean allowed, long remaining, Duration retryAfter) {}
```
Map it to headers: `RateLimit-Remaining`, `Retry-After`. For token bucket, `retryAfter = (1 − tokens) / rate`. I'd add it as a new method (`tryAcquireWithInfo`) with `tryAcquire()` as a default method delegating to it, so existing implementations and callers don't break.

💡 **Default method / backward compatible:** a Java interface method with a body, so adding it does not break existing implementers. **Header** = a name-value line in an HTTP request or response.

**🧑‍💼 Interviewer:** "10 per second AND 1,000 per day."

**🧑‍💻 Candidate:** A `CompositeRateLimiter` holding a list of limiters, allowing only if **all** allow. Careful: if the per-second limiter allows but the daily one rejects, we already consumed a per-second token. Options: check all with a "peek" first then consume, or accept the slight under-count. (This is the Composite pattern.)

💡 **Composite pattern:** treating a group of objects through the same interface as a single one. **Peek** = look without consuming. **Under-count** = recording less usage than actually happened.

**🧑‍💼 Interviewer:** Cost of a request varies — a search costs 5, a read costs 1.

**🧑‍💻 Candidate:** `tryAcquire(int permits)` — token bucket handles it naturally (`tokens >= permits`). Default `tryAcquire()` calls `tryAcquire(1)`.

---

## 7. JavaScript version

[js/rateLimiters.js](js/rateLimiters.js) mirrors the Java design (factory + 4 classes + keyed `Map`). Differences worth knowing:
- **No locks:** single-threaded event loop; `tryAcquire` has no `await`. ([event loop](../../libraries/js/event-loop-and-concurrency.md))
- **But** the moment the state moves to a remote store, `read → await → write` races again — the test `the one race Node CAN have` reproduces it.
- `performance.now()` as the monotonic clock; `setInterval(...).unref()` for eviction so the process can exit.
- `Map`, not a plain object, for the registry ([Map vs Object](../../libraries/js/map-vs-object.md)).
- Plugged into HTTP via [middleware](../../libraries/js/express-middleware.md) — [middleware.js](js/middleware.js), [server.js](js/server.js).

💡 **Event loop / `unref()` / `performance.now()`:** Node's event loop runs your JS on one thread, taking queued tasks one at a time. `unref()` tells Node not to wait for that timer before exiting. `performance.now()` is a monotonic timer in milliseconds.

---

## 8. What the interviewer was evaluating (L5)

- [ ] Scoped requirements including tiers, memory, and future distribution
- [ ] Strategy + Factory used **for a reason**; avoided unnecessary patterns
- [ ] Implemented and compared multiple algorithms; recommended per use case
- [ ] Concurrency: per-limiter locking as default; understood when CAS helps; the `max(now, last)` subtlety
- [ ] A contention test with an **exact** expected result
- [ ] Bounded memory: eviction with `volatile`, safe scheduling, and an honest analysis of the eviction race
- [ ] Extended the API without breaking callers
- [ ] Knew to use Bucket4j/Resilience4j in production

## 9. Common mistakes at this level

| Mistake | Why it hurts at L5 |
|---|---|
| Abstract base class + 3 layers of inheritance for 4 small algorithms | Over-engineering; composition/interface is enough |
| Making everything lock-free "for performance" with no measurement | Complexity without evidence |
| Two separate `AtomicLong`s for tokens and timestamp | Each is atomic, but the *pair* isn't — still a race |

💡 **Memory leak:** memory that grows because nothing ever frees it; here, idle limiters that are never evicted.

| Eviction task that can die silently | Memory leak in production weeks later |
| No concurrency test, or one that only asserts "≤ limit" | Doesn't prove correctness |
| Changing the interface signature for a new feature | Breaks every caller; add a default method instead |

⬅️ Previous: [L4-mid.md](L4-mid.md) · ➡️ Next: [L6-staff.md](L6-staff.md)
