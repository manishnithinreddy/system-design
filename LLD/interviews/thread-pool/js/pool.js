'use strict';

// Two small building blocks, the Node.js versions of the Java ThreadPool / ConnectionPool:
//
// 1. ResourcePool: an async pool of expensive resources (DB connections). Same rules as the Java pool:
//    at most `max` exist, acquire() reuses an idle one or creates one, otherwise WAITS in a FIFO line,
//    gives up after `acquireTimeoutMs`, validates on acquire and replaces broken ones.
//    This is what `pg.Pool` (node-postgres) or `generic-pool` do.
//
// 2. createLimiter(n): run at most n async tasks at once, queue the rest (like the p-limit package).
//
// Why no thread pool here? Node runs our JavaScript on ONE thread (the event loop). Waiting for the
// database doesn't occupy that thread: it's just a pending Promise. So "1,000 requests in flight" costs
// no threads at all, and the scarce thing is no longer threads but the RESOURCES behind the awaits
// (connections, the remote service's capacity). A concurrency limiter is the Node-shaped answer to
// "bounded workers". (Node does have a small thread pool, libuv's, default 4 threads, for file I/O, DNS
// lookups and crypto; and worker_threads for CPU-heavy JavaScript. Neither is needed here.)
//
// Because there is one thread, there are no locks: between two `await`s, our code can't be interrupted.

class TimeoutError extends Error {
  constructor(msg) { super(msg); this.name = 'TimeoutError'; }
}

class ResourcePool {
  #create; #validate; #destroy; #max; #timeoutMs;
  #idle = [];          // LIFO stack of idle resources (most recently used = warmest)
  #waiters = [];       // FIFO line: { resolve, reject, timer }
  #total = 0;          // idle + borrowed + being created; never above max
  #borrowed = new Set();
  #closed = false;
  stats = { created: 0, destroyed: 0, broken: 0, timeouts: 0 };

  constructor({ create, validate = async () => true, destroy = async () => {}, max = 10, acquireTimeoutMs = 1000 }) {
    this.#create = create;
    this.#validate = validate;
    this.#destroy = destroy;
    this.#max = max;
    this.#timeoutMs = acquireTimeoutMs;
  }

  get size() { return this.#total; }
  get idleCount() { return this.#idle.length; }
  get pending() { return this.#waiters.length; }

  async acquire() {
    for (;;) {
      if (this.#closed) throw new Error('pool closed');
      const slot = await this.#takeSlot();           // { res } = existing one, {} = "you may create"
      if (!('res' in slot)) {
        try {
          const res = await this.#create();
          this.stats.created++;
          this.#borrowed.add(res);
          return res;
        } catch (err) {
          this.#freeSlot();
          throw err;
        }
      }
      let ok;
      try { ok = await this.#validate(slot.res); } catch { ok = false; }
      if (ok) { this.#borrowed.add(slot.res); return slot.res; }
      this.stats.broken++;
      await this.#destroyOne(slot.res);              // replace and try again
    }
  }

  #takeSlot() {
    if (this.#waiters.length === 0) {               // nobody ahead of us: no line-jumping
      if (this.#idle.length > 0) return Promise.resolve({ res: this.#idle.pop() });
      if (this.#total < this.#max) { this.#total++; return Promise.resolve({}); }
    }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const i = this.#waiters.indexOf(waiter);
        if (i >= 0) this.#waiters.splice(i, 1);
        this.stats.timeouts++;
        reject(new TimeoutError(`no resource within ${this.#timeoutMs} ms (size=${this.#total}, waiting=${this.#waiters.length})`));
      }, this.#timeoutMs);
      this.#waiters.push(waiter);
    });
  }

  release(res) {
    if (!this.#borrowed.delete(res)) return;          // double release (or a stranger): ignore
    if (this.#closed) { this.#destroyOne(res); return; }
    const w = this.#waiters.shift();                  // hand straight to the longest waiter (FIFO)
    if (w) { clearTimeout(w.timer); w.resolve({ res }); } else this.#idle.push(res);
  }

  /** Remove a resource the caller knows is broken (e.g. the query failed with "connection reset"). */
  async discard(res) {
    if (this.#borrowed.delete(res)) await this.#destroyOne(res);
  }

  async #destroyOne(res) {
    try { await this.#destroy(res); } catch { /* destroying must not throw */ }
    this.stats.destroyed++;
    this.#freeSlot();                                 // close first, THEN let someone create a replacement
  }

  #freeSlot() {
    this.#total--;
    const w = this.#closed ? undefined : this.#waiters.shift();
    if (w) { this.#total++; clearTimeout(w.timer); w.resolve({}); }
  }

  /** Convenience: acquire, run, always release (the JS version of try-with-resources). */
  async use(fn) {
    const res = await this.acquire();
    try { return await fn(res); } finally { this.release(res); }
  }

  async close() {
    this.#closed = true;
    for (const w of this.#waiters.splice(0)) { clearTimeout(w.timer); w.reject(new Error('pool closed')); }
    await Promise.all(this.#idle.splice(0).map((r) => this.#destroyOne(r)));
  }
}

/**
 * Run at most `n` async functions at the same time; the rest wait in FIFO order.
 *   const limit = createLimiter(5);
 *   await Promise.all(urls.map((u) => limit(() => fetch(u))));
 * Without it, `Promise.all(urls.map(fetch))` starts ALL requests at once: 10,000 sockets, and the
 * downstream service (or our own file-descriptor limit) falls over.
 */
function createLimiter(n) {
  let active = 0;
  const queue = [];
  const next = () => {
    if (active >= n || queue.length === 0) return;
    active++;
    const { fn, resolve, reject } = queue.shift();
    Promise.resolve()
      .then(fn)                                       // also catches a synchronous throw inside fn
      .then(resolve, reject)
      .finally(() => { active--; next(); });
  };
  const limit = (fn) => new Promise((resolve, reject) => { queue.push({ fn, resolve, reject }); next(); });
  Object.defineProperties(limit, {
    active: { get: () => active },
    queued: { get: () => queue.length },
  });
  return limit;
}

module.exports = { ResourcePool, TimeoutError, createLimiter };
