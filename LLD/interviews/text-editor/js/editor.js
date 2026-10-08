'use strict';

// A gap buffer + an editor with undo/redo (Command pattern) and keystroke coalescing.
// Same design as the Java version, smaller. Node 22, no dependencies.

/** One array with a hole (the gap) at the edit point. Typing at the gap is O(1). */
class GapBuffer {
  #buf;
  #gapStart;
  #gapEnd;

  constructor(initial = '', gap = 16) {
    this.#buf = new Array(initial.length + gap);
    for (let i = 0; i < initial.length; i++) this.#buf[i] = initial[i];
    this.#gapStart = initial.length;
    this.#gapEnd = this.#buf.length;
  }

  get length() { return this.#buf.length - (this.#gapEnd - this.#gapStart); }

  insert(pos, text) {
    this.#check(pos, 0, this.length + 1);
    this.#moveGap(pos);
    this.#ensureGap(text.length);
    for (const ch of text) this.#buf[this.#gapStart++] = ch;
  }

  delete(pos, len) {
    this.#check(pos, len, this.length);
    this.#moveGap(pos);
    this.#gapEnd += len;                    // deleted chars just become part of the gap
  }

  charAt(i) {
    this.#check(i, 1, this.length);
    return i < this.#gapStart ? this.#buf[i] : this.#buf[i + this.#gapEnd - this.#gapStart];
  }

  slice(start, end) {
    let s = '';
    for (let i = start; i < end; i++) s += this.charAt(i);
    return s;
  }

  text() {
    return this.#buf.slice(0, this.#gapStart).join('') + this.#buf.slice(this.#gapEnd).join('');
  }

  #moveGap(pos) {
    while (pos < this.#gapStart) this.#buf[--this.#gapEnd] = this.#buf[--this.#gapStart];   // gap moves left
    while (pos > this.#gapStart) this.#buf[this.#gapStart++] = this.#buf[this.#gapEnd++];   // gap moves right
  }

  #ensureGap(needed) {
    if (this.#gapEnd - this.#gapStart >= needed) return;
    const tail = this.#buf.slice(this.#gapEnd);
    const capacity = Math.max(this.#buf.length * 2, this.length + needed + 16);
    const bigger = new Array(capacity);
    for (let i = 0; i < this.#gapStart; i++) bigger[i] = this.#buf[i];
    for (let i = 0; i < tail.length; i++) bigger[capacity - tail.length + i] = tail[i];
    this.#gapEnd = capacity - tail.length;
    this.#buf = bigger;
  }

  #check(pos, len, limit) {
    if (pos < 0 || len < 0 || pos + len > limit) throw new RangeError(`bad range ${pos}+${len}, limit ${limit}`);
  }
}

// ---------------------------------------------------------------- commands: each knows how to undo itself

class InsertCommand {
  constructor(pos, text, cursorBefore) { Object.assign(this, { pos, text, cursorBefore }); }
  execute(buf) { buf.insert(this.pos, this.text); }
  undo(buf) { buf.delete(this.pos, this.text.length); }
  get cursorAfter() { return this.pos + this.text.length; }
}

class DeleteCommand {
  constructor(pos, deleted, cursorBefore) { Object.assign(this, { pos, deleted, cursorBefore }); }
  execute(buf) { buf.delete(this.pos, this.deleted.length); }
  undo(buf) { buf.insert(this.pos, this.deleted); }
  get cursorAfter() { return this.pos; }
}

class MacroCommand {
  constructor(parts, cursorBefore, cursorAfter) { Object.assign(this, { parts, cursorBefore, cursorAfter }); }
  execute(buf) { for (const c of this.parts) c.execute(buf); }
  undo(buf) { for (const c of [...this.parts].reverse()) c.undo(buf); }
}

// ---------------------------------------------------------------- the editor

class Editor {
  #buf;
  #now;
  #maxHistory;
  #windowMs;
  #undo = [];               // end of array = most recent
  #redo = [];
  #cursor = 0;
  #lastTypedAt = 0;
  #mayCoalesce = false;

  /** now: injectable clock (() => millis), so tests control time. */
  constructor(buffer, { now = Date.now, maxHistory = 1000, coalesceWindowMs = 1000 } = {}) {
    this.#buf = buffer;
    this.#now = now;
    this.#maxHistory = maxHistory;
    this.#windowMs = coalesceWindowMs;
  }

  get text() { return this.#buf.text(); }
  get cursor() { return this.#cursor; }
  get undoSize() { return this.#undo.length; }
  get redoSize() { return this.#redo.length; }

  moveTo(pos) {
    this.#cursor = Math.max(0, Math.min(pos, this.#buf.length));
    this.#mayCoalesce = false;
  }

  type(s) {
    if (s.length === 0) return;
    const now = this.#now();
    const cmd = new InsertCommand(this.#cursor, s, this.#cursor);
    cmd.execute(this.#buf);
    this.#cursor = cmd.cursorAfter;
    const last = this.#undo.at(-1);
    if (this.#canCoalesce(last, cmd, now)) {
      this.#undo[this.#undo.length - 1] = new InsertCommand(last.pos, last.text + s, last.cursorBefore);
      this.#redo = [];
    } else {
      this.#push(cmd);
    }
    this.#lastTypedAt = now;
    this.#mayCoalesce = true;
  }

  backspace() {
    if (this.#cursor === 0) return;
    const p = this.#cursor - 1;
    this.#run(new DeleteCommand(p, this.#buf.slice(p, p + 1), this.#cursor));
  }

  deleteForward() {
    if (this.#cursor === this.#buf.length) return;
    const p = this.#cursor;
    this.#run(new DeleteCommand(p, this.#buf.slice(p, p + 1), p));
  }

  /** Every occurrence replaced as ONE undo step. Built from the last hit backwards so positions stay valid. */
  replaceAll(find, replacement) {
    if (!find) throw new Error('find must not be empty');
    const text = this.#buf.text();
    const hits = [];
    for (let i = text.indexOf(find); i >= 0; i = text.indexOf(find, i + find.length)) hits.push(i);
    if (hits.length === 0) return 0;
    const parts = hits.reverse().flatMap((pos) => [
      new DeleteCommand(pos, find, this.#cursor),
      new InsertCommand(pos, replacement, this.#cursor),
    ]);
    const newLength = text.length + hits.length * (replacement.length - find.length);
    this.#run(new MacroCommand(parts, this.#cursor, Math.min(this.#cursor, newLength)));
    return hits.length;
  }

  undo() {
    const c = this.#undo.pop();
    if (!c) return false;
    c.undo(this.#buf);
    this.#cursor = c.cursorBefore;
    this.#redo.push(c);
    this.#mayCoalesce = false;
    return true;
  }

  redo() {
    const c = this.#redo.pop();
    if (!c) return false;
    c.execute(this.#buf);
    this.#cursor = c.cursorAfter;
    this.#undo.push(c);
    this.#trim();
    this.#mayCoalesce = false;
    return true;
  }

  #run(cmd) {
    cmd.execute(this.#buf);
    this.#cursor = cmd.cursorAfter;
    this.#push(cmd);
    this.#mayCoalesce = false;
  }

  #push(cmd) {
    this.#undo.push(cmd);
    this.#redo = [];                         // a new edit invalidates the redo "future"
    this.#trim();
  }

  #trim() {
    while (this.#undo.length > this.#maxHistory) this.#undo.shift();   // drop the OLDEST
  }

  #canCoalesce(last, cmd, now) {
    if (!this.#mayCoalesce || cmd.text.length !== 1) return false;
    if (!(last instanceof InsertCommand) || last.cursorAfter !== cmd.pos) return false;
    if (now - this.#lastTypedAt > this.#windowMs) return false;
    const isSpace = (ch) => /\s/.test(ch);
    return !(isSpace(last.text.at(-1)) && !isSpace(cmd.text));        // a new word = a new undo step
  }
}

module.exports = { GapBuffer, Editor, InsertCommand, DeleteCommand, MacroCommand };
