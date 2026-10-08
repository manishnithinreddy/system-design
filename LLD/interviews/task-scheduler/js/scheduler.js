'use strict';

// In-process task scheduler, same design as the Java version:
//   - a min-heap of tasks ordered by runAt, with a sequence number as FIFO tie-break
//   - runDue(): runs everything due "now" (deterministic, used by tests)
//   - start(): ONE setTimeout, always re-armed to the earliest runAt (re-armed when an earlier task arrives)
// No cron here (see the Java version for cron + time zones). Node runs our code on one thread,
// so no locks are needed; "overlap" can only happen with async tasks, and we avoid it the same
// way as Java: a recurring task goes back into the heap only after its run has finished.

const Misfire = Object.freeze({ FIRE_ONCE_NOW: 'FIRE_ONCE_NOW', SKIP: 'SKIP' });
const MAX_TIMEOUT_MS = 2 ** 31 - 1; // setTimeout fires IMMEDIATELY for larger delays (~24.8 days)

class MinHeap {
  #a = [];
  constructor(less) { this.less = less; }
  get size() { return this.#a.length; }
  peek() { return this.#a[0]; }
  push(x) {
    const a = this.#a; a.push(x);
    for (let i = a.length - 1; i > 0;) {
      const p = (i - 1) >> 1;
      if (!this.less(a[i], a[p])) break;
      [a[i], a[p]] = [a[p], a[i]]; i = p;
    }
  }
  pop() {
    const a = this.#a; const top = a[0]; const last = a.pop();
    if (a.length) {
      a[0] = last;
      for (let i = 0; ;) {
        const l = 2 * i + 1; const r = l + 1; let m = i;
        if (l < a.length && this.less(a[l], a[m])) m = l;
        if (r < a.length && this.less(a[r], a[m])) m = r;
        if (m === i) break;
        [a[i], a[m]] = [a[m], a[i]]; i = m;
      }
    }
    return top;
  }
}

const byTime = (x, y) => x.runAt < y.runAt || (x.runAt === y.runAt && x.seq < y.seq);
const dueOrder = (x, y) => (y.priority - x.priority) || (x.runAt - y.runAt) || (x.seq - y.seq);

/** Full jitter: random in [0, min(max, base * 2^(failures-1))]. */
function backoffMs(failures, retry, random) {
  const cap = Math.min(retry.maxMs, retry.baseMs * 2 ** (failures - 1));
  return Math.round(random() * cap);
}

class Scheduler {
  #heap = new MinHeap(byTime);
  #seq = 0; #nextId = 0;
  #now; #random; #misfireMs;
  #timer = null; #started = false; #accepting = true;
  #inFlight = new Set();
  deadLetters = [];

  constructor({ now = () => Date.now(), random = Math.random, misfireThresholdMs = 1000 } = {}) {
    this.#now = now; this.#random = random; this.#misfireMs = misfireThresholdMs;
  }

  /**
   * opts: { delayMs, fixedRateMs | fixedDelayMs, priority, retry: { maxAttempts, baseMs, maxMs }, misfire }
   * Returns a handle with cancel(), state and runAt.
   */
  schedule(name, fn, opts = {}) {
    if (!this.#accepting) throw new Error('scheduler is shutting down');
    const task = {
      id: ++this.#nextId, name, fn,
      fixedRateMs: opts.fixedRateMs, fixedDelayMs: opts.fixedDelayMs,
      priority: opts.priority ?? 0,
      retry: opts.retry ?? { maxAttempts: 1, baseMs: 0, maxMs: 0 },
      misfire: opts.misfire ?? Misfire.FIRE_ONCE_NOW,
      failures: 0, state: 'SCHEDULED', runAt: 0, nominalAt: 0, seq: 0, lagMs: 0,
      cancel: () => {
        if (task.state !== 'SCHEDULED' && task.state !== 'RUNNING') return false;
        task.state = 'CANCELLED'; // lazy: the heap entry is dropped when it reaches the top
        return true;
      },
    };
    task.nominalAt = this.#now() + (opts.delayMs ?? 0);
    this.#enqueue(task, task.nominalAt);
    return task;
  }

  /** Runs every due task, one after another, awaiting async ones. Returns how many ran. */
  async runDue() {
    const due = this.#takeDue(this.#now());
    for (const t of due) await this.#runOnce(t);
    return due.length;
  }

  start() { this.#started = true; this.#arm(); }

  /** Graceful: stop the timer, stop accepting, wait for runs already in progress. */
  async shutdown() {
    this.#accepting = false; this.#started = false;
    clearTimeout(this.#timer);
    await Promise.allSettled([...this.#inFlight]);
  }

  #enqueue(t, runAt) {
    t.runAt = runAt; t.seq = this.#seq++; t.state = 'SCHEDULED';
    this.#heap.push(t);
    if (this.#heap.peek() === t) this.#arm(); // new earliest: re-arm, or the timer fires too late
  }

  #arm() {
    if (!this.#started) return;
    clearTimeout(this.#timer);
    const head = this.#heap.peek();
    if (!head) return;
    const wait = Math.min(Math.max(0, head.runAt - this.#now()), MAX_TIMEOUT_MS);
    this.#timer = setTimeout(() => {
      this.#timer = null;
      for (const t of this.#takeDue(this.#now())) {   // dispatch without awaiting: runs overlap ACROSS tasks
        const p = this.#runOnce(t).finally(() => this.#inFlight.delete(p));
        this.#inFlight.add(p);
      }
      this.#arm();
    }, wait);
  }

  #takeDue(now) {
    const due = [];
    while (this.#heap.size && this.#heap.peek().runAt <= now) {
      const t = this.#heap.pop();
      if (t.state === 'CANCELLED') continue;
      const misfired = t.failures === 0 && now - t.runAt > this.#misfireMs;
      if (misfired && t.misfire === Misfire.SKIP) {
        const next = this.#nextAfterSkip(t, now);
        if (next === null) { t.state = 'DONE'; continue; }
        t.nominalAt = next; this.#enqueue(t, next);
        continue;
      }
      if (misfired) t.nominalAt = now; // FIRE_ONCE_NOW: one run now, schedule continues from now
      t.lagMs = now - t.runAt;
      t.state = 'RUNNING';
      due.push(t);
    }
    return due.sort(dueOrder);
  }

  async #runOnce(t) {
    let error = null;
    try { await t.fn(); } catch (e) { error = e; }
    const finishedAt = this.#now();
    if (t.state === 'CANCELLED' || !this.#accepting) return;
    if (error) {
      t.failures++;
      if (t.failures < t.retry.maxAttempts) {
        this.#enqueue(t, finishedAt + backoffMs(t.failures, t.retry, this.#random));
        return;
      }
      this.deadLetters.push({ name: t.name, attempts: t.failures, error: String(error), at: finishedAt });
    }
    t.failures = 0;
    const next = t.fixedRateMs ? t.nominalAt + t.fixedRateMs
      : t.fixedDelayMs ? finishedAt + t.fixedDelayMs : null;
    if (next === null) { t.state = error ? 'DEAD' : 'DONE'; return; }
    t.nominalAt = next;
    this.#enqueue(t, next);
  }

  #nextAfterSkip(t, now) {
    if (t.fixedRateMs) return t.nominalAt + (Math.floor((now - t.nominalAt) / t.fixedRateMs) + 1) * t.fixedRateMs;
    if (t.fixedDelayMs) return now + t.fixedDelayMs;
    return null; // stale one-shot: drop
  }
}

module.exports = { Scheduler, Misfire, MinHeap, backoffMs };
