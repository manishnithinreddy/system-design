'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { GapBuffer, Editor } = require('./editor');

/** A fake clock: time only moves when the test says so. */
function fakeClock() {
  let t = 0;
  return { now: () => t, advance: (ms) => { t += ms; } };
}

function typeKeys(editor, clock, keys, gapMs) {
  for (const ch of keys) { clock.advance(gapMs); editor.type(ch); }
}

/** Tiny deterministic random generator (mulberry32), so a failing seed can be replayed. */
function seeded(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

test('gap buffer: insert, delete, charAt anywhere in the document', () => {
  const b = new GapBuffer('Hello world', 2);
  b.insert(5, ',');
  b.insert(0, '>> ');
  b.insert(b.length, '!');
  assert.equal(b.text(), '>> Hello, world!');
  assert.equal(b.charAt(3), 'H');
  b.delete(0, 3);
  b.delete(5, 1);
  assert.equal(b.text(), 'Hello world!');
  assert.throws(() => b.insert(99, 'x'), RangeError);
});

test('undo and redo; a new edit clears redo', () => {
  const e = new Editor(new GapBuffer(''));
  e.type('abc');
  e.moveTo(3);
  e.type('def');
  assert.equal(e.undo(), true);
  assert.equal(e.text, 'abc');
  assert.equal(e.redo(), true);
  assert.equal(e.text, 'abcdef');
  e.undo();
  e.type('X');
  assert.equal(e.redoSize, 0);
  assert.equal(e.redo(), false);
  assert.equal(e.text, 'abcX');
});

test('typing a word is one undo step; a space starts the next one', () => {
  const clock = fakeClock();
  const e = new Editor(new GapBuffer(''), { now: clock.now });
  typeKeys(e, clock, 'hello world', 100);
  assert.equal(e.undoSize, 2);
  e.undo();
  assert.equal(e.text, 'hello ');
  e.undo();
  assert.equal(e.text, '');
});

test('coalescing breaks after a pause and after a cursor jump', () => {
  const clock = fakeClock();
  const e = new Editor(new GapBuffer(''), { now: clock.now });
  typeKeys(e, clock, 'ab', 100);
  clock.advance(2000);
  typeKeys(e, clock, 'cd', 100);
  assert.equal(e.undoSize, 2, 'pause > 1 s');
  e.moveTo(0);
  typeKeys(e, clock, 'ef', 100);
  assert.equal(e.undoSize, 3, 'cursor jump');
  assert.equal(e.text, 'efabcd');
});

test('backspace and delete are undone with the cursor restored', () => {
  const e = new Editor(new GapBuffer('abcdef'));
  e.moveTo(3);
  e.backspace();
  e.deleteForward();
  assert.equal(e.text, 'abef');
  e.undo();
  e.undo();
  assert.equal(e.text, 'abcdef');
  assert.equal(e.cursor, 3);
});

test('replace all is one undo step', () => {
  const e = new Editor(new GapBuffer('cat sat on the cat mat'));
  assert.equal(e.replaceAll('cat', 'dog'), 2);
  assert.equal(e.text, 'dog sat on the dog mat');
  assert.equal(e.undoSize, 1);
  e.undo();
  assert.equal(e.text, 'cat sat on the cat mat');
});

test('bounded history drops the oldest step', () => {
  const e = new Editor(new GapBuffer(''), { maxHistory: 3 });
  for (const w of ['a', 'b', 'c', 'd', 'e']) { e.moveTo(e.text.length); e.type(w); }
  assert.equal(e.undoSize, 3);
  while (e.undo());
  assert.equal(e.text, 'ab');
});

test('property: 2,000 random edits match a plain string, and undo-all returns the original', () => {
  const rnd = seeded(42);
  const clock = fakeClock();
  const original = 'The quick brown fox jumps over the lazy dog.';
  const e = new Editor(new GapBuffer(original, 4), { now: clock.now, maxHistory: 100000 });
  const pick = (s) => s[Math.floor(rnd() * s.length)];
  const int = (n) => Math.floor(rnd() * n);
  let model = original;                 // reference: what the text must be, tracked with plain strings
  for (let step = 0; step < 2000; step++) {
    const op = int(100);
    if (op < 45) {
      const ch = pick(' abcde');
      model = model.slice(0, e.cursor) + ch + model.slice(e.cursor);
      e.type(ch);
    } else if (op < 60) {
      if (e.cursor > 0) model = model.slice(0, e.cursor - 1) + model.slice(e.cursor);
      e.backspace();
    } else if (op < 68) {
      if (e.cursor < model.length) model = model.slice(0, e.cursor) + model.slice(e.cursor + 1);
      e.deleteForward();
    } else if (op < 80) {
      e.moveTo(int(model.length + 1));
    } else if (op < 82) {
      const find = pick('abcde'), repl = pick(['', 'Z', 'xy']);
      model = model.split(find).join(repl);
      e.replaceAll(find, repl);
    } else if (op < 92) {
      e.undo();
      model = e.text;                   // undo is checked by the final undo-all below
    } else {
      e.redo();
      model = e.text;
    }
    if (int(5) === 0) clock.advance(int(2000));
    assert.equal(e.text, model, `step ${step}`);
  }
  const finalText = e.text;
  while (e.undo());
  assert.equal(e.text, original, 'undo everything = original text');
  while (e.redo());
  assert.equal(e.text, finalText, 'redo everything = final text');
});
