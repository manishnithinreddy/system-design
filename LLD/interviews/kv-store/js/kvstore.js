'use strict';

// Mini Redis: nested transactions (stack of undo logs), O(1) COUNT, TTL (lazy + active expiry),
// optional append-only log with crash-safe transaction framing. Same design as the Java version.
// Node runs this on one thread, which is exactly how Redis executes commands (single writer).

const fs = require('node:fs');

const NEVER = Number.MAX_SAFE_INTEGER;

class NoTransactionError extends Error { constructor() { super('NO TRANSACTION'); } }

const esc = (s) => s.replace(/\\/g, '\\\\').replace(/\t/g, '\\t').replace(/\n/g, '\\n');
const unesc = (s) => s.replace(/\\(.)/g, (_, c) => (c === 't' ? '\t' : c === 'n' ? '\n' : c));

class AppendOnlyLog {
  constructor(path, { fsync = 'always' } = {}) {
    this.path = path;
    this.fsync = fsync; // 'always' | 'never'
    this.fd = fs.openSync(path, 'a');
  }

  static set(key, e) { return `S\t${esc(key)}\t${esc(e.value)}\t${e.expiresAt}`; }
  static del(key) { return `D\t${esc(key)}`; }

  append(records, asTransaction) {
    const lines = asTransaction ? ['B', ...records, 'C'] : records;
    fs.writeSync(this.fd, lines.join('\n') + '\n');
    if (this.fsync === 'always') fs.fsyncSync(this.fd);
  }

  replay(onSet, onDel) {
    if (!fs.existsSync(this.path)) return;
    const text = fs.readFileSync(this.path, 'utf8');
    const lines = text.split('\n');
    lines.pop(); // after the last '\n' there is either '' (clean end) or a torn, half-written line
    let batch = null;
    const apply = (r) => (r[0] === 'S' ? onSet(r[1], { value: r[2], expiresAt: Number(r[3]) }) : onDel(r[1]));
    for (const line of lines) {
      if (line === 'B') { batch = []; continue; }
      if (line === 'C') { (batch ?? []).forEach(apply); batch = null; continue; }
      const p = line.split('\t');
      let rec = null;
      if (p[0] === 'S' && p.length === 4 && /^\d+$/.test(p[3])) rec = ['S', unesc(p[1]), unesc(p[2]), p[3]];
      else if (p[0] === 'D' && p.length === 2) rec = ['D', unesc(p[1])];
      if (!rec) break;                    // garbage: stop
      if (batch) batch.push(rec); else apply(rec);
    }
    // an unterminated batch (B without C) is ignored
  }

  close() { fs.fsyncSync(this.fd); fs.closeSync(this.fd); }
}

class KeyValueStore {
  #data = new Map();         // key -> { value, expiresAt }
  #counts = new Map();       // value -> number of keys holding it
  #undo = [];                // stack of Map(key -> original entry | null)
  #pending = [];             // stack of log-record arrays, one per open layer
  #now; #log;

  constructor({ now = () => Date.now(), log = null } = {}) {
    this.#now = now;
    this.#log = log;
    if (log) log.replay((k, e) => this.#rawPut(k, e), (k) => this.#rawRemove(k));
  }

  get(key) { this.#purge(); return this.#data.get(key)?.value ?? null; }
  set(key, value) { this.#purge(); this.#write(key, { value, expiresAt: NEVER }); }
  delete(key) { this.#purge(); if (!this.#data.has(key)) return false; this.#write(key, null); return true; }
  count(value) { this.#purge(); return this.#counts.get(value) ?? 0; }

  expire(key, seconds) {
    this.#purge();
    const e = this.#data.get(key);
    if (!e) return false;
    this.#write(key, { value: e.value, expiresAt: this.#now() + seconds * 1000 });
    return true;
  }

  ttl(key) {
    this.#purge();
    const e = this.#data.get(key);
    if (!e) return -2;
    if (e.expiresAt === NEVER) return -1;
    return Math.ceil((e.expiresAt - this.#now()) / 1000);
  }

  begin() { this.#undo.push(new Map()); this.#pending.push([]); }

  rollback() {
    if (!this.#undo.length) throw new NoTransactionError();
    const layer = this.#undo.pop();
    this.#pending.pop();
    for (const [k, original] of layer) { if (original) this.#rawPut(k, original); else this.#rawRemove(k); }
  }

  commit() {
    if (!this.#undo.length) throw new NoTransactionError();
    const layer = this.#undo.pop();
    const records = this.#pending.pop();
    if (this.#undo.length) {
      const parent = this.#undo[this.#undo.length - 1];
      for (const [k, original] of layer) if (!parent.has(k)) parent.set(k, original);   // parent's older original wins
      this.#pending[this.#pending.length - 1].push(...records);
    } else if (this.#log && records.length) {
      this.#log.append(records, true);
    }
  }

  #write(key, entry) {
    if (this.#undo.length) {
      const layer = this.#undo[this.#undo.length - 1];
      if (!layer.has(key)) layer.set(key, this.#data.get(key) ?? null);
    }
    if (entry) this.#rawPut(key, entry); else this.#rawRemove(key);
    const rec = entry ? AppendOnlyLog.set(key, entry) : AppendOnlyLog.del(key);
    if (this.#pending.length) this.#pending[this.#pending.length - 1].push(rec);
    else if (this.#log) this.#log.append([rec], false);
  }

  #rawPut(key, e) {
    const old = this.#data.get(key);
    if (old) this.#dec(old.value);
    this.#data.set(key, e);
    this.#counts.set(e.value, (this.#counts.get(e.value) ?? 0) + 1);
  }

  #rawRemove(key) {
    const old = this.#data.get(key);
    if (!old) return;
    this.#data.delete(key);
    this.#dec(old.value);
  }

  #dec(value) {
    const n = this.#counts.get(value);
    if (n === 1) this.#counts.delete(value); else this.#counts.set(value, n - 1);
  }

  // Active expiry. A real implementation keeps a heap of expiry times (see the Java version);
  // a scan keeps the JS version short and is fine for small data sets.
  #purge() {
    const now = this.#now();
    for (const [k, e] of this.#data) if (e.expiresAt !== NEVER && now >= e.expiresAt) this.#rawRemove(k);
  }
}

/** Text commands like redis-cli. */
function execute(store, line) {
  const [cmd, ...a] = line.trim().split(/\s+/);
  try {
    switch (cmd.toUpperCase()) {
      case 'SET': store.set(a[0], a[1]); return 'OK';
      case 'GET': return store.get(a[0]) ?? 'NULL';
      case 'DELETE': return store.delete(a[0]) ? '1' : '0';
      case 'COUNT': return String(store.count(a[0]));
      case 'EXPIRE': return store.expire(a[0], Number(a[1])) ? '1' : '0';
      case 'TTL': return String(store.ttl(a[0]));
      case 'BEGIN': store.begin(); return 'OK';
      case 'ROLLBACK': store.rollback(); return 'OK';
      case 'COMMIT': store.commit(); return 'OK';
      default: return `ERR unknown command ${cmd}`;
    }
  } catch (e) {
    if (e instanceof NoTransactionError) return 'NO TRANSACTION';
    throw e;
  }
}

module.exports = { KeyValueStore, AppendOnlyLog, execute, NoTransactionError };
