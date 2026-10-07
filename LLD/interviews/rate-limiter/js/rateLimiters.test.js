'use strict';

// Run: node --test     (uses Node's built-in test runner, no npm install needed)
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  FakeClock, createLimiter, KeyedRateLimiter,
} = require('./rateLimiters');

const countAllowed = (fn, n) => { let ok = 0; for (let i = 0; i < n; i++) if (fn()) ok++; return ok; };

test('token bucket: burst up to capacity, then refills', () => {
  const clock = new FakeClock();
  const rl = createLimiter({ algorithm: 'TOKEN_BUCKET', limit: 10, windowMs: 1000 }, clock);
  assert.equal(countAllowed(() => rl.tryAcquire(), 20), 10);
  clock.advance(100);
  assert.equal(countAllowed(() => rl.tryAcquire(), 5), 1);
  clock.advance(10_000);
  assert.equal(countAllowed(() => rl.tryAcquire(), 20), 10);
});

test('fixed window: 2x limit can pass around a boundary (known weakness)', () => {
  const clock = new FakeClock();
  const rl = createLimiter({ algorithm: 'FIXED_WINDOW', limit: 5, windowMs: 1000 }, clock);
  clock.advance(999);
  const a = countAllowed(() => rl.tryAcquire(), 5);
  clock.advance(2);
  const b = countAllowed(() => rl.tryAcquire(), 5);
  assert.equal(a + b, 10);
});

test('sliding window log: never more than limit in any window', () => {
  const clock = new FakeClock();
  const rl = createLimiter({ algorithm: 'SLIDING_WINDOW_LOG', limit: 5, windowMs: 1000 }, clock);
  clock.advance(999);
  const a = countAllowed(() => rl.tryAcquire(), 5);
  clock.advance(2);
  const b = countAllowed(() => rl.tryAcquire(), 5);
  assert.equal(a + b, 5);
  clock.advance(1000);
  assert.equal(countAllowed(() => rl.tryAcquire(), 10), 5);
});

test('sliding window counter: weights the previous window', () => {
  const clock = new FakeClock();
  const rl = createLimiter({ algorithm: 'SLIDING_WINDOW_COUNTER', limit: 10, windowMs: 1000 }, clock);
  assert.equal(countAllowed(() => rl.tryAcquire(), 10), 10);
  clock.advance(1250); // previous weighted 0.75 -> 7.5 used
  assert.equal(countAllowed(() => rl.tryAcquire(), 10), 3);
});

test('keyed limiter: per-key isolation, tiers, and idle eviction', () => {
  const clock = new FakeClock();
  const keyed = new KeyedRateLimiter({
    clock,
    backgroundEviction: false,
    idleTimeoutMs: 5 * 60_000,
    configFor: (k) => ({ algorithm: 'TOKEN_BUCKET', limit: k.startsWith('premium:') ? 100 : 3, windowMs: 60_000 }),
  });
  assert.equal(countAllowed(() => keyed.tryAcquire('free:alice'), 10), 3);
  assert.equal(countAllowed(() => keyed.tryAcquire('free:bob'), 10), 3);
  assert.equal(countAllowed(() => keyed.tryAcquire('premium:carol'), 200), 100);

  clock.advance(4 * 60_000);
  keyed.tryAcquire('free:bob');
  clock.advance(2 * 60_000);
  keyed.evictIdle();
  assert.equal(keyed.size, 1);
});

test('the one race Node CAN have: await between check and update', async () => {
  // Simulates a limiter backed by a remote store (e.g. Redis) done the WRONG way:
  // read count, await, then write. Concurrent requests all read the same old value.
  const store = { count: 0 };
  const limit = 5;
  async function naiveTryAcquire() {
    const current = store.count;                        // read
    await new Promise((r) => setImmediate(r));          // network round trip
    if (current < limit) { store.count = current + 1; return true; } // write
    return false;
  }
  const results = await Promise.all(Array.from({ length: 20 }, naiveTryAcquire));
  const allowed = results.filter(Boolean).length;
  assert.ok(allowed > limit, `expected the race to let more than ${limit} through, got ${allowed}`);
  // Fix: make check+update ONE atomic operation in the store (Redis INCR or a Lua script). See L6-staff.md.
});
