'use strict';

// Run: node --test
const test = require('node:test');
const assert = require('node:assert/strict');
const { MemFS, normalize } = require('./fs');

/** Tiny deterministic random generator (mulberry32), so a failing seed can be replayed. */
function seeded(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const code = (c) => (err) => err.code === c;   // assert.throws matcher on err.code

test('mkdir, mkdir -p, write, append, read, sorted ls', () => {
  const fs = new MemFS();
  assert.throws(() => fs.mkdir('/var/log/app'), code('ENOENT'));
  fs.mkdir('/var/log/app', { recursive: true });
  fs.mkdir('/var/log/app', { recursive: true });            // idempotent, like mkdir -p
  assert.throws(() => fs.mkdir('/var/log'), code('EEXIST'));
  fs.writeFile('/var/log/app/z.log', 'hello');
  fs.appendFile('/var/log/app/z.log', ' world');
  fs.writeFile('/var/log/app/a.log', '');
  assert.equal(fs.readFile('/var/log/app/z.log'), 'hello world');
  assert.deepEqual(fs.ls('/var/log/app'), ['a.log', 'z.log']);
  assert.throws(() => fs.readFile('/var/log'), code('EISDIR'));
  assert.throws(() => fs.readFile('/var/log/app/z.log/x'), code('ENOTDIR'));
});

test('path normalisation edge cases, lexical and through the tree', () => {
  assert.equal(normalize('/a/./b/../c'), '/a/c');
  assert.equal(normalize('/..'), '/');
  assert.equal(normalize('/a/b/'), '/a/b');
  assert.equal(normalize('//a///b'), '/a/b');
  const fs = new MemFS();
  fs.mkdir('/a/b', { recursive: true });
  fs.writeFile('/a/c', 'C');
  for (const p of ['/a/./b/../c', '//a///c', '/../../a/c', '/a/c/']) assert.equal(fs.readFile(p), 'C', p);
  assert.equal(fs.realpath('/a/b/..'), '/a');
});

test('mv renames, moves subtrees in O(1), and refuses to move a dir into itself', () => {
  const fs = new MemFS();
  fs.mkdir('/data/2025/q1', { recursive: true });
  fs.writeFile('/data/2025/q1/sales.csv', '1,2,3');
  fs.mkdir('/archive');
  fs.mv('/data/2025', '/archive');                            // into an existing dir: keeps the name
  assert.equal(fs.readFile('/archive/2025/q1/sales.csv'), '1,2,3');
  assert.equal(fs.realpath('/archive/2025/q1'), '/archive/2025/q1');
  assert.throws(() => fs.mv('/archive', '/archive/2025/q1/x'), code('EINVAL'));
  assert.throws(() => fs.mv('/archive', '/archive'), code('EINVAL'));
  fs.writeFile('/a.txt', 'A');
  fs.writeFile('/b.txt', 'B');
  fs.mv('/a.txt', '/b.txt');                                  // a file may replace a file
  assert.equal(fs.readFile('/b.txt'), 'A');
  assert.throws(() => fs.mv('/archive', '/b.txt'), code('EEXIST'));
});

test('rm needs recursive for a non-empty dir; du sums the subtree', () => {
  const fs = new MemFS();
  fs.mkdir('/srv/a/b', { recursive: true });
  fs.writeFile('/srv/one', '12345');
  fs.writeFile('/srv/a/two', '1234567890');
  fs.writeFile('/srv/a/b/three', '123');
  assert.equal(fs.du('/srv'), 18);
  assert.throws(() => fs.rm('/srv/a'), code('ENOTEMPTY'));
  fs.rm('/srv/a', { recursive: true });
  assert.equal(fs.du('/srv'), 5);
  assert.deepEqual(fs.ls('/srv'), ['one']);
});

test('find by extension and by size, depth-first in name order', () => {
  const fs = new MemFS();
  fs.mkdir('/repo/src/main', { recursive: true });
  fs.writeFile('/repo/src/main/App.java', 'class App {}');
  fs.writeFile('/repo/src/main/Util.java', 'x');
  fs.writeFile('/repo/Build.java', '');
  fs.writeFile('/repo/README.md', '# readme');
  assert.deepEqual(fs.find('/repo', (f) => f.type === 'file' && f.name.endsWith('.java')),
    ['/repo/Build.java', '/repo/src/main/App.java', '/repo/src/main/Util.java']);
  assert.deepEqual(fs.find('/repo', (f) => f.type === 'file' && f.size > 5),
    ['/repo/README.md', '/repo/src/main/App.java']);
});

test('symlinks to a file and to a directory; .. after a link is physical', () => {
  const fs = new MemFS();
  fs.mkdir('/opt/app-1.2/bin', { recursive: true });
  fs.writeFile('/opt/app-1.2/bin/run', 'v1.2');
  fs.symlink('/opt/app-1.2', '/opt/current');
  fs.symlink('bin/run', '/opt/app-1.2/start');                // relative target
  assert.equal(fs.readFile('/opt/current/bin/run'), 'v1.2');
  assert.equal(fs.readFile('/opt/current/start'), 'v1.2');
  assert.equal(fs.realpath('/opt/current/bin/../..'), '/opt');
  fs.rm('/opt/current');                                      // removes the link only
  assert.equal(fs.readFile('/opt/app-1.2/bin/run'), 'v1.2');
});

test('symlink loops are detected with a hop limit', () => {
  const fs = new MemFS();
  fs.symlink('/b', '/a');
  fs.symlink('/a', '/b');
  assert.throws(() => fs.readFile('/a'), code('ELOOP'));
  assert.equal(fs.du('/'), 4);                                // du never follows links: 2 + 2 bytes, no infinite loop
  fs.mkdir('/d');
  for (let i = 0; i < 41; i++) fs.symlink(i === 0 ? '/d' : `/c${i - 1}`, `/c${i}`);
  assert.equal(fs.realpath('/c39'), '/d');                    // 40 hops: allowed
  assert.throws(() => fs.realpath('/c40'), code('ELOOP'));    // 41 hops: ELOOP
});

test('random operations match a flat "path -> content" reference model', () => {
  for (const seed of [1, 2, 42]) {
    const rnd = seeded(seed);
    const pick = (s) => s[Math.floor(rnd() * s.length)];
    const randomPath = () => Array.from({ length: 1 + Math.floor(rnd() * 3) }, () => '/' + pick('abc')).join('');
    const fs = new MemFS();
    const model = new Map();                                  // full path -> content, or DIR
    const DIR = '<dir>';
    const parent = (p) => p.slice(0, p.lastIndexOf('/')) || '/';
    const isDir = (p) => p === '/' || model.get(p) === DIR;
    const exists = (p) => p === '/' || model.has(p);
    const under = (p, root) => p === root || p.startsWith(root + '/');

    const modelOps = [
      (p) => { if (!isDir(parent(p)) || exists(p)) return false; model.set(p, DIR); return true; },
      (p, q, t) => { if (!isDir(parent(p)) || isDir(p)) return false; model.set(p, t); return true; },
      (p, q, t) => { if (!isDir(parent(p)) || isDir(p)) return false; model.set(p, (model.get(p) ?? '') + t); return true; },
      (p) => { if (!exists(p)) return false; for (const k of [...model.keys()]) if (under(k, p)) model.delete(k); return true; },
      (p, q) => {
        if (!exists(p)) return false;
        const target = isDir(q) ? (q === '/' ? '' : q) + '/' + p.slice(p.lastIndexOf('/') + 1) : q;
        if (!isDir(parent(target))) return false;
        if (target === p) return true;
        if (target.startsWith(p + '/')) return false;
        if (exists(target) && (isDir(target) || isDir(p))) return false;
        const moved = [...model].filter(([k]) => under(k, p));   // O(n) re-keying: what a flat key space must do
        for (const [k] of moved) model.delete(k);
        for (const [k, v] of moved) model.set(target + k.slice(p.length), v);
        return true;
      },
    ];
    const realOps = [
      (p) => fs.mkdir(p),
      (p, q, t) => fs.writeFile(p, t),
      (p, q, t) => fs.appendFile(p, t),
      (p) => fs.rm(p, { recursive: true }),
      (p, q) => fs.mv(p, q),
    ];

    for (let step = 0; step < 3000; step++) {
      const op = Math.floor(rnd() * 5);
      const p = randomPath(), q = randomPath(), t = pick('xyz');
      let real = true;
      try { realOps[op](p, q, t); } catch (e) { if (!e.code) throw e; real = false; }
      const expected = modelOps[op](p, q, t);
      assert.equal(real, expected, `seed ${seed} step ${step} op ${op} ${p} ${q}`);
    }
    const listing = {};
    for (const path of fs.find('/')) {
      if (path !== '/') listing[path] = fs.isDirectory(path) ? DIR : fs.readFile(path);
    }
    assert.deepEqual(listing, Object.fromEntries([...model].sort()), `seed ${seed}: final tree`);
  }
});
