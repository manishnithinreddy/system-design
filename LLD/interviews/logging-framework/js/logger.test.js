'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  LEVELS, LoggerContext, withContext, patternLayout, jsonLayout, redacting, ArrayAppender, BufferedAppender,
} = require('./logger');

const T0 = Date.parse('2026-10-08T03:30:00.000Z');
function setup() {
  const ctx = new LoggerContext({ now: () => T0 });
  const list = new ArrayAppender();
  ctx.root.appenders.push(list);
  return { ctx, list };
}
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };

test('levels, hierarchy inheritance, override and runtime change', () => {
  const { ctx, list } = setup();
  const db = ctx.getLogger('com.shop.db.Pool');
  assert.equal(db.parent.name, 'com.shop.db');
  db.debug('hidden'); db.info('shown');
  ctx.setLevel('com.shop', LEVELS.WARN);
  db.info('hidden by com.shop=WARN');
  ctx.setLevel('com.shop.db', LEVELS.DEBUG);
  db.debug('query took {} ms', 12);
  ctx.setLevel('com.shop.db', null);
  db.debug('hidden again');
  assert.deepEqual(list.messages, ['shown', 'query took 12 ms']);
});

test('additivity stops events at a logger with additive = false', () => {
  const { ctx, list } = setup();
  const audit = new ArrayAppender();
  const pay = ctx.getLogger('com.shop.payment');
  pay.appenders.push(audit);
  pay.additive = false;
  ctx.getLogger('com.shop.payment.Stripe').info('refund');
  assert.deepEqual(audit.messages, ['refund']);
  assert.deepEqual(list.messages, []);
});

test('disabled level never formats arguments or calls the supplier', () => {
  const { ctx } = setup();
  let calls = 0;
  const expensive = { toString() { calls++; return 'big'; } };
  const log = ctx.getLogger('x');
  log.debug('state {}', expensive);
  log.debug(() => { calls++; return 'built'; });
  assert.equal(calls, 0);
  log.info('state {}', expensive);
  assert.equal(calls, 1);
});

test('{} substitution edge cases and Error as last argument', () => {
  const { ctx, list } = setup();
  const log = ctx.getLogger('x');
  const boom = new Error('boom');
  log.info('a={} b={}', 1);
  log.info('a={}', 1, 2);
  log.info('failed for {}', 'alice', boom);
  log.info('cause: {}', boom);
  assert.deepEqual(list.messages, ['a=1 b={}', 'a=1', 'failed for alice', 'cause: Error: boom']);
  assert.equal(list.events[2].err, boom);
  assert.equal(list.events[3].err, null);
});

test('AsyncLocalStorage context follows each request across awaits', async () => {
  const { ctx, list } = setup();
  const log = ctx.getLogger('web');
  const tick = () => new Promise((r) => setImmediate(r));
  const handle = (id) => withContext({ requestId: id }, async () => {
    log.info('start'); await tick(); log.info('end');
  });
  await Promise.all([handle('r-1'), handle('r-2')]);   // interleaved: start, start, end, end
  assert.deepEqual(list.events.map((e) => `${e.msg}:${e.context.requestId}`), ['start:r-1', 'start:r-2', 'end:r-1', 'end:r-2']);
  log.info('outside');
  assert.deepEqual(list.events.at(-1).context, {});
});

test('pattern and JSON layouts; JSON stays one line and escapes; redaction', () => {
  const { ctx, list } = setup();
  withContext({ requestId: 'r-9' }, () => ctx.getLogger('auth').warn('say "{}" password={}', 'hi\\there\nnext', 'hunter2'));
  const e = list.events[0];
  assert.equal(patternLayout(e), '2026-10-08T03:30:00.000Z WARN  auth - say "hi\\there\nnext" password=hunter2 {"requestId":"r-9"}');
  const json = jsonLayout(e);
  assert.ok(!json.includes('\n'));
  assert.deepEqual(JSON.parse(json).msg, 'say "hi\\there\nnext" password=hunter2');   // round-trips exactly
  assert.ok(redacting(jsonLayout)(e).includes('password=***'));
  assert.ok(!redacting(patternLayout)(e).includes('hunter2'));
});

test('buffered appender writes batches in order and flushes on close', async () => {
  const { ctx } = setup();
  const writes = [];
  const app = new BufferedAppender((lines) => { writes.push(lines); }, { layout: (e) => e.msg, capacity: 100 });
  const log = ctx.getLogger('bulk');
  log.additive = false; log.appenders.push(app);
  for (let i = 0; i < 5; i++) log.info('{}', i);
  assert.equal(writes.length, 0, 'nothing written synchronously');
  await ctx.close();
  assert.deepEqual(writes, [['0', '1', '2', '3', '4']], 'one batch, in order');
});

test('slow sink: drop-below-warn when 80% full, drop everything when full, count drops', async () => {
  const { ctx } = setup();
  const gate = deferred(); const written = [];
  const app = new BufferedAppender(async (lines) => { await gate.promise; written.push(...lines); },
    { layout: (e) => e.msg, capacity: 10, policy: 'drop-below-warn' });
  const log = ctx.getLogger('async');
  log.additive = false; log.level = LEVELS.TRACE; log.appenders.push(app);
  log.info('first');
  await new Promise((r) => setImmediate(r));          // first batch is now "in flight", stuck on the gate
  for (let i = 0; i < 8; i++) log.debug('d{}', i);    // buffer 8/10
  log.info('dropped info');
  log.warn('w1'); log.error('e1');                    // 10/10
  log.error('dropped: buffer full');
  assert.equal(app.dropped, 2);
  gate.resolve();
  await app.close();
  assert.deepEqual(written.slice(-2), ['w1', 'e1']);
  assert.equal(written.length, 11);
});
