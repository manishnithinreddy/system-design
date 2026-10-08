'use strict';

// A small logging framework, same design as the Java version:
//   Logger (dotted hierarchy, level inheritance, additivity) -> filters -> appenders -> layout -> sink
// Differences that come from Node itself:
//   - Context (the MDC equivalent) uses AsyncLocalStorage: it follows a request across `await`s,
//     where a "thread-local" would be useless (one thread serves every request).
//   - Node runs our JavaScript on ONE thread (the event loop). There is no background thread to hand
//     events to, and we must never block that thread. So "async appender" here means: buffer events in
//     memory, write them in batches later (setImmediate), and respect the stream's own back-pressure
//     (write() returning false -> wait for 'drain'). "BLOCK" is not an option: blocking the event
//     loop would freeze every request, so a full buffer can only drop (and count).

const { AsyncLocalStorage } = require('node:async_hooks');

const LEVELS = Object.freeze({ TRACE: 10, DEBUG: 20, INFO: 30, WARN: 40, ERROR: 50, OFF: Infinity });
const NAMES = Object.fromEntries(Object.entries(LEVELS).map(([k, v]) => [v, k]));

// ---------- message formatting ("{}" placeholders, SLF4J rules) ----------

function countPlaceholders(t) { return t.split('{}').length - 1; }

function safeString(x) {
  try { return String(x); } catch (e) { return `[toString() failed: ${e.message}]`; }
}

function formatMessage(template, args) {
  let i = 0;
  return template.replace(/\{\}/g, (m) => (i < args.length ? safeString(args[i++]) : m));
}

// ---------- context (MDC) ----------

const als = new AsyncLocalStorage();

/** Run fn with extra context fields; nested calls add to the outer context. */
function withContext(fields, fn) { return als.run({ ...(als.getStore() ?? {}), ...fields }, fn); }
function currentContext() { return als.getStore() ?? {}; }

// ---------- layouts (Strategy: event -> string) ----------

const pad = (s) => s.padEnd(5);
function patternLayout(e) {
  const ctx = Object.keys(e.context).length ? ' ' + JSON.stringify(e.context) : '';
  const err = e.err ? '\n' + (e.err.stack ?? String(e.err)) : '';
  return `${new Date(e.time).toISOString()} ${pad(NAMES[e.level])} ${e.logger} - ${e.msg}${ctx}${err}`;
}

// JSON.stringify already escapes quotes, backslashes, newlines and control characters correctly.
// Never build JSON by string concatenation.
function jsonLayout(e) {
  const out = { ts: new Date(e.time).toISOString(), level: NAMES[e.level], logger: e.logger, msg: e.msg, ...e.context };
  if (e.err) { out.error = String(e.err); out.stack = e.err.stack; }
  return JSON.stringify(out);
}

