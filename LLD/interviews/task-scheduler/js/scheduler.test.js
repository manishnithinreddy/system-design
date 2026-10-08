'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { Scheduler, Misfire, backoffMs } = require('./scheduler');

const fakeClock = (start = 0) => { let t = start; return { now: () => t, set: (v) => { t = v; }, advance: (d) => { t += d; } }; };

test('runs in time order, FIFO among equal times', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now });
  const ran = [];
  s.schedule('late', () => ran.push('late'), { delayMs: 900 });
  for (let i = 0; i < 10; i++) s.schedule(`t${i}`, () => ran.push(i), { delayMs: 500 });
  c.set(1000);
  await s.runDue();
  assert.deepEqual(ran, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 'late']);
});

test('priority wins among tasks that are already due', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now });
  const ran = [];
  s.schedule('report', () => ran.push('report'), { delayMs: 100 });
  s.schedule('page', () => ran.push('page'), { delayMs: 200, priority: 10 });
  c.set(300);
  await s.runDue();
  assert.deepEqual(ran, ['page', 'report']);
});

test('cancel is lazy and stops a recurring task', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now });
  let runs = 0;
  const t = s.schedule('poll', () => { runs++; }, { delayMs: 100, fixedRateMs: 100 });
  c.set(100); await s.runDue();
  assert.equal(t.cancel(), true);
  assert.equal(t.cancel(), false);
  c.set(10_000);
  assert.equal(await s.runDue(), 0);
  assert.equal(runs, 1);
});

test('fixed rate plans from the planned start, fixed delay from the finish', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now });
  const slow = () => c.advance(3000); // each run "takes" 3 s
  const rate = s.schedule('rate', slow, { delayMs: 0, fixedRateMs: 10_000 });
  const delay = s.schedule('delay', slow, { delayMs: 0, fixedDelayMs: 10_000 });
  await s.runDue(); // rate runs 0..3 s, then delay runs 3..6 s
  assert.equal(rate.runAt, 10_000);
  assert.equal(delay.runAt, 16_000);
});

test('async failures retry with exponential backoff, then dead-letter', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now, random: () => 1 });
  let attempts = 0;
  const t = s.schedule('webhook', async () => { attempts++; throw new Error('HTTP 503'); },
    { retry: { maxAttempts: 4, baseMs: 1000, maxMs: 60_000 } });
  const waits = [];
  await s.runDue();
  while (t.state === 'SCHEDULED') { waits.push(t.runAt - c.now()); c.set(t.runAt); await s.runDue(); }
  assert.deepEqual(waits, [1000, 2000, 4000]);
  assert.equal(attempts, 4);
  assert.equal(t.state, 'DEAD');
  assert.equal(s.deadLetters[0].name, 'webhook');
  assert.equal(backoffMs(3, { baseMs: 1000, maxMs: 60_000 }, () => 0.25), 1000); // full jitter
});

test('misfire: FIRE_ONCE_NOW collapses missed runs, SKIP jumps to the next slot', async () => {
  const c = fakeClock(); const s = new Scheduler({ now: c.now });
  let fired = 0; let skipped = 0;
  const a = s.schedule('fire', () => { fired++; }, { delayMs: 10_000, fixedRateMs: 10_000 });
  const b = s.schedule('skip', () => { skipped++; }, { delayMs: 10_000, fixedRateMs: 10_000, misfire: Misfire.SKIP });
  c.set(35_000); // down for 35 s
  await s.runDue();
  assert.equal(fired, 1); assert.equal(a.runAt, 45_000);
  assert.equal(skipped, 0); assert.equal(b.runAt, 40_000);
});

test('start(): adding an earlier task re-arms the single timer', async () => {
  const s = new Scheduler();
  s.start();
  s.schedule('far', () => {}, { delayMs: 10_000 });
  const t0 = Date.now();
  const ran = new Promise((resolve) => s.schedule('soon', resolve, { delayMs: 30 }));
  const timeout = new Promise((_, reject) => setTimeout(() => reject(new Error('timer was not re-armed')), 1000).unref());
  await Promise.race([ran, timeout]);
  assert.ok(Date.now() - t0 < 1000);
  await s.shutdown();
});
