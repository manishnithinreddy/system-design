'use strict';

// Monotonic clock in milliseconds (never jumps back when the wall clock is adjusted).
const systemClock = { now: () => performance.now() };

/** Test clock: time moves only when advance() is called. */
class FakeClock {
  constructor() { this.t = 0; }
  now() { return this.t; }
  advance(ms) { this.t += ms; }
}

/*
 * No locks anywhere below: Node runs JavaScript on ONE thread (the event loop), and
 * tryAcquire() has no `await` inside, so it always runs start-to-finish without interruption.
 * See LLD/libraries/js/event-loop-and-concurrency.md.
 */

class TokenBucketLimiter {
  constructor({ limit, windowMs }, clock = systemClock) {
    this.capacity = limit;
    this.tokensPerMs = limit / windowMs;
    this.clock = clock;
    this.tokens = limit;
    this.lastRefill = clock.now();
  }

  tryAcquire() {
    const now = this.clock.now();
    const elapsed = now - this.lastRefill;
    if (elapsed > 0) {
      this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.tokensPerMs);
      this.lastRefill = now;
    }
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return true;
    }
    return false;
  }
}

class FixedWindowLimiter {
  constructor({ limit, windowMs }, clock = systemClock) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.clock = clock;
    this.window = Math.floor(clock.now() / windowMs);
    this.count = 0;
  }

  tryAcquire() {
    const window = Math.floor(this.clock.now() / this.windowMs);
    if (window !== this.window) {
      this.window = window;
      this.count = 0;
    }
    if (this.count < this.limit) {
      this.count++;
      return true;
    }
    return false;
  }
}

class SlidingWindowLogLimiter {
  constructor({ limit, windowMs }, clock = systemClock) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.clock = clock;
    this.log = []; // timestamps, oldest first
    this.head = 0; // index of oldest live entry (avoids O(n) Array.shift)
  }

  tryAcquire() {
    const now = this.clock.now();
    while (this.head < this.log.length && now - this.log[this.head] >= this.windowMs) {
      this.head++;
    }
    if (this.head > 1024 && this.head > this.log.length / 2) {
      this.log = this.log.slice(this.head); // compact occasionally
      this.head = 0;
    }
    if (this.log.length - this.head < this.limit) {
      this.log.push(now);
      return true;
    }
    return false;
  }
}

class SlidingWindowCounterLimiter {
  constructor({ limit, windowMs }, clock = systemClock) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.clock = clock;
    this.window = Math.floor(clock.now() / windowMs);
    this.current = 0;
    this.previous = 0;
  }

  tryAcquire() {
    const now = this.clock.now();
    const window = Math.floor(now / this.windowMs);
    if (window !== this.window) {
      this.previous = window === this.window + 1 ? this.current : 0;
      this.current = 0;
      this.window = window;
    }
    const elapsedInWindow = now - window * this.windowMs;
    const estimate = this.previous * (1 - elapsedInWindow / this.windowMs) + this.current;
    if (estimate < this.limit) {
      this.current++;
      return true;
    }
    return false;
  }
}

const ALGORITHMS = {
  TOKEN_BUCKET: TokenBucketLimiter,
  FIXED_WINDOW: FixedWindowLimiter,
  SLIDING_WINDOW_LOG: SlidingWindowLogLimiter,
  SLIDING_WINDOW_COUNTER: SlidingWindowCounterLimiter,
};

/** Factory: pick the class from config.algorithm. */
function createLimiter(config, clock = systemClock) {
  const Impl = ALGORITHMS[config.algorithm];
  if (!Impl) throw new Error(`Unknown algorithm: ${config.algorithm}`);
  if (!(config.limit > 0) || !(config.windowMs > 0)) throw new Error('limit and windowMs must be > 0');
  return new Impl(config, clock);
}

/** One limiter per key, created lazily; idle keys are evicted so the Map doesn't grow forever. */
class KeyedRateLimiter {
  constructor({ configFor, idleTimeoutMs = 10 * 60_000, clock = systemClock, backgroundEviction = true }) {
    this.configFor = configFor;
    this.idleTimeoutMs = idleTimeoutMs;
    this.clock = clock;
    this.entries = new Map(); // key -> { limiter, lastAccess }
    if (backgroundEviction) {
      this.timer = setInterval(() => this.evictIdle(), Math.max(1, idleTimeoutMs / 2));
      this.timer.unref(); // don't keep the process alive just for cleanup
    }
  }

  tryAcquire(key) {
    const now = this.clock.now();
    let entry = this.entries.get(key);
    if (!entry) {
      entry = { limiter: createLimiter(this.configFor(key), this.clock), lastAccess: now };
      this.entries.set(key, entry);
    }
    entry.lastAccess = now;
    return entry.limiter.tryAcquire();
  }

  evictIdle() {
    const now = this.clock.now();
    for (const [key, entry] of this.entries) {
      if (now - entry.lastAccess > this.idleTimeoutMs) this.entries.delete(key);
    }
  }

  get size() { return this.entries.size; }

  close() { if (this.timer) clearInterval(this.timer); }
}

module.exports = {
  systemClock,
  FakeClock,
  TokenBucketLimiter,
  FixedWindowLimiter,
  SlidingWindowLogLimiter,
  SlidingWindowCounterLimiter,
  createLimiter,
  KeyedRateLimiter,
};
