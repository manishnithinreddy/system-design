'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { Broker, topicMatches, idempotent } = require('./pubsub');

const tick = () => new Promise((resolve) => setImmediate(resolve));   // a real pause, like I/O
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };

async function waitFor(cond, what) {
  const deadline = Date.now() + 5000;
  while (!cond()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for: ${what}`);
    await tick();
  }
}

/** A subscription stuck on message "0" with a queue of 5, then messages 1..19 published. */
async function stuck(overflow) {
  const broker = new Broker();
  const gate = deferred();
  const entered = deferred();
  const got = [];
  const sub = broker.subscribe('ticks', async (m) => { got.push(m.payload); entered.resolve(); await gate.promise; },
    { capacity: 5, overflow });
  const results = [await broker.publish('ticks', '0')];
  await entered.promise;
  for (let i = 1; i < 20; i++) results.push(await broker.publish('ticks', String(i)));
  gate.resolve();
  await sub.idle();
  const sum = (field) => results.reduce((n, r) => n + r[field], 0);
  return { got, sub, dropped: sum('dropped'), rejected: sum('rejected'), accepted: sum('accepted') };
}

test('fan-out: every subscription gets every message, in publish order', async () => {
  const broker = new Broker();
  const inboxes = [[], [], []];
  const subs = inboxes.map((inbox) => broker.subscribe('orders', async (m) => { inbox.push(m.payload); await tick(); }));
  for (let i = 0; i < 200; i++) assert.equal((await broker.publish('orders', String(i))).accepted, 3);
  await Promise.all(subs.map((s) => s.idle()));
  const expected = Array.from({ length: 200 }, (_, i) => String(i));
  for (const inbox of inboxes) assert.deepEqual(inbox, expected);
});

test('a stuck subscriber blocks neither the publisher nor the others', async () => {
  const broker = new Broker();
  const gate = deferred();
  const slow = [];
  const fast = [];
  broker.subscribe('orders', async (m) => { slow.push(m.payload); await gate.promise; });
  const fastSub = broker.subscribe('orders', async (m) => { fast.push(m.payload); });
  for (let i = 0; i < 50; i++) await broker.publish('orders', String(i));   // every await resolves: nobody is full
  await fastSub.idle();
  assert.equal(fast.length, 50);
  assert.equal(slow.length, 1, 'slow one is still on its first message');
  gate.resolve();
  await waitFor(() => slow.length === 50, 'slow catches up');
});

test('dropNewest drops exactly the overflow; dropOldest keeps the newest; reject tells the publisher', async () => {
  const newest = await stuck('dropNewest');
  assert.deepEqual(newest.got, ['0', '1', '2', '3', '4', '5']);
  assert.equal(newest.dropped, 14);
  assert.equal(newest.sub.stats.dropped, 14);

  const oldest = await stuck('dropOldest');
  assert.deepEqual(oldest.got, ['0', '15', '16', '17', '18', '19']);
  assert.equal(oldest.dropped, 14);
  assert.equal(oldest.accepted, 20);

  const rejected = await stuck('reject');
  assert.deepEqual(rejected.got, ['0', '1', '2', '3', '4', '5']);
  assert.equal(rejected.rejected, 14);
});

test('block: concurrent publishers into a tiny queue lose nothing and keep their own order', async () => {
  const broker = new Broker();
  const last = new Map();
  let outOfOrder = 0;
  let received = 0;
  const sub = broker.subscribe('events', async (m) => {
    const [pub, seq] = m.payload.split(':');
    if ((last.get(pub) ?? -1) !== Number(seq) - 1) outOfOrder++;
    last.set(pub, Number(seq));
    received++;
    await tick();
  }, { capacity: 4, overflow: 'block', blockTimeoutMs: 10_000 });
  const publisher = async (p) => {
    for (let i = 0; i < 500; i++) {
      const r = await broker.publish('events', `p${p}:${i}`);
      assert.equal(r.accepted, 1);
    }
  };
  await Promise.all([0, 1, 2, 3, 4].map(publisher));
  await sub.idle();
  assert.equal(received, 2500);
  assert.equal(outOfOrder, 0);
  assert.equal(sub.stats.dropped + sub.stats.rejected, 0);
});

test('block gives up after blockTimeoutMs and reports a rejection', async () => {
  const broker = new Broker();
  const gate = deferred();
  const sub = broker.subscribe('ticks', async () => { await gate.promise; }, { capacity: 1, overflow: 'block', blockTimeoutMs: 30 });
  await broker.publish('ticks', 'in-handler');
  await tick();
  await broker.publish('ticks', 'queued');
  const started = Date.now();
  const r = await broker.publish('ticks', 'too-many');
  const waited = Date.now() - started;
  gate.resolve();   // unblock before asserting, so a failure doesn't leave a handler waiting
  await sub.idle();
  assert.equal(r.rejected, 1);
  assert.ok(waited >= 25, `it really waited (${waited} ms)`);
});

test('nacks are retried, then the message goes to the dead-letter topic', async () => {
  const broker = new Broker();
  const dlq = [];
  broker.subscribe('orders.dlq', async (m) => { dlq.push(m); });
  const attempts = [];
  const sub = broker.subscribe('orders', async (m, { attempt }) => {
    attempts.push(attempt);
    throw new Error('cannot parse');
  }, { maxAttempts: 3, deadLetterTopic: 'orders.dlq' });
  await broker.publish('orders', 'poison', { key: 'o-1' });
  await sub.idle();
  await waitFor(() => dlq.length === 1, 'dead-lettered');
  assert.deepEqual(attempts, [1, 2, 3]);
  assert.equal(dlq[0].payload, 'poison');
  assert.equal(dlq[0].headers['dlq.attempts'], '3');
  assert.equal(dlq[0].headers['dlq.originalTopic'], 'orders');
  assert.equal(sub.stats.deadLettered, 1);
});

test('ack timeout redelivers; an idempotent consumer applies the side effect once', async () => {
  const broker = new Broker();
  let applied = 0;
  const apply = idempotent(async () => { applied++; });
  const sub = broker.subscribe('payments', async (m, ctx) => {
    await apply(m, ctx);
    if (ctx.attempt === 1) return new Promise(() => {});   // work done, but the ack never comes
  }, { ackTimeoutMs: 20 });
  await broker.publish('payments', 'credit 500', { key: 'acct-7' });
  await sub.idle();
  assert.equal(sub.stats.delivered, 2);
  assert.equal(sub.stats.redelivered, 1);
  assert.equal(applied, 1);
  assert.equal(apply.duplicates, 1);
});

test('consumer group with keys: one lane per key, per-key order, work shared', async () => {
  const broker = new Broker();
  const lastSeq = new Map();
  const lanesPerKey = new Map();
  const perLane = [0, 0, 0, 0];
  let violations = 0;
  const sub = broker.subscribe('orders', async (m, { lane }) => {
    for (let i = 0; i < (m.payload.length % 3); i++) await tick();   // uneven handler times
    const seq = Number(m.payload);
    if ((lastSeq.get(m.key) ?? -1) !== seq - 1) violations++;
    lastSeq.set(m.key, seq);
    (lanesPerKey.get(m.key) ?? lanesPerKey.set(m.key, new Set()).get(m.key)).add(lane);
    perLane[lane]++;
  }, { lanes: 4, capacity: 8 });
  const publisher = async (p) => {
    for (let seq = 0; seq < 100; seq++) for (let k = 0; k < 5; k++) await broker.publish('orders', String(seq), { key: `order-${p}-${k}` });
  };
  await Promise.all([0, 1, 2].map(publisher));   // 15 keys: not a multiple of 4 lanes, so round-robin can't fake it
  await sub.idle();
  assert.equal(violations, 0);
  assert.ok([...lanesPerKey.values()].every((s) => s.size === 1), 'each key stays on one lane');
  assert.ok(perLane.every((n) => n > 0), `all lanes did work: ${perLane}`);
  assert.equal(perLane.reduce((a, b) => a + b, 0), 1500);
});

test('wildcards: * is one word, # is zero or more', () => {
  assert.ok(topicMatches('orders.*', 'orders.created'));
  assert.ok(!topicMatches('orders.*', 'orders.eu.created'));
  assert.ok(topicMatches('orders.#', 'orders'));
  assert.ok(topicMatches('orders.#', 'orders.eu.created'));
  assert.ok(topicMatches('*.created', 'payments.created'));
  assert.ok(topicMatches('#', 'anything.at.all'));
  assert.ok(!topicMatches('orders.eu.*', 'orders.us.created'));
});

test('shutdown drains what was queued, then refuses new publishes', async () => {
  const broker = new Broker();
  let done = 0;
  broker.subscribe('jobs', async () => { await tick(); done++; }, { lanes: 2 });
  for (let i = 0; i < 100; i++) await broker.publish('jobs', `j${i}`);
  await broker.shutdown();
  assert.equal(done, 100);
  await assert.rejects(broker.publish('jobs', 'late'), /shutting down/);
});
