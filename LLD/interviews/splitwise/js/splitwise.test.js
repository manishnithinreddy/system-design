'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseRupees, formatINR, split, simplifyDebts, Group } = require('./splitwise');

test('parse and format rupees', () => {
  assert.equal(parseRupees('420.5'), 42050);
  assert.equal(parseRupees('100'), 10000);
  assert.throws(() => parseRupees('1.234'));
  assert.equal(formatINR(42050), '₹420.50');
});

test('equal split: ₹100 / 3 sums exactly', () => {
  assert.deepEqual(split(10000, { type: 'equal', users: ['c', 'a', 'b'] }), { a: 3334, b: 3333, c: 3333 });
});

test('percent (basis points) and shares', () => {
  const p = split(1001, { type: 'percent', basisPoints: { a: 3333, b: 3333, c: 3334 } });
  assert.equal(p.a + p.b + p.c, 1001);
  assert.equal(p.c, 334);
  assert.deepEqual(split(100000, { type: 'shares', shares: { a1: 2, a2: 2, kid: 1 } }), { a1: 40000, a2: 40000, kid: 20000 });
  assert.throws(() => split(1000, { type: 'exact', paise: { a: 600, b: 300 } }), /add up/);
});

function goa() {
  const g = new Group('goa', ['asha', 'bala', 'chitra']);
  g.addExpense({ requestId: 'r1', paidBy: 'asha', total: parseRupees('9000'), spec: { type: 'equal', users: ['asha', 'bala', 'chitra'] }, description: 'Hotel' });
  g.addExpense({ requestId: 'r2', paidBy: 'bala', total: parseRupees('1500'), spec: { type: 'exact', paise: { bala: 50000, chitra: 100000 } }, description: 'Dinner' });
  return g;
}

test('balances, idempotency, delete as reversal', () => {
  const g = goa();
  assert.deepEqual(g.balances(), { asha: 600000, bala: -200000, chitra: -400000 });
  g.addExpense({ requestId: 'r1', paidBy: 'asha', total: parseRupees('9000'), spec: { type: 'equal', users: ['asha', 'bala', 'chitra'] }, description: 'retry' });
  assert.equal(g.history().length, 2);
  g.deleteExpense(g.history()[1].id);
  assert.deepEqual(g.balances(), { asha: 600000, bala: -300000, chitra: -300000 });
  assert.equal(g.history().length, 3);
});

test('simplify debts settles everyone', () => {
  const t = simplifyDebts(goa().balances());
  assert.deepEqual(t, [{ from: 'chitra', to: 'asha', amount: 400000 }, { from: 'bala', to: 'asha', amount: 200000 }]);
});

test('random expenses: sum zero, simplified transfers settle all', () => {
  let seed = 3; const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % n; };
  for (let round = 0; round < 200; round++) {
    const people = Array.from({ length: 2 + rnd(7) }, (_, i) => `u${i}`);
    const g = new Group('g', people);
    for (let e = 0; e < 15; e++) {
      const shares = {};
      for (const p of people) if (rnd(2)) shares[p] = 1 + rnd(4);
      if (!Object.keys(shares).length) shares[people[0]] = 1;
      g.addExpense({ requestId: `r${e}`, paidBy: people[rnd(people.length)], total: 1 + rnd(500000), spec: { type: 'shares', shares } });
    }
    const bal = g.balances();
    assert.equal(Object.values(bal).reduce((a, b) => a + b, 0), 0);
    const after = { ...bal };
    const transfers = simplifyDebts(bal);
    assert.ok(transfers.length <= people.length - 1);
    for (const t of transfers) { after[t.from] += t.amount; after[t.to] -= t.amount; }
    assert.ok(Object.values(after).every((v) => v === 0));
  }
});
