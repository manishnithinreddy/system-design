'use strict';

// An in-process pub-sub broker, the Node.js version of the Java one in ../java.
//
// Same ideas: topics with wildcards, a bounded queue per subscription (split into lanes by key),
// overflow policies, retries with an ack timeout, and a dead-letter topic. The difference is how
// concurrency works. Node runs our JavaScript on ONE thread (the event loop), so there are no locks:
// between two `await`s nothing else can touch our state. "Consumer threads" become async loops,
// one per lane, and "block the publisher" becomes "publish() returns a Promise that resolves when
// there is room", the same contract as a Node stream's write() returning false until 'drain'.
//
// Ack model: the handler is async. Its Promise resolving = ack; rejecting (or throwing) = nack;
// not settling within ackTimeoutMs = treated as lost and redelivered (its late result is ignored).

const DEFAULTS = Object.freeze({
  lanes: 1,                 // consumers in the group; each lane is processed by one async loop
  capacity: 1000,           // per lane
  overflow: 'block',        // 'block' | 'dropNewest' | 'dropOldest' | 'reject'
  blockTimeoutMs: 1000,
  mode: 'at-least-once',    // or 'at-most-once'
  ackTimeoutMs: 30_000,
  maxAttempts: 5,
  deadLetterTopic: null,
});

/** "*" = exactly one word, "#" = zero or more words (RabbitMQ topic-exchange rules). */
function topicMatches(pattern, topic) {
  const p = pattern.split('.');
  const t = topic.split('.');
  const match = (i, j) => {
    if (i === p.length) return j === t.length;
    if (p[i] === '#') {
      for (let k = j; k <= t.length; k++) if (match(i + 1, k)) return true;
      return false;
    }
    return j < t.length && (p[i] === '*' || p[i] === t[j]) && match(i + 1, j + 1);
  };
  return match(0, 0);
}

/** Java's String.hashCode, so the same key always picks the same lane. */
function hashKey(key) {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (Math.imul(31, h) + key.charCodeAt(i)) | 0;
  return h;
}

class Subscription {
  #broker; #handler; #lanes; #rr = 0; #idleWaiters = []; #closed = false;

  constructor(broker, name, pattern, handler, options) {
    this.#broker = broker;
    this.name = name;
    this.pattern = pattern;
    this.#handler = handler;
    this.options = { ...DEFAULTS, ...options };
    this.stats = { offered: 0, dropped: 0, rejected: 0, delivered: 0, redelivered: 0, acked: 0, deadLettered: 0 };
    this.#lanes = Array.from({ length: this.options.lanes }, (_, index) => ({ index, queue: [], waiters: [], busy: false }));
  }

  /** Returns 'accepted' | 'evicted' | 'dropped' | 'rejected', or a Promise of one (block policy when full). */
  offer(msg) {
    this.stats.offered++;
    const lane = msg.key != null
      ? this.#lanes[Math.abs(hashKey(msg.key) % this.#lanes.length)]
      : this.#lanes[this.#rr++ % this.#lanes.length];
    const { capacity, overflow, blockTimeoutMs } = this.options;
    if (this.#closed) return this.#count('rejected');
    if (lane.queue.length < capacity && lane.waiters.length === 0) return this.#enqueue(lane, msg);
    switch (overflow) {
      case 'dropNewest': return this.#count('dropped');
      case 'reject': return this.#count('rejected');
      case 'dropOldest':
        lane.queue.shift();
        this.#enqueue(lane, msg);
        return this.#count('evicted');
      case 'block':
        return new Promise((resolve) => {
          const waiter = { msg, resolve };
          waiter.timer = setTimeout(() => {   // gave up waiting: take it out of the line
            lane.waiters.splice(lane.waiters.indexOf(waiter), 1);
            resolve(this.#count('rejected'));
          }, blockTimeoutMs);
          lane.waiters.push(waiter);          // FIFO: earlier publishers get in first
        });
      default: throw new Error(`unknown overflow policy ${overflow}`);
    }
  }

  #count(outcome) {
    if (outcome === 'dropped' || outcome === 'evicted') this.stats.dropped++;
    if (outcome === 'rejected') this.stats.rejected++;
    return outcome;
  }

  #enqueue(lane, msg) {
    lane.queue.push(msg);
    if (!lane.busy) this.#pump(lane);   // not awaited: the consumer loop runs on its own
    return 'accepted';
  }

