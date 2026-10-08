'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { MapLruCache, LinkedLruCache } = require('./lruCache');

for (const Impl of [MapLruCache, LinkedLruCache]) {
  test(`${Impl.name}: evicts least recently used`, () => {
    const c = new Impl(3);
    c.put('a', 1); c.put('b', 2); c.put('c', 3);
    c.get('a');      // b is now least recent
    c.put('d', 4);   // evicts b
    assert.equal(c.get('b'), undefined);
    assert.deepEqual(c.keysMostRecentFirst(), ['d', 'a', 'c']);
  });

  test(`${Impl.name}: update refreshes and does not grow`, () => {
    const c = new Impl(2);
    c.put('a', 1); c.put('b', 2); c.put('a', 10); c.put('c', 3);
    assert.equal(c.size, 2);
    assert.equal(c.get('a'), 10);
    assert.equal(c.get('b'), undefined);
  });

  test(`${Impl.name}: remove`, () => {
    const c = new Impl(1);
    c.put('a', 1);
    assert.equal(c.remove('a'), true);
    assert.equal(c.remove('a'), false);
    assert.equal(c.size, 0);
  });
}

test('both implementations agree on 50k random operations', () => {
  const a = new MapLruCache(20);
  const b = new LinkedLruCache(20);
  let seed = 7;
  const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % n; };
  for (let i = 0; i < 50_000; i++) {
    const key = rnd(50);
    const op = rnd(3);
    if (op === 0) { a.put(key, i); b.put(key, i); }
    else if (op === 1) assert.equal(a.get(key), b.get(key), `get(${key}) at op ${i}`);
    else assert.equal(a.remove(key), b.remove(key));
  }
  assert.deepEqual(a.keysMostRecentFirst(), b.keysMostRecentFirst());
});
