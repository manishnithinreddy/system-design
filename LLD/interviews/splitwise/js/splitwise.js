'use strict';

// Money is integer paise everywhere (₹1 = 100). Never floats: see LLD/libraries/js/money-and-numbers-in-js.md
// Same design as the Java version: split specs, largest-remainder rounding, append-only ledger,
// derived balances, greedy debt simplification.

/** "420.50" -> 42050; rejects more than 2 decimals. */
function parseRupees(str) {
  const m = /^(-)?(\d+)(?:\.(\d{1,2}))?$/.exec(String(str).trim());
  if (!m) throw new Error(`invalid amount: ${str}`);
  const paise = Number(m[2]) * 100 + Number((m[3] ?? '').padEnd(2, '0'));
  return m[1] ? -paise : paise;
}

const formatINR = (paise) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(paise / 100);

/**
 * Largest remainder: floor every share, then give leftover paise to the largest fractional parts
 * (ties: alphabetical). Weights are integers; using BigInt avoids any floating point.
 */
function largestRemainder(total, weights /* Map<user, positive integer> */) {
  const users = [...weights.keys()].sort();
  if (!users.length) throw new Error('no participants');
  const W = users.reduce((s, u) => s + BigInt(weights.get(u)), 0n);
  const T = BigInt(total);
  const parts = users.map((u) => {
    const num = T * BigInt(weights.get(u));
    return { u, floor: num / W, rem: num % W };           // exact: floor and remainder as integers
  });
  let leftover = T - parts.reduce((s, p) => s + p.floor, 0n);
  parts.sort((a, b) => (a.rem === b.rem ? (a.u < b.u ? -1 : 1) : (b.rem > a.rem ? 1 : -1)));
  const out = {};
  for (const p of parts) {
    out[p.u] = Number(p.floor + (leftover > 0n ? 1n : 0n));
    if (leftover > 0n) leftover -= 1n;
  }
  return out;
}

/** spec: {type:'equal', users} | {type:'exact', paise:{u:n}} | {type:'percent', basisPoints:{u:n}} | {type:'shares', shares:{u:n}} */
function split(total, spec) {
  if (!Number.isInteger(total) || total <= 0) throw new Error('total must be a positive integer (paise)');
  switch (spec.type) {
    case 'equal': {
      if (new Set(spec.users).size !== spec.users.length) throw new Error('duplicate participant');
      return largestRemainder(total, new Map(spec.users.map((u) => [u, 1])));
    }
    case 'exact': {
      const sum = Object.values(spec.paise).reduce((a, b) => a + b, 0);
      if (sum !== total) throw new Error(`exact amounts add up to ${formatINR(sum)}, not ${formatINR(total)}`);
      return { ...spec.paise };
    }
    case 'percent': { // basis points: 1% = 100, so 33.33% = 3333, no decimals needed
      const sum = Object.values(spec.basisPoints).reduce((a, b) => a + b, 0);
      if (sum !== 10000) throw new Error('percentages must add up to 100');
      return largestRemainder(total, new Map(Object.entries(spec.basisPoints)));
    }
    case 'shares': {
      if (Object.values(spec.shares).some((n) => !(n > 0))) throw new Error('shares must be positive');
      return largestRemainder(total, new Map(Object.entries(spec.shares)));
    }
    default: throw new Error(`unknown split type ${spec.type}`);
  }
}

function simplifyDebts(balances) {
  const sum = Object.values(balances).reduce((a, b) => a + b, 0);
  if (sum !== 0) throw new Error('balances must sum to zero');
  const byBiggest = (a, b) => b.amount - a.amount || (a.user < b.user ? -1 : 1);
  // Few people per group, so sorting arrays each step is fine (a heap would be O(log n)).
  const creditors = Object.entries(balances).filter(([, v]) => v > 0).map(([user, amount]) => ({ user, amount }));
  const debtors = Object.entries(balances).filter(([, v]) => v < 0).map(([user, v]) => ({ user, amount: -v }));
  const transfers = [];
  while (creditors.length) {
    creditors.sort(byBiggest); debtors.sort(byBiggest);
    const c = creditors[0], d = debtors[0];
    const x = Math.min(c.amount, d.amount);
    transfers.push({ from: d.user, to: c.user, amount: x });
    c.amount -= x; d.amount -= x;
    if (!c.amount) creditors.shift();
    if (!d.amount) debtors.shift();
  }
  return transfers;
}

class Group {
  #id; #members; #entries = []; #expenses = new Map(); #requests = new Map(); #seq = 1; #now;

  constructor(id, members, now = () => new Date()) {
    if (members.length < 2) throw new Error('a group needs at least 2 members');
    this.#id = id; this.#members = new Set(members); this.#now = now;
  }

  // No locks: in Node, each method runs to completion on the single event-loop thread.
  addExpense({ requestId, paidBy, total, spec, description }) {
    const prev = this.#requests.get(requestId);
    if (prev) {
      if (prev.paidBy !== paidBy || prev.total !== total || JSON.stringify(prev.spec) !== JSON.stringify(spec)) {
        throw new Error(`requestId ${requestId} reused for a different expense`);
      }
      return prev.result;
    }
    this.#requireMember(paidBy);
    const owed = split(total, spec);
    Object.keys(owed).forEach((u) => this.#requireMember(u));
    const expense = Object.freeze({ kind: 'expense', id: `${this.#id}-e${this.#seq++}`, at: this.#now(), paidBy, total, owed: Object.freeze(owed), description });
    this.#entries.push(expense);
    this.#expenses.set(expense.id, expense);
    this.#requests.set(requestId, { paidBy, total, spec, result: expense });
    return expense;
  }

  deleteExpense(expenseId) {
    if (!this.#expenses.has(expenseId)) throw new Error(`unknown expense ${expenseId}`);
    if (this.#entries.some((e) => e.kind === 'reversal' && e.of === expenseId)) throw new Error('already deleted');
    const r = Object.freeze({ kind: 'reversal', id: `${this.#id}-r${this.#seq++}`, at: this.#now(), of: expenseId });
    this.#entries.push(r);
    return r;
  }

  settle(from, to, amount) {
    this.#requireMember(from); this.#requireMember(to);
    if (from === to || !(amount > 0)) throw new Error('invalid settlement');
    const s = Object.freeze({ kind: 'settlement', id: `${this.#id}-s${this.#seq++}`, at: this.#now(), from, to, amount });
    this.#entries.push(s);
    return s;
  }

  balances() {
    const net = Object.fromEntries([...this.#members].sort().map((m) => [m, 0]));
    const apply = (x, sign) => {
      net[x.paidBy] += sign * x.total;
      for (const [u, o] of Object.entries(x.owed)) net[u] -= sign * o;
    };
    for (const e of this.#entries) {
      if (e.kind === 'expense') apply(e, 1);
      else if (e.kind === 'reversal') apply(this.#expenses.get(e.of), -1);
      else { net[e.from] += e.amount; net[e.to] -= e.amount; }
    }
    return net;
  }

  history() { return [...this.#entries]; }

  #requireMember(u) { if (!this.#members.has(u)) throw new Error(`${u} is not in group ${this.#id}`); }
}

module.exports = { parseRupees, formatINR, split, largestRemainder, simplifyDebts, Group };
