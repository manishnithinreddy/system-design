'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { KeyValueStore, AppendOnlyLog, execute } = require('./kvstore');

const run = (store, ...lines) => lines.map((l) => execute(store, l));

test('classic nested transaction sequence', () => {
  const s = new KeyValueStore();
  assert.deepEqual(
    run(s, 'BEGIN', 'SET a 10', 'GET a', 'BEGIN', 'SET a 20', 'GET a', 'ROLLBACK', 'GET a', 'COMMIT', 'GET a', 'COMMIT'),
    ['OK', 'OK', '10', 'OK', 'OK', '20', 'OK', '10', 'OK', '10', 'NO TRANSACTION'],
  );
});

test('count stays exact through rollback', () => {
  const s = new KeyValueStore();
  assert.deepEqual(run(s, 'SET a 1', 'SET b 1', 'BEGIN', 'DELETE a', 'COUNT 1', 'ROLLBACK', 'COUNT 1'), ['OK', 'OK', 'OK', '1', '1', 'OK', '2']);
});

test('outer rollback undoes committed nested work', () => {
  const s = new KeyValueStore();
  s.begin(); s.set('a', '1'); s.begin(); s.set('a', '2'); s.commit(); s.rollback();
  assert.equal(s.get('a'), null);
});

test('ttl expiry with fake clock', () => {
  let t = 0;
  const s = new KeyValueStore({ now: () => t });
  s.set('k', 'v'); s.expire('k', 30);
  assert.equal(s.ttl('k'), 30);
  t += 30_000;
  assert.equal(s.get('k'), null);
  assert.equal(s.count('v'), 0);
});

test('log replay, rollback not persisted, torn tail ignored', () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'kv-')), 'aof.log');
  let log = new AppendOnlyLog(file);
  const s = new KeyValueStore({ log });
  s.set('a', '1');
  s.begin(); s.set('b', '2'); s.commit();
  s.begin(); s.set('c', '3'); s.rollback();
  log.close();
  fs.appendFileSync(file, 'B\nS\td\t4\t9007199254740991\nS\te\t5\t90');  // crash mid-transaction
  log = new AppendOnlyLog(file);
  const r = new KeyValueStore({ log });
  assert.equal(r.get('a'), '1');
  assert.equal(r.get('b'), '2');
  assert.equal(r.get('c'), null);
  assert.equal(r.get('d'), null);
  log.close();
});

test('random ops match a copy-on-BEGIN model', () => {
  let seed = 9; const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % n; };
  const s = new KeyValueStore();
  let model = new Map(); const snaps = [];
  for (let i = 0; i < 10_000; i++) {
    const k = `k${rnd(6)}`; const v = `v${rnd(3)}`;
    switch (rnd(7)) {
      case 0: case 1: s.set(k, v); model.set(k, v); break;
      case 2: assert.equal(s.delete(k), model.delete(k)); break;
      case 3: s.begin(); snaps.push(new Map(model)); break;
      case 4: if (snaps.length) { s.rollback(); model = snaps.pop(); } break;
      case 5: if (snaps.length) { s.commit(); snaps.pop(); } break;
      default:
        assert.equal(s.get(k), model.get(k) ?? null);
        assert.equal(s.count(v), [...model.values()].filter((x) => x === v).length);
    }
  }
});
