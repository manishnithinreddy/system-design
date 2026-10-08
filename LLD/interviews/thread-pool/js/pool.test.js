'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { ResourcePool, TimeoutError, createLimiter } = require('./pool');

function fakeDb() {
  let seq = 0;
  const db = { alive: 0, maxAlive: 0, closed: [] };
  db.create = async () => {
    db.alive++;
    db.maxAlive = Math.max(db.maxAlive, db.alive);
    return { id: ++seq, broken: false };
  };
  db.validate = async (c) => !c.broken;
  db.destroy = async (c) => { db.alive--; db.closed.push(c.id); };
  return db;
}
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };
const tick = () => new Promise((r) => setImmediate(r));

test('acquire/release reuses the same resource', async () => {
  const db = fakeDb();
  const pool = new ResourcePool({ ...db, max: 3 });
  const a = await pool.acquire();
  pool.release(a);
  const b = await pool.acquire();
  assert.equal(b, a);
  assert.equal(pool.stats.created, 1);
  pool.release(b);
  pool.release(b);                                   // double release is ignored
  assert.equal(pool.idleCount, 1);
});

test('never more than max; a waiter times out', async () => {
  const db = fakeDb();
  const pool = new ResourcePool({ ...db, max: 2, acquireTimeoutMs: 30 });
  const a = await pool.acquire();
  await pool.acquire();
  await assert.rejects(pool.acquire(), TimeoutError);
  assert.equal(db.maxAlive, 2);
  assert.equal(pool.stats.timeouts, 1);
  assert.equal(pool.pending, 0, 'timed-out waiter left the line');
  pool.release(a);
  assert.equal((await pool.acquire()).id, a.id);
});

test('waiters are served first-in, first-out', async () => {
  const pool = new ResourcePool({ ...fakeDb(), max: 1 });
  const held = await pool.acquire();
  const order = [];
  const all = ['A', 'B', 'C'].map((name) => pool.use(async () => { order.push(name); await tick(); }));
  await tick();
  assert.equal(pool.pending, 3);
  pool.release(held);
  await Promise.all(all);
  assert.deepEqual(order, ['A', 'B', 'C']);
});

test('broken resource is destroyed and replaced on acquire', async () => {
  const db = fakeDb();
  const pool = new ResourcePool({ ...db, max: 2 });
  const a = await pool.acquire();
  pool.release(a);
  a.broken = true;
  const b = await pool.acquire();
  assert.notEqual(b.id, a.id);
  assert.deepEqual(db.closed, [a.id]);
  assert.equal(pool.stats.broken, 1);
  assert.equal(pool.size, 1, 'slot reused');
});

test('discard() frees the slot for a waiter', async () => {
  const db = fakeDb();
  const pool = new ResourcePool({ ...db, max: 1 });
  const a = await pool.acquire();
  const waiting = pool.acquire();
  await tick();
  await pool.discard(a);                             // e.g. query failed with "connection reset"
  const b = await waiting;
  assert.notEqual(b.id, a.id);
  assert.equal(db.maxAlive, 1, 'old one closed before the new one was created');
});

test('stress: 200 concurrent users, max 4, nothing lost', async () => {
  const db = fakeDb();
  const pool = new ResourcePool({ ...db, max: 4, acquireTimeoutMs: 5000 });
  let inside = 0; let maxInside = 0;
  await Promise.all(Array.from({ length: 200 }, (_, i) => pool.use(async (c) => {
    maxInside = Math.max(maxInside, ++inside);
    if (i % 37 === 0) c.broken = true;
    await tick();
    inside--;
  })));
  assert.ok(maxInside <= 4);
  assert.ok(db.maxAlive <= 4);
  assert.equal(pool.idleCount, pool.size);
  assert.equal(db.alive, pool.size);
  await pool.close();
  assert.equal(db.alive, 0);
});

test('limiter runs at most n tasks at once, in order', async () => {
  const limit = createLimiter(2);
  const gates = [deferred(), deferred(), deferred(), deferred()];
  const started = [];
  const results = gates.map((g, i) => limit(async () => { started.push(i); await g.promise; return i * 10; }));
  await tick();
  assert.deepEqual(started, [0, 1]);
  assert.equal(limit.active, 2);
  assert.equal(limit.queued, 2);
  gates[1].resolve();
  await tick();
  assert.deepEqual(started, [0, 1, 2], 'a finished slot lets the NEXT queued task in');
  gates.forEach((g) => g.resolve());
  assert.deepEqual(await Promise.all(results), [0, 10, 20, 30]);
});

test('limiter: a failing task rejects its own promise and frees its slot', async () => {
  const limit = createLimiter(1);
  const bad = limit(() => { throw new Error('boom'); });
  const good = limit(async () => 'ok');
  await assert.rejects(bad, /boom/);
  assert.equal(await good, 'ok');
  await tick();                                      // the slot is freed in .finally(), just after resolve
  assert.equal(limit.active, 0);
});