const SECRET_KV = /\b(password|passwd|secret|token|api[_-]?key)(\s*[=:]\s*)([^\s,&;"}]+)/gi;
const SECRET_JSON = /("(?:password|passwd|secret|token|api[_-]?key)":")((?:[^"\\]|\\.)*)(")/gi;
/** Decorator: masks secrets in whatever the inner layout produced. */
const redacting = (layout) => (e) => layout(e).replace(SECRET_JSON, '$1***$3').replace(SECRET_KV, '$1$2***');

// ---------- appenders ----------

class ArrayAppender {           // for tests
  events = [];
  append(e) { this.events.push(e); }
  get messages() { return this.events.map((e) => e.msg); }
}

/** Buffers formatted lines and writes them in batches. sink(lines) may return a Promise. */
class BufferedAppender {
  #buf = []; #scheduled = false; #inFlight = null; #closed = false;
  dropped = 0;

  constructor(sink, { capacity = 1000, policy = 'drop-below-warn', layout = patternLayout } = {}) {
    Object.assign(this, { sink, capacity, policy, layout });
  }

  append(e) {
    const n = this.#buf.length;
    const nearlyFull = this.policy === 'drop-below-warn' && n >= this.capacity * 0.8 && e.level < LEVELS.WARN;
    if (this.#closed || n >= this.capacity || nearlyFull) { this.dropped++; return; }
    this.#buf.push(this.layout(e));         // format now: the caller's objects may change later
    if (!this.#scheduled) {
      this.#scheduled = true;
      setImmediate(() => { this.#scheduled = false; this.flush().catch((err) => console.error('[logging]', err)); });
    }
  }

  /** Write everything buffered, one batch at a time, never two writes at once (keeps order). */
  async flush() {
    while (this.#buf.length || this.#inFlight) {
      if (this.#inFlight) { await this.#inFlight; continue; }
      const batch = this.#buf.splice(0, this.#buf.length);
      this.#inFlight = Promise.resolve(this.sink(batch)).finally(() => { this.#inFlight = null; });
    }
  }

  async close() { this.#closed = true; await this.flush(); }
}

/** A sink for a Writable stream (file, stdout) that honours its back-pressure signal. */
const streamSink = (stream) => (lines) => new Promise((resolve) => {
  if (stream.write(lines.join('\n') + '\n')) resolve(); else stream.once('drain', resolve);
});

// ---------- loggers ----------

class Logger {
  #ctx;
  constructor(name, parent, ctx) {
    this.name = name; this.parent = parent; this.#ctx = ctx;
    this.level = null;          // null = inherit from the nearest ancestor
    this.additive = true;
    this.appenders = [];
  }

  effectiveLevel() {
    for (let l = this; l; l = l.parent) if (l.level !== null) return l.level;
    throw new Error('ROOT must have a level');
  }

  isEnabled(level) { return level !== LEVELS.OFF && level >= this.effectiveLevel(); }

  trace(t, ...a) { this.log(LEVELS.TRACE, t, a); }
  debug(t, ...a) { this.log(LEVELS.DEBUG, t, a); }
  info(t, ...a) { this.log(LEVELS.INFO, t, a); }
  warn(t, ...a) { this.log(LEVELS.WARN, t, a); }
  error(t, ...a) { this.log(LEVELS.ERROR, t, a); }

  log(level, template, args) {
    if (!this.isEnabled(level)) return;                       // first, before any formatting
    if (typeof template === 'function') template = template(); // log.debug(() => expensive()) is lazy too
    let err = null;
    if (args.length && args.at(-1) instanceof Error && countPlaceholders(template) < args.length) {
      err = args.at(-1); args = args.slice(0, -1);
    }
    const e = Object.freeze({
      time: this.#ctx.now(), level, logger: this.name, template,
      msg: formatMessage(template, args), context: { ...currentContext() }, err,
    });
    if (!this.#ctx.filtersAccept(e)) return;
    for (let l = this; l; l = l.parent) {
      for (const a of l.appenders) {
        try { a.append(e); } catch (x) { console.error('[logging] appender failed:', x.message); }
      }
      if (!l.additive) break;
    }
  }
}

class LoggerContext {
  #loggers = new Map(); #filters = [];
  constructor({ now = Date.now } = {}) {
    this.now = now;
    this.root = new Logger('ROOT', null, this);
    this.root.level = LEVELS.INFO;
    this.#loggers.set('ROOT', this.root);
  }

  getLogger(name) {
    let l = this.#loggers.get(name);
    if (l) return l;
    const dot = name.lastIndexOf('.');
    l = new Logger(name, dot < 0 ? this.root : this.getLogger(name.slice(0, dot)), this);
    this.#loggers.set(name, l);
    return l;
  }

  setLevel(name, level) { this.getLogger(name).level = level; }  // runtime change, no restart
  addFilter(f) { this.#filters.push(f); }

  /** Chain of Responsibility: 'ACCEPT' / 'DENY' decide, 'NEUTRAL' passes to the next filter. */
  filtersAccept(e) {
    for (const f of this.#filters) {
      const d = f(e);
      if (d === 'ACCEPT') return true;
      if (d === 'DENY') return false;
    }
    return true;
  }

  async close() {
    const all = new Set([...this.#loggers.values()].flatMap((l) => l.appenders));
    for (const a of all) if (a.close) await a.close();
  }
}

module.exports = {
  LEVELS, LoggerContext, Logger, formatMessage, withContext, currentContext,
  patternLayout, jsonLayout, redacting, ArrayAppender, BufferedAppender, streamSink,
};
