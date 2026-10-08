'use strict';

// Vending machine, same design as the Java version:
//   - money is integer paise (₹1 = 100 paise); only coins are paid out as change
//   - State pattern: one object per state; unhandled events fall through to a polite refusal
//   - change-making: greedy (can fail) and exact (bounded dynamic programming over coin counts)
//   - the dispenser is async (the motor takes seconds); while we await it the machine is in
//     Dispensing, which refuses every other event. Node runs our code on one thread, so the only
//     interleaving points are `await`s: no locks needed, but the Dispensing state still is.

const DENOMS = Object.freeze({
  COIN_1: { paise: 100, coin: true }, COIN_2: { paise: 200, coin: true }, COIN_5: { paise: 500, coin: true },
  COIN_10: { paise: 1000, coin: true }, COIN_20: { paise: 2000, coin: true },
  NOTE_10: { paise: 1000, coin: false }, NOTE_20: { paise: 2000, coin: false },
  NOTE_50: { paise: 5000, coin: false }, NOTE_100: { paise: 10000, coin: false },
});
const CASH_TIMEOUT_MS = 60_000;
const UPI_TIMEOUT_MS = 120_000;

const rupees = (p) => (p % 100 === 0 ? `₹${p / 100}` : `₹${Math.floor(p / 100)}.${String(p % 100).padStart(2, '0')}`);
const total = (bag) => Object.entries(bag).reduce((s, [d, n]) => s + DENOMS[d].paise * n, 0);
const coinsLargestFirst = (avail) =>
  Object.keys(avail).filter((d) => DENOMS[d].coin && avail[d] > 0).sort((a, b) => DENOMS[b].paise - DENOMS[a].paise);

function greedy(amount, avail) {
  const out = {}; let left = amount;
  for (const d of coinsLargestFirst(avail)) {
    const take = Math.min(avail[d], Math.floor(left / DENOMS[d].paise));
    if (take > 0) { out[d] = take; left -= take * DENOMS[d].paise; }
  }
  return left === 0 ? out : null;
}

// best[a] = fewest coins making a (in ₹1 units) with the coin types seen so far, never more than we have.
function exact(amount, avail) {
  if (amount === 0) return {};
  const coins = coinsLargestFirst(avail);
  if (coins.length === 0 || amount % 100 !== 0) return null; // all our coins are whole rupees
  const target = amount / 100;
  let best = new Array(target + 1).fill(Infinity); best[0] = 0;
  const take = coins.map(() => new Array(target + 1).fill(0));
  coins.forEach((d, i) => {
    const v = DENOMS[d].paise / 100; const next = best.slice();
    for (let a = 0; a <= target; a++) {
      if (best[a] === Infinity) continue;
      for (let k = 1; k <= avail[d] && a + k * v <= target; k++) {
        if (best[a] + k < next[a + k * v]) { next[a + k * v] = best[a] + k; take[i][a + k * v] = k; }
      }
    }
    best = next;
  });
  if (best[target] === Infinity) return null;
  const out = {};
  for (let i = coins.length - 1, a = target; i >= 0; i--) {
    const k = take[i][a]; if (k > 0) out[coins[i]] = k; a -= k * DENOMS[coins[i]].paise / 100;
  }
  return out;
}

// ---- states: each overrides only what it handles ----
const base = {
  refusal() { return `Busy (${this.name})`; },
  insert(m, d) { return m._bounce(d, `${this.refusal()}: ${d} returned`); },
  select() { return this.refusal(); },
  payByUpi() { return this.refusal(); },
  cancel() { return 'Nothing to cancel'; },
  upiResult() { throw new Error('a PENDING UPI order exists only in AwaitingUpi'); },
  tick() { return ''; },
  enterMaintenance() { return 'Finish the current transaction first'; },
  exitMaintenance() { return 'Not in maintenance'; },
};
const state = (name, handlers) => Object.freeze(Object.assign(Object.create(base), { name }, handlers));
const S = {};
S.Idle = state('Idle', {
  insert: (m, d) => m._acceptCash(d), select: (m, slot) => m._priceCheck(slot),
  payByUpi: (m, slot) => m._startUpi(slot), enterMaintenance: (m) => m._moveTo(S.Maintenance, 'Maintenance mode'),
});
S.HasMoney = state('HasMoney', {
  insert: (m, d) => m._acceptCash(d), select: (m, slot) => m._sellForCash(slot),
  payByUpi: () => 'Cash inserted: buy with cash or cancel first', cancel: (m) => m._refundEscrow('Cancelled'),
  tick: (m) => (m._idleMs() >= CASH_TIMEOUT_MS ? m._refundEscrow('No activity for 60 s') : ''),
});
S.AwaitingUpi = state('AwaitingUpi', {
  refusal: () => 'Waiting for UPI payment (press cancel to stop)', cancel: (m) => m._abandonUpi('Cancelled'),
  upiResult: (m, o, ok) => (ok ? m._upiPaid(o) : m._upiFailed(o)),
  tick: (m) => (m._idleMs() >= UPI_TIMEOUT_MS ? m._abandonUpi('UPI not confirmed in 120 s') : ''),
});
S.Dispensing = state('Dispensing', { refusal: () => 'Busy: dispensing' });
S.SoldOut = state('SoldOut', { refusal: () => 'SOLD OUT', enterMaintenance: (m) => m._moveTo(S.Maintenance, 'Maintenance mode') });
S.Maintenance = state('Maintenance', { refusal: () => 'Out of service', exitMaintenance: (m) => m._moveTo(m._idleOrSoldOut(), 'Back in service') });

