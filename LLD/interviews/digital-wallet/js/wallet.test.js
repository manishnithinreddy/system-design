'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { Wallet, formatPaise } = require('./wallet');

const rupees = (r) => r * 100;
const tick = () => new Promise((resolve) => setImmediate(resolve));   // a real pause, like a network call

/** Tiny deterministic random generator (mulberry32), so a failing seed can be replayed. */
function seeded(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function walletWith(balances, options) {
  const w = new Wallet(options);
  return Promise.all(Object.entries(balances).map(([id, r]) => {
    w.open(id);
    return r > 0 ? w.addMoney(`seed-${id}`, id, rupees(r)) : null;
  })).then(() => w);
}

function assertConsistent(w) {
  let total = 0;
  for (const a of w.accounts()) {
    assert.equal(a.balance, w.sumOfEntries(a.id), `cached balance == sum of entries for ${a.id}`);
    if (a.type !== 'SYSTEM') assert.ok(a.balance >= 0, `${a.id} never negative`);
    total += a.balance;
  }
  assert.equal(total, 0, 'all accounts incl. SYS:BANK add up to zero');
}

test('transfer moves money and writes two entries that sum to zero', async () => {
  const w = await walletWith({ alice: 1000, bob: 0 });
  const t = await w.transfer('pay-1', 'alice', 'bob', rupees(200));
  assert.equal(t.status, 'COMPLETED');
  assert.equal(w.balance('alice'), rupees(800));
  assert.equal(w.balance('bob'), rupees(200));
  const entries = w.entriesForTxn(t.id);
  assert.deepEqual(entries.map((e) => [e.accountId, e.amount]), [['alice', -20000], ['bob', 20000]]);
  assert.ok(Object.isFrozen(entries[0]), 'entries are immutable');
  assertConsistent(w);
});

test('insufficient funds is rejected and writes nothing', async () => {
  const w = await walletWith({ alice: 100, bob: 0 });
  const before = w.entryCount();
  const t = await w.transfer('pay-1', 'alice', 'bob', rupees(200));
  assert.equal(t.status, 'REJECTED');
  assert.match(t.reason, /insufficient/);
  assert.equal(w.entryCount(), before);
});

test('a retry with the same key returns the same txn; a different request with that key is refused', async () => {
  const w = await walletWith({ alice: 1000, bob: 0 });
  const first = await w.transfer('app-req-42', 'alice', 'bob', rupees(200));
  const again = await w.transfer('app-req-42', 'alice', 'bob', rupees(200));
  assert.equal(again, first);
  assert.equal(w.balance('bob'), rupees(200));
  await assert.rejects(w.transfer('app-req-42', 'alice', 'bob', rupees(500)), { code: 'KEY_REUSED' });
});

test('same key sent 10 times at once (slow risk check) creates one transfer', async () => {
  const w = await walletWith({ alice: 1000, bob: 0 }, { riskCheck: tick });
  const results = await Promise.all(Array.from({ length: 10 }, () => w.transfer('dup', 'alice', 'bob', rupees(200))));
  assert.equal(new Set(results.map((t) => t.id)).size, 1);
  assert.equal(w.balance('bob'), rupees(200));
  assert.equal(w.entriesForTxn(results[0].id).length, 2);
});

test('two different transfers racing past an await cannot overdraw (check happens after the await)', async () => {
  const w = await walletWith({ alice: 300, bob: 0, carol: 0 }, { riskCheck: tick });
  const [a, b] = await Promise.all([
    w.transfer('k1', 'alice', 'bob', rupees(200)),
    w.transfer('k2', 'alice', 'carol', rupees(200)),
  ]);
  assert.deepEqual([a.status, b.status].sort(), ['COMPLETED', 'REJECTED']);
  assert.equal(w.balance('alice'), rupees(100));
  assertConsistent(w);
});

test('refunds add reversing entries; over-refund is rejected; history is never edited', async () => {
  const w = await walletWith({ alice: 1000, swiggy: 0 });
  const pay = await w.transfer('order-9', 'alice', 'swiggy', rupees(500));
  const r1 = await w.refund('refund-1', pay.id, rupees(200));
  assert.equal(r1.status, 'COMPLETED');
  assert.deepEqual(w.entriesForTxn(r1.id).map((e) => [e.accountId, e.amount]), [['swiggy', -20000], ['alice', 20000]]);
  assert.equal(w.entriesForTxn(pay.id).length, 2, 'original entries still there');
  assert.equal((await w.refund('refund-2', pay.id, rupees(400))).status, 'REJECTED', 'only ₹300 left');
  assert.equal((await w.refund('refund-3', pay.id, rupees(300))).status, 'COMPLETED');
  assert.equal(w.balance('alice'), rupees(1000));
  assertConsistent(w);
});

test('property: money is conserved over 5,000 random concurrent transfers (seeded)', async () => {
  for (const seed of [1, 2, 3]) {
    const rand = seeded(seed);
    const names = ['u0', 'u1', 'u2', 'u3', 'u4', 'u5'];
    const w = await walletWith(Object.fromEntries(names.map((n) => [n, 10_000])), { riskCheck: () => (rand() < 0.5 ? tick() : undefined) });
    let ops = 0;
    for (let batch = 0; batch < 50; batch++) {        // 50 batches x 100 transfers in flight at once
      const inFlight = [];
      for (let i = 0; i < 100; i++) {
        const from = names[Math.floor(rand() * names.length)];
        const to = names[(names.indexOf(from) + 1 + Math.floor(rand() * (names.length - 1))) % names.length];
        const amount = 1 + Math.floor(rand() * rupees(4000));
        const key = rand() < 0.1 && ops > 0 ? `s${seed}-${ops - 1}` : `s${seed}-${ops}`;   // 10%: replay a key
        inFlight.push(w.transfer(key, from, to, amount).catch((e) => assert.equal(e.code, 'KEY_REUSED')));
        ops++;
      }
      await Promise.all(inFlight);
    }
    const users = names.reduce((s, n) => s + w.balance(n), 0);
    assert.equal(users, rupees(60_000), `seed ${seed}: total user money unchanged`);
    assertConsistent(w);
  }
});

test('why integer paise: floats drift, fractions are refused, BigInt is exact past 2^53', async () => {
  assert.notEqual(0.1 + 0.2, 0.3);
  assert.equal(0.1 + 0.2, 0.30000000000000004);
  assert.equal(10 + 20, 30, 'the same amounts in paise');
  const w = await walletWith({ alice: 10, bob: 0 });
  await assert.rejects(w.transfer('frac', 'alice', 'bob', 10.5), { code: 'BAD_AMOUNT' });
  await assert.rejects(w.transfer('huge', 'alice', 'bob', 2 ** 53), { code: 'BAD_AMOUNT' });
  assert.equal(2 ** 53 + 1, 2 ** 53, 'Number silently loses the last paisa here');
  assert.equal(2n ** 53n + 1n, 9007199254740993n, 'BigInt does not');
  assert.equal(formatPaise(12345678901), '₹12,34,56,789.01');
});
