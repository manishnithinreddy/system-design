'use strict';

/*
 * A wallet ledger in plain Node 22, no npm packages.
 *
 * Money = integer paise in a normal Number. A JS Number is a 64-bit float, but every whole number up to
 * Number.MAX_SAFE_INTEGER (2^53 - 1, about 9 x 10^15 paise = ₹90 lakh crore) is stored exactly, and
 * adding two of them is exact while the result stays in that range. So we check Number.isSafeInteger on
 * every amount and every new balance. BigInt (123n) is the alternative when numbers can get bigger
 * (e.g. summing a whole bank's ledger): exact at any size, but it can't be mixed with Number in
 * arithmetic and JSON.stringify throws on it, so APIs usually send it as a string.
 *
 * Concurrency: Node runs our JavaScript on one thread, so no locks. But an async function pauses at every
 * `await`, and other requests run during the pause. Rule used below: do all slow async work first, then
 * check and write in one synchronous block with no await inside. That block is the "transaction".
 */

class WalletError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

class Wallet {
  #accounts = new Map();   // id -> { id, type, balance, version }
  #entries = [];           // append-only: { id, txnId, accountId, amount (signed paise), balanceAfter, at }
  #txns = new Map();       // txnId -> txn
  #idem = new Map();       // idempotency key -> { fingerprint, promise }
  #refunded = new Map();   // original txnId -> paise refunded so far
  #seq = 0;
  #now;
  #riskCheck;

  /** riskCheck: an async call (fraud check, KYC lookup...) awaited before money moves. */
  constructor({ now = () => new Date(), riskCheck = async () => {} } = {}) {
    this.#now = now;
    this.#riskCheck = riskCheck;
    this.open('SYS:BANK', 'SYSTEM');            // the outside world: goes negative as money comes in
  }

  open(id, type = 'USER') {
    if (this.#accounts.has(id)) throw new WalletError('EXISTS', `account ${id} exists`);
    this.#accounts.set(id, { id, type, balance: 0, version: 0 });
  }

  balance(id) { return this.#account(id).balance; }
  accounts() { return [...this.#accounts.values()].map((a) => ({ ...a })); }
  entriesFor(id) { return this.#entries.filter((e) => e.accountId === id); }
  entriesForTxn(txnId) { return this.#entries.filter((e) => e.txnId === txnId); }
  sumOfEntries(id) { return this.entriesFor(id).reduce((s, e) => s + e.amount, 0); }
  entryCount() { return this.#entries.length; }

  addMoney(key, to, amount) {
    return this.#idempotent(key, `TOP_UP|${to}|${amount}`,
      () => this.#move(key, 'TOP_UP', 'SYS:BANK', to, amount, null, () => null, () => {}));
  }

  transfer(key, from, to, amount) {
    if (from === to) throw new WalletError('SAME_ACCOUNT', 'from and to are the same');
    return this.#idempotent(key, `TRANSFER|${from}|${to}|${amount}`,
      () => this.#move(key, 'TRANSFER', from, to, amount, null, () => null, () => {}));
  }

  /** New entries in the opposite direction; the original stays untouched. */
  refund(key, originalTxnId, amount) {
    return this.#idempotent(key, `REFUND|${originalTxnId}|${amount}`, async () => {
      const orig = this.#txns.get(originalTxnId);
      if (!orig || orig.status !== 'COMPLETED' || orig.type !== 'TRANSFER') {
        return this.#reject(key, 'REFUND', null, null, amount, originalTxnId, `nothing refundable with id ${originalTxnId}`);
      }
      const check = () => {
        const left = orig.amount - (this.#refunded.get(orig.id) ?? 0);
        return amount > left ? `refund ${amount} exceeds refundable ${left}` : null;
      };
      return this.#move(key, 'REFUND', orig.to, orig.from, amount, orig.id, check,
        () => this.#refunded.set(orig.id, (this.#refunded.get(orig.id) ?? 0) + amount));
    });
  }

  /**
   * First call with a key stores its promise BEFORE anything can pause, so a second call with the same key,
   * even one arriving while the first is still awaiting, gets the same promise and the same txn.
   */
  #idempotent(key, fingerprint, work) {
    if (!key) throw new WalletError('NO_KEY', 'idempotency key required');
    const seen = this.#idem.get(key);
    if (seen) {
      if (seen.fingerprint !== fingerprint) {
        return Promise.reject(new WalletError('KEY_REUSED', `key ${key} was used for a different request`));
      }
      return seen.promise;
    }
    const promise = work().catch((err) => { this.#idem.delete(key); throw err; });   // crash: allow a retry
    this.#idem.set(key, { fingerprint, promise });
    return promise;
  }

  async #move(key, type, from, to, amount, related, check, onCommit) {
    if (!Number.isSafeInteger(amount) || amount <= 0) {
      throw new WalletError('BAD_AMOUNT', `amount must be a positive whole number of paise, got ${amount}`);
    }
    this.#account(from); this.#account(to);
    await this.#riskCheck({ type, from, to, amount });   // other requests may run here

    // ---- from here on: synchronous, nothing can interleave (the JS version of holding the lock) ----
    const src = this.#account(from);
    const dst = this.#account(to);
    const reason = check() ?? (src.type !== 'SYSTEM' && src.balance < amount ? `insufficient funds in ${from}` : null);
    if (reason) return this.#reject(key, type, from, to, amount, related, reason);
    const newSrc = src.balance - amount;
    const newDst = dst.balance + amount;
    if (!Number.isSafeInteger(newSrc) || !Number.isSafeInteger(newDst)) throw new WalletError('OVERFLOW', 'balance too large');

    const at = this.#now();
    const txn = Object.freeze({ id: `T${++this.#seq}`, key, type, from, to, amount, status: 'COMPLETED', related, at });
    src.balance = newSrc; src.version++;
    dst.balance = newDst; dst.version++;
    this.#entries.push(
      Object.freeze({ id: `E${this.#entries.length + 1}`, txnId: txn.id, accountId: from, amount: -amount, balanceAfter: newSrc, at }),
      Object.freeze({ id: `E${this.#entries.length + 2}`, txnId: txn.id, accountId: to, amount, balanceAfter: newDst, at }),
    );
    this.#txns.set(txn.id, txn);
    onCommit(txn);
    return txn;
  }

  #reject(key, type, from, to, amount, related, reason) {
    const txn = Object.freeze({ id: `R${++this.#seq}`, key, type, from, to, amount, status: 'REJECTED', reason, related, at: this.#now() });
    this.#txns.set(txn.id, txn);
    return txn;
  }

  #account(id) {
    const a = this.#accounts.get(id);
    if (!a) throw new WalletError('NO_ACCOUNT', `no account ${id}`);
    return a;
  }
}

/** 123456 paise -> "₹1,234.56" (Indian grouping comes free with the en-IN locale). */
function formatPaise(paise) {
  const sign = paise < 0 ? '-' : '';
  const abs = Math.abs(paise);
  return `${sign}₹${Math.floor(abs / 100).toLocaleString('en-IN')}.${String(abs % 100).padStart(2, '0')}`;
}

module.exports = { Wallet, WalletError, formatPaise };