class VendingMachine {
  #state = S.Maintenance; #slots = new Map(); #box = {}; #escrow = {}; #orders = new Map(); #nextOrder = 1;
  #saleForMotor = null; #lastActivity; #currentUpi = null;
  coinTray = []; productTray = []; upiRefunds = []; audit = []; cashRevenue = 0; upiRevenue = 0;

  constructor({ id = 'VM', dispenser = async () => true, now = () => Date.now() } = {}) {
    this.id = id; this.dispenser = dispenser; this.now = now; this.#lastActivity = now();
  }

  get stateName() { return this.#state.name; }
  get balance() { return total(this.#escrow); }
  get coinBox() { return { ...this.#box }; }
  get currentUpiKey() { return this.#currentUpi?.key ?? null; }
  stock(slot) { return this.#slots.get(slot)?.count ?? 0; }

  // customer events (select / onUpiResult may run the motor, so they are async)
  insert(d) { return this.#handleSync(`insert ${d}`, (s) => s.insert(this, d)); }
  payByUpi(slot) { return this.#handleSync(`pay by UPI ${slot}`, (s) => s.payByUpi(this, slot)); }
  cancel() { return this.#handleSync('cancel', (s) => s.cancel(this)); }
  tick() { return this.#handleSync('tick', (s) => s.tick(this)); }
  enterMaintenance() { return this.#handleSync('enter maintenance', (s) => s.enterMaintenance(this)); }
  exitMaintenance() { return this.#handleSync('exit maintenance', (s) => s.exitMaintenance(this)); }
  select(slot) { return this.#handle(`select ${slot}`, (s) => s.select(this, slot)); }
  onUpiResult(key, ok) {
    return this.#handle(`UPI ${ok ? 'SUCCESS' : 'FAILED'} ${key}`, (s) => {
      const o = this.#orders.get(key);
      if (!o) return 'Unknown order: ignored';
      if (o.status === 'PENDING') return s.upiResult(this, o, ok);
      if (ok && (o.status === 'FAILED' || o.status === 'ABANDONED')) {
        o.status = 'REFUNDED'; this.upiRefunds.push(key);
        return `Late payment for closed order: refunded ${rupees(o.amount)} to UPI`;
      }
      return `Duplicate callback (${o.status}): ignored`;
    });
  }

  // technician
  restock(slot, product, count) {
    this.#requireMaintenance();
    const s = this.#slots.get(slot) ?? { product, count: 0, jammed: false };
    if (s.product.name !== product.name) s.count = 0;
    Object.assign(s, { product, count: s.count + count, jammed: false }); this.#slots.set(slot, s);
  }
  loadCoins(d, n) { this.#requireMaintenance(); if (!DENOMS[d].coin) throw new Error('coins only'); this.#box[d] = (this.#box[d] ?? 0) + n; }

  #handleSync(event, action) {
    const from = this.#state.name; const msg = action(this.#state);
    if (msg) this.audit.push({ at: this.now(), from, to: this.#state.name, event, msg });
    return msg;
  }

  async #handle(event, action) {
    const msg = this.#handleSync(event, action);
    const sale = this.#saleForMotor; this.#saleForMotor = null;
    if (!sale) return msg;
    let dropped;
    try { dropped = await this.dispenser(sale.slot, sale.product); } catch { dropped = false; } // other events run here
    const done = this.#finishSale(sale, dropped);
    this.audit.push({ at: this.now(), from: 'Dispensing', to: this.#state.name, event: `drop sensor ${sale.slot}`, msg: done });
    return done;
  }

  // ---- operations the states call ----
  _acceptCash(d) {
    const balance = this.balance + DENOMS[d].paise; const pool = this.#changePool();
    if (DENOMS[d].coin) pool[d] = (pool[d] ?? 0) + 1;
    const products = [...this.#slots.values()].filter((s) => s.count > 0 && !s.jammed).map((s) => s.product);
    const canFinish = products.some((p) => p.price > balance || exact(balance - p.price, pool) !== null);
    if (!canFinish) return this._bounce(d, `EXACT CHANGE ONLY: no change possible for ${rupees(balance)}, ${d} returned`);
    this.#escrow[d] = (this.#escrow[d] ?? 0) + 1; this.#lastActivity = this.now(); this.#state = S.HasMoney;
    return `Balance ${rupees(balance)}`;
  }
  _priceCheck(slot) { return this.#unavailable(slot) ?? `${this.#slots.get(slot).product.name} ${rupees(this.#slots.get(slot).product.price)}: insert money or pay by UPI`; }
  _sellForCash(slot) {
    const problem = this.#unavailable(slot); if (problem) return `${problem}: choose another or cancel`;
    const p = this.#slots.get(slot).product; const bal = this.balance;
    if (bal < p.price) return `Insert ${rupees(p.price - bal)} more`;
    const change = exact(bal - p.price, this.#changePool());
    if (!change) return `Cannot return ${rupees(bal - p.price)} change: choose another, add exact money, or cancel`;
    return this.#startSale({ slot, product: p, payment: { kind: 'cash', inserted: { ...this.#escrow } }, change }, `Dispensing ${p.name}`);
  }
  _startUpi(slot) {
    const problem = this.#unavailable(slot); if (problem) return problem;
    const o = { key: `${this.id}-${this.#nextOrder++}`, slot, amount: this.#slots.get(slot).product.price, status: 'PENDING' };
    this.#orders.set(o.key, o); this.#currentUpi = o; this.#lastActivity = this.now(); this.#state = S.AwaitingUpi;
    return `Scan QR to pay ${rupees(o.amount)} (order ${o.key})`;
  }
  _upiPaid(o) {
    o.status = 'PAID'; this.#currentUpi = null;
    const product = this.#slots.get(o.slot).product;
    return this.#startSale({ slot: o.slot, product, payment: { kind: 'upi', key: o.key, amount: o.amount }, change: {} }, `Paid, dispensing ${product.name}`);
  }
  _upiFailed(o) { o.status = 'FAILED'; this.#currentUpi = null; this.#state = this._idleOrSoldOut(); return 'UPI payment failed: nothing charged'; }
  _abandonUpi(reason) {
    const o = this.#currentUpi; o.status = 'ABANDONED'; this.#currentUpi = null; this.#state = this._idleOrSoldOut();
    return `${reason}: order ${o.key} closed; a late payment will be refunded automatically`;
  }
  _refundEscrow(reason) { const amt = this.balance; this.#returnEscrow(); this.#state = this._idleOrSoldOut(); return `${reason}: returned ${rupees(amt)}`; }
  _bounce(d, msg) { this.coinTray.push(d); return msg; }
  _moveTo(s, msg) { this.#state = s; return msg; }
  _idleOrSoldOut() { return [...this.#slots.values()].some((s) => s.count > 0 && !s.jammed) ? S.Idle : S.SoldOut; }
  _idleMs() { return this.now() - this.#lastActivity; }

  #startSale(sale, msg) { this.#saleForMotor = sale; this.#state = S.Dispensing; return msg; }
  #finishSale(sale, dropped) {
    let result;
    if (dropped) {
      this.#slots.get(sale.slot).count--; this.productTray.push(sale.product);
      if (sale.payment.kind === 'cash') {
        for (const [d, n] of Object.entries(sale.payment.inserted)) this.#box[d] = (this.#box[d] ?? 0) + n;
        for (const [d, n] of Object.entries(sale.change)) {
          if ((this.#box[d] ?? 0) < n) throw new Error(`not enough ${d}`); // never negative
          this.#box[d] -= n; for (let i = 0; i < n; i++) this.coinTray.push(d);
        }
        this.#escrow = {}; this.cashRevenue += sale.product.price;
      } else this.upiRevenue += sale.payment.amount;
      result = `Take your ${sale.product.name}` + (total(sale.change) ? `, change ${rupees(total(sale.change))}` : '');
    } else {
      this.#slots.get(sale.slot).jammed = true;
      if (sale.payment.kind === 'cash') { this.#returnEscrow(); result = `Jam in ${sale.slot}: returned ${rupees(total(sale.payment.inserted))}`; } else {
        this.#orders.get(sale.payment.key).status = 'REFUNDED'; this.upiRefunds.push(sale.payment.key);
        result = `Jam in ${sale.slot}: refunded ${rupees(sale.payment.amount)} to UPI`;
      }
    }
    this.#state = this._idleOrSoldOut();
    return result;
  }
  #returnEscrow() { for (const [d, n] of Object.entries(this.#escrow)) for (let i = 0; i < n; i++) this.coinTray.push(d); this.#escrow = {}; }
  #changePool() {
    const pool = Object.fromEntries(Object.entries(this.#box).filter(([d, n]) => DENOMS[d].coin && n > 0));
    for (const [d, n] of Object.entries(this.#escrow)) if (DENOMS[d].coin) pool[d] = (pool[d] ?? 0) + n;
    return pool;
  }
  #unavailable(slot) {
    const s = this.#slots.get(slot); if (!s) return `No slot ${slot}`;
    return s.count > 0 && !s.jammed ? null : `${s.product.name} sold out`;
  }
  #requireMaintenance() { if (this.#state !== S.Maintenance) throw new Error(`technician operations need maintenance mode, state is ${this.#state.name}`); }
}

module.exports = { VendingMachine, DENOMS, greedy, exact, total, rupees, CASH_TIMEOUT_MS, UPI_TIMEOUT_MS };
