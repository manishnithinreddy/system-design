# Node.js Event Loop and Concurrency

## 1. One-line summary

Node runs your JavaScript on **one thread** driven by an **event loop**, so synchronous code can never be interrupted by other JS — which is why an in-memory rate limiter needs no locks — but races still appear across `await` points and across multiple processes.

## 2. The problem it solves

In Java, the rate limiter needs [locks](../java/locks-and-synchronized.md) or [atomics](../java/atomics-and-cas.md) because many threads run `tryAcquire` truly in parallel. That's a lot of subtle code.

Node takes a different trade: one thread runs all JavaScript, and I/O (sockets, files, timers) is handled by the OS and libuv in the background and reported back as **callbacks/events**. You get concurrency (thousands of in-flight requests) without parallel execution of your JS — so plain object updates are safe by construction.

## 3. How it works

```mermaid
flowchart LR
    Req[Incoming requests] --> Q[Event queue]
    Q --> EL{{Event loop<br/>single JS thread}}
    EL -->|run callback to completion| H[handler: limiter.tryAcquire]
    H -->|await db / fetch| IO[libuv / OS<br/>I/O, timers, thread pool]
    IO -->|done → enqueue callback| Q
```

Key rule: **a synchronous block of JS runs to completion.** Nothing else runs until it returns or hits an `await`.

### Why no locks are needed

```js
class TokenBucket {
  constructor(capacity, refillPerSec, now = () => performance.now()) {
    this.capacity = capacity;
    this.refillPerMs = refillPerSec / 1000;
    this.tokens = capacity;
    this.now = now;
    this.last = now();
  }

  tryAcquire() {                      // fully synchronous: atomic w.r.t. other JS
    const t = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + (t - this.last) * this.refillPerMs);
    this.last = t;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }
}
```

Two requests for the same user can't interleave inside `tryAcquire` — the second one's callback isn't even started until the first one's returns. The Java check-then-act race simply can't happen here.

### Where races still happen

**1. An `await` between read and write.** Every `await` yields to the event loop; other requests run in between.

```js
// BROKEN: classic lost update across an await
async function tryAcquire(key) {
  const count = await store.get(key);        // A reads 4, yields; B reads 4, yields
  if (count >= LIMIT) return false;
  await store.set(key, count + 1);           // both write 5 → two requests, one count
  return true;
}
```

Fix: keep the read-modify-write **synchronous** for in-memory state, or make it a **single atomic operation** in the external store (Redis `INCR`, or a Lua script that does check + decrement together).

**2. Multiple processes.** `node:cluster`, PM2 cluster mode, `worker_threads`, or simply several pods behind a load balancer each have their **own memory**. An in-memory `Map` in each process means each one enforces the limit separately: 4 workers × 100/min = 400/min. Shared state must move to Redis (or similar) — same conclusion as Java across pods.

**3. `worker_threads` with `SharedArrayBuffer`.** If you deliberately share memory between worker threads, you're back in multi-threaded land and need `Atomics`. Rare for a rate limiter.

### Timers: setInterval and unref()

Idle-key eviction (the Node equivalent of a [ScheduledExecutorService](../java/scheduled-executor-service.md)):

```js
const sweeper = setInterval(() => {
  const now = performance.now();
  for (const [key, bucket] of buckets) {
    if (now - bucket.last > IDLE_MS) buckets.delete(key);  // deleting while iterating a Map is safe
  }
}, 60_000);
sweeper.unref();   // don't keep the process alive just for this timer

// on shutdown / in tests:
clearInterval(sweeper);
```

- An active timer keeps the Node process alive. Without `unref()`, a test file or CLI script hangs at the end — the JS cousin of a non-daemon Java thread.
- An exception thrown inside the callback is an **uncaught exception** and by default crashes the process. Wrap the body in try/catch.
- Timers are a minimum delay, not a guarantee: a long synchronous task delays them.
- As in Java, refill tokens **lazily** on access, not with a timer per bucket.

### Monotonic clocks

| | `Date.now()` | `performance.now()` | `process.hrtime.bigint()` |
|---|---|---|---|
| Kind | wall clock (ms since epoch) | monotonic, ms as float since process start | monotonic, ns as `BigInt` |
| Jumps with NTP | yes | no | no |
| Use for | timestamps, `Retry-After` dates | elapsed time in limiters | high-resolution measurements |

```js
const t0 = process.hrtime.bigint();
// ...
const elapsedMs = Number(process.hrtime.bigint() - t0) / 1e6;  // BigInt minus BigInt, then convert
```

Don't mix `BigInt` and `Number` in arithmetic — it throws `TypeError`. `performance.now()` is the simpler default for a limiter. Inject the clock function (`now = () => performance.now()`) so tests can pass a fake, exactly like the Java [TimeSource](../java/time-and-clock.md).

## 4. When to use it

- Reasoning about whether in-memory state in a Node service needs protection (usually: no, if updates are synchronous).
- Designing the in-process rate limiter for a single Node instance.
- Explaining why the distributed version needs atomic operations in Redis.

## 5. When NOT to use it

- **Don't rely on "single-threaded" for correctness across `await`s** — the guarantee ends at the first `await`.
- **Don't rely on it across processes** — cluster mode and multiple pods each have separate memory.
- **Don't do CPU-heavy work on the event loop** (big JSON parsing, crypto, regex on huge input) — it blocks every request, including the limiter's own check. Offload to `worker_threads` or another service.
- **Don't add mutex libraries for synchronous code** — a lock around code that can't be interrupted is noise that suggests a misunderstanding.

## 6. Commonly confused with

| | Node (single process) | Node cluster / multiple pods | Java multi-threaded |
|---|---|---|---|
| JS/Java code runs in parallel | no | yes, in separate memory | yes, shared memory |
| Lock needed for in-memory counter | no | n/a (memory not shared) | yes |
| Race source | `await` between read and write | separate copies of state | any interleaving of threads |
| Shared-limit fix | keep it synchronous | Redis atomic ops | locks / atomics / CHM |

## 7. Common mistakes / misuse

1. **`await` inside the read-modify-write** of a counter — lost updates.
2. **In-memory limiter behind PM2 cluster mode / multiple pods**, believed to be global.
3. **Forgetting `unref()` / `clearInterval`** — tests and scripts never exit.
4. **Using `Date.now()` for elapsed time** — clock jumps break refill math.
5. **Throwing inside a `setInterval` callback** — crashes the process.
6. **Saying "Node is single-threaded so it can't have race conditions"** in an interview — half true; interviewers will push on the `await` and multi-process cases.

## 8. Interview cheat-sheet

- "Node runs JavaScript on one thread and each synchronous function runs to completion, so my in-memory `tryAcquire` needs no locks."
- "Races come back the moment there's an `await` between reading and writing state, so I keep the in-memory update synchronous."
- "With cluster mode or multiple pods each process has its own `Map`, so a global limit needs Redis with an atomic script."
- "The eviction timer is `unref()`'d so it doesn't hold the process open, and wrapped in try/catch."
- "I use `performance.now()` or `process.hrtime.bigint()` — monotonic — not `Date.now()`, and inject it for tests."

## 9. Used in

- [LLD: Design a Rate Limiter](../../interviews/rate-limiter/README.md) — JavaScript implementation, why it has no locks.
- [LLD: Design an Elevator System](../../interviews/elevator-system/README.md) — JS version: the event loop is already a single writer, so commands and `tick()` need no locks (see [async-await-and-timers](async-await-and-timers.md)).
