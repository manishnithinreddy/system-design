'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { VendingMachine, greedy, exact, total, UPI_TIMEOUT_MS } = require('./vending');

const WATER = { name: 'Water 1L', price: 2000 };
const CHIPS = { name: 'Masala chips', price: 1500 };
const CHIKKI = { name: 'Peanut chikki', price: 1400 };
const fakeClock = (t = 0) => ({ now: () => t, advance: (d) => { t += d; } });

function machine({ coins = {}, dispenser, clock = fakeClock() } = {}) {
  const m = new VendingMachine({ id: 'VM-T', dispenser, now: clock.now });
  m.restock('A1', WATER, 5); m.restock('A2', CHIPS, 5); m.restock('B1', CHIKKI, 5);
  for (const [d, n] of Object.entries(coins)) m.loadCoins(d, n);
  m.exitMaintenance();
  return m;
}
const take = (arr) => arr.splice(0); // empty a tray, returning what was in it

test('greedy fails on ₹6 from one ₹5 + three ₹2; exact finds 2+2+2 with fewest coins', () => {
  assert.equal(greedy(600, { COIN_5: 1, COIN_2: 3 }), null);
  assert.deepEqual(exact(600, { COIN_5: 1, COIN_2: 3 }), { COIN_2: 3 });
  assert.deepEqual(exact(3000, { COIN_20: 1, COIN_10: 3, COIN_5: 4 }), { COIN_20: 1, COIN_10: 1 });
  assert.equal(exact(300, { COIN_2: 5 }), null);
  assert.equal(exact(1000, { NOTE_10: 3 }), null, 'notes are never change');
});

test('happy path with change; machine uses exact change where greedy fails', async () => {
  const m = machine({ coins: { COIN_5: 1, COIN_2: 3 } });
  m.insert('COIN_20');
  assert.equal(await m.select('B1'), 'Take your Peanut chikki, change ₹6');
  assert.deepEqual(take(m.coinTray), ['COIN_2', 'COIN_2', 'COIN_2']);
  assert.deepEqual(take(m.productTray), [CHIKKI]);
  assert.equal(m.stock('B1'), 4);
  assert.equal(m.stateName, 'Idle');
});

test('change impossible: sale refused, money kept in escrow, cancel returns the same pieces', async () => {
  const m = machine();
  assert.match(m.insert('NOTE_100'), /^EXACT CHANGE ONLY/, 'dead-end note refused before accepting');
  m.insert('NOTE_10'); m.insert('NOTE_10');          // accepted: water ₹20 needs no change
  assert.match(await m.select('A2'), /^Cannot return ₹5 change/);
  assert.equal(m.stateName, 'HasMoney');
  m.cancel();
  assert.deepEqual(take(m.coinTray), ['NOTE_100', 'NOTE_10', 'NOTE_10']);
});

test('jam: refund, stock not decremented, slot out of use', async () => {
  const m = machine({ coins: { COIN_5: 2, COIN_1: 1 }, dispenser: async () => false });
  m.insert('NOTE_20');
  assert.equal(await m.select('A1'), 'Jam in A1: returned ₹20');
  assert.deepEqual(take(m.coinTray), ['NOTE_20']);
  assert.equal(m.stock('A1'), 5);
  m.insert('NOTE_20');
  assert.equal(await m.select('A1'), 'Water 1L sold out: choose another or cancel');
});

test('inactivity timeout refunds after 60 s', () => {
  const clock = fakeClock(); const m = machine({ clock });
  m.insert('COIN_10');
  clock.advance(59_999); assert.equal(m.tick(), '');
  clock.advance(1); assert.equal(m.tick(), 'No activity for 60 s: returned ₹10');
  assert.equal(m.stateName, 'Idle');
});

test('UPI: success dispenses once; duplicate and contradicting callbacks are ignored', async () => {
  const m = machine();
  m.payByUpi('A2'); const key = m.currentUpiKey;
  assert.equal(await m.onUpiResult(key, true), 'Take your Masala chips');
  assert.equal(await m.onUpiResult(key, true), 'Duplicate callback (PAID): ignored');
  assert.equal(await m.onUpiResult(key, false), 'Duplicate callback (PAID): ignored');
  assert.equal(m.productTray.length, 1);
  assert.equal(m.upiRevenue, 1500);
});

test('UPI: failure charges nothing; late success after timeout is refunded exactly once', async () => {
  const clock = fakeClock(); const m = machine({ clock });
  m.payByUpi('A1'); const k1 = m.currentUpiKey;
  assert.equal(await m.onUpiResult(k1, false), 'UPI payment failed: nothing charged');
  m.payByUpi('B1'); const k2 = m.currentUpiKey;
  clock.advance(UPI_TIMEOUT_MS); m.tick();
  assert.match(await m.onUpiResult(k2, true), /refunded ₹14 to UPI/);
  assert.equal(await m.onUpiResult(k2, true), 'Duplicate callback (REFUNDED): ignored');
  assert.deepEqual(m.upiRefunds, [k2]);
  assert.equal(m.productTray.length, 0);
});

test('while the motor runs (await), Dispensing refuses other presses: one item, one change', async () => {
  let release; const motor = new Promise((r) => { release = r; });
  const m = machine({ coins: { COIN_5: 2 }, dispenser: () => motor });
  m.insert('NOTE_20');
  const first = m.select('A2');                     // starts the motor, now awaiting
  assert.equal(m.stateName, 'Dispensing');
  assert.equal(await m.select('A2'), 'Busy: dispensing');
  assert.equal(m.insert('COIN_10'), 'Busy: dispensing: COIN_10 returned');
  release(true);
  assert.equal(await first, 'Take your Masala chips, change ₹5');
  assert.deepEqual(take(m.coinTray), ['COIN_10', 'COIN_5']);
  assert.equal(m.stock('A2'), 4);
});

test('technician operations only in maintenance; conservation over random sessions', async () => {
  const m0 = machine();
  assert.throws(() => m0.restock('A1', WATER, 1), /maintenance/);
  let seed = 7; const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % n; }; // tiny deterministic PRNG
  const pieces = ['COIN_1', 'COIN_2', 'COIN_5', 'COIN_10', 'COIN_20', 'NOTE_10', 'NOTE_20', 'NOTE_50', 'NOTE_100'];
  const PAISE = { COIN_1: 100, COIN_2: 200, COIN_5: 500, COIN_10: 1000, COIN_20: 2000, NOTE_10: 1000, NOTE_20: 2000, NOTE_50: 5000, NOTE_100: 10000 };
  for (let run = 0; run < 30; run++) {
    const m = machine({ coins: { COIN_1: 3, COIN_2: 3, COIN_5: 2, COIN_10: 2 }, dispenser: async () => rnd(10) > 0 });
    const before = total(m.coinBox); let inserted = 0;
    for (let step = 0; step < 60; step++) {
      const r = rnd(5);
      if (r < 2) { const d = pieces[rnd(pieces.length)]; inserted += PAISE[d]; m.insert(d); } else if (r < 4) await m.select(['A1', 'A2', 'B1'][rnd(3)]); else m.cancel();
      assert.ok(Object.values(m.coinBox).every((n) => n >= 0));
    }
    m.cancel();
    const handedBack = m.coinTray.reduce((s, d) => s + PAISE[d], 0);
    const growth = total(m.coinBox) - before;
    assert.equal(inserted, handedBack + growth, `run ${run}: cash conserved`);
    assert.equal(m.cashRevenue, growth, `run ${run}: box grew by exactly the revenue`);
  }
});