  async #pump(lane) {
    lane.busy = true;
    while (lane.queue.length > 0 && !this.#closed) {
      const msg = lane.queue.shift();
      const waiter = lane.waiters.shift();   // a slot was freed: let one blocked publisher in
      if (waiter) {
        clearTimeout(waiter.timer);
        lane.queue.push(waiter.msg);
        waiter.resolve('accepted');
      }
      await this.#deliver(msg, lane);       // one message at a time per lane: per-key order
    }
    lane.busy = false;
    if (this.#isIdle()) this.#idleWaiters.splice(0).forEach((resolve) => resolve());
  }

  async #deliver(msg, lane) {
    const { mode, maxAttempts, ackTimeoutMs, deadLetterTopic } = this.options;
    for (let attempt = 1; ; attempt++) {
      this.stats.delivered++;
      if (attempt > 1) this.stats.redelivered++;
      try {
        await withTimeout(Promise.resolve().then(() => this.#handler(msg, { attempt, lane: lane.index })), ackTimeoutMs);
        this.stats.acked++;
        return;
      } catch (err) {
        if (mode === 'at-most-once') return;              // lost: that's the deal
        if (attempt < maxAttempts) continue;              // nack or ack timeout: redeliver, before anything newer
        this.stats.deadLettered++;
        if (deadLetterTopic) {
          await this.#broker.publish(deadLetterTopic, msg.payload, {
            key: msg.key,
            headers: { ...msg.headers, 'dlq.attempts': String(attempt), 'dlq.reason': String(err.message), 'dlq.originalTopic': msg.topic },
          }, true);
        }
        return;
      }
    }
  }

  get backlog() {
    return this.#lanes.reduce((n, l) => n + l.queue.length + l.waiters.length + (l.busy ? 1 : 0), 0);
  }

  #isIdle() { return this.#lanes.every((l) => !l.busy && l.queue.length === 0); }

  /** Resolves when every lane is empty and no handler is running. */
  idle() {
    return this.#isIdle() ? Promise.resolve() : new Promise((resolve) => this.#idleWaiters.push(resolve));
  }

  close() {
    this.#closed = true;
    this.#broker.unsubscribe(this);
    for (const lane of this.#lanes) for (const w of lane.waiters.splice(0)) { clearTimeout(w.timer); w.resolve('rejected'); }
    this.#idleWaiters.splice(0).forEach((resolve) => resolve());
  }
}

class Broker {
  #subs = new Set();
  #nextId = 0;
  #accepting = true;

  subscribe(pattern, handler, options = {}) {
    const sub = new Subscription(this, options.name ?? `sub-${this.#subs.size + 1}`, pattern, handler, options);
    this.#subs.add(sub);
    return sub;
  }

  unsubscribe(sub) { this.#subs.delete(sub); }

  /** Fan-out to every matching subscription. Awaiting it is how a publisher feels back-pressure. */
  async publish(topic, payload, { key = null, headers = {} } = {}, internal = false) {
    if (!this.#accepting && !internal) throw new Error('broker is shutting down');
    if (/[*#]/.test(topic)) throw new Error(`wildcards are for subscribing: ${topic}`);
    const msg = Object.freeze({ id: `m-${++this.#nextId}`, topic, key, payload, headers: Object.freeze({ ...headers }) });
    const result = { id: msg.id, accepted: 0, dropped: 0, rejected: 0 };
    for (const sub of [...this.#subs]) {
      if (!topicMatches(sub.pattern, topic)) continue;
      const outcome = await sub.offer(msg);
      if (outcome === 'accepted' || outcome === 'evicted') result.accepted++;
      if (outcome === 'dropped' || outcome === 'evicted') result.dropped++;
      if (outcome === 'rejected') result.rejected++;
    }
    return result;
  }

  /** Stop taking publishes, wait for subscriptions to finish what they hold, then close them. */
  async shutdown() {
    this.#accepting = false;
    const subs = [...this.#subs];
    await Promise.all(subs.map((s) => s.idle()));
    subs.forEach((s) => s.close());
  }
}

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('ack timeout')), ms); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** Decorator: skip messages whose id was already processed (bounded memory, oldest ids forgotten first). */
function idempotent(handler, capacity = 10_000) {
  const seen = new Map();   // a Map remembers insertion order, so the first key is the oldest
  const wrapped = async (msg, ctx) => {
    if (seen.has(msg.id)) { wrapped.duplicates++; return; }
    await handler(msg, ctx);
    seen.set(msg.id, true);
    if (seen.size > capacity) seen.delete(seen.keys().next().value);
  };
  wrapped.duplicates = 0;
  return wrapped;
}

module.exports = { Broker, topicMatches, idempotent };
