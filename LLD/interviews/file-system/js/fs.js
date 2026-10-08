'use strict';

// An in-memory file system: a tree of directories, files and symlinks.
// Same design as the Java version, smaller (no users or permissions). Node 22, no dependencies.
// Paths must be absolute. Node.js is single-threaded, so no locks are needed: each call runs to
// completion before the next one starts.

const MAX_SYMLINK_HOPS = 40;   // Linux's limit (MAXSYMLINKS); more than that = ELOOP

/** An Error with a Linux-style code, like Node's own fs errors (err.code === 'ENOENT'). */
class FsError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.code = code;
  }
}

class Dir {
  /** Map keeps insertion order, not sorted order, so ls() sorts. */
  children = new Map();
  constructor(name, parent) { this.name = name; this.parent = parent; }
  get size() {
    let total = 0;
    for (const child of this.children.values()) total += child.size;   // Composite: recursive
    return total;
  }
}

class File {
  content = '';
  constructor(name, parent) { this.name = name; this.parent = parent; }
  get size() { return Buffer.byteLength(this.content, 'utf8'); }
}

class Link {
  constructor(name, parent, target) { this.name = name; this.parent = parent; this.target = target; }
  get size() { return this.target.length; }
}

/** '/a//b/./c/' -> ['a', 'b', '.', 'c']: empty parts dropped, '.' and '..' kept. */
function parts(path) { return path.split('/').filter((p) => p !== ''); }

function split(path) {
  if (typeof path !== 'string' || !path.startsWith('/')) throw new FsError('EINVAL', `expected an absolute path: ${path}`);
  return parts(path);
}

/** Lexical normalisation: '/a/./b/../c' -> '/a/c'; '..' at the root stays at the root. */
function normalize(path) {
  const out = [];
  for (const part of split(path)) {
    if (part === '.') continue;
    if (part === '..') out.pop();
    else out.push(part);
  }
  return '/' + out.join('/');
}

function pathOf(node) {
  const names = [];
  for (let n = node; n.parent; n = n.parent) names.push(n.name);
  return '/' + names.reverse().join('/');
}

class MemFS {
  #root = new Dir('', null);

  // ------------------------------------------------------------ path walk

  /** Walks one name at a time; symlinks in the middle (or at the end if followLast) are expanded. */
  #resolve(path, followLast = true) {
    const todo = split(path);
    let cur = this.#root;
    let hops = 0;
    while (todo.length > 0) {
      if (!(cur instanceof Dir)) throw new FsError('ENOTDIR', path);
      const part = todo.shift();
      const next = part === '.' ? cur : part === '..' ? (cur.parent ?? cur) : cur.children.get(part);
      if (!next) throw new FsError('ENOENT', path);
      if (next instanceof Link && (followLast || todo.length > 0)) {
        if (++hops > MAX_SYMLINK_HOPS) throw new FsError('ELOOP', path);
        todo.unshift(...parts(next.target));
        if (next.target.startsWith('/')) cur = this.#root;   // relative targets start at the link's dir
        continue;
      }
      cur = next;
    }
    return cur;
  }

  #tryResolve(path) {
    try { return this.#resolve(path); } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }

  /** The parent directory and the final name of a path. */
  #parentOf(path) {
    const names = split(path);
    const name = names.pop();
    if (name === undefined || name === '.' || name === '..') throw new FsError('EINVAL', `path must end in a name: ${path}`);
    const dir = this.#resolve('/' + names.join('/'));
    if (!(dir instanceof Dir)) throw new FsError('ENOTDIR', path);
    return { dir, name };
  }

  // ------------------------------------------------------------ operations

  mkdir(path, { recursive = false } = {}) {
    if (recursive) {
      const names = split(path);
      for (let i = 1; i <= names.length; i++) {
        const prefix = '/' + names.slice(0, i).join('/');
        const existing = this.#tryResolve(prefix);
        if (!existing) this.mkdir(prefix);
        else if (!(existing instanceof Dir)) throw new FsError('EEXIST', prefix);
      }
      return;
    }
    const { dir, name } = this.#parentOf(path);
    if (dir.children.has(name)) throw new FsError('EEXIST', path);
    dir.children.set(name, new Dir(name, dir));
  }

  writeFile(path, text) { this.#fileForWriting(path).content = text; }

  appendFile(path, text) { this.#fileForWriting(path).content += text; }

  readFile(path) {
    const node = this.#resolve(path);
    if (!(node instanceof File)) throw new FsError('EISDIR', path);
    return node.content;
  }

  symlink(target, path) {
    const { dir, name } = this.#parentOf(path);
    if (dir.children.has(name)) throw new FsError('EEXIST', path);
    dir.children.set(name, new Link(name, dir, target));
  }

  ls(path) {
    const node = this.#resolve(path);
    return node instanceof Dir ? [...node.children.keys()].sort() : [node.name];
  }

  realpath(path) { return pathOf(this.#resolve(path)); }

  isDirectory(path) { return this.#resolve(path) instanceof Dir; }

  du(path) { return this.#resolve(path).size; }

  rm(path, { recursive = false } = {}) {
    const { dir, name } = this.#parentOf(path);
    const node = dir.children.get(name);
    if (!node) throw new FsError('ENOENT', path);
    if (node instanceof Dir && node.children.size > 0 && !recursive) throw new FsError('ENOTEMPTY', path);
    dir.children.delete(name);
    node.parent = null;
  }

  /** mv: into an existing dir keeps the name; never into its own subtree; a file may replace a file. */
  mv(src, dst) {
    const from = this.#parentOf(src);
    const node = from.dir.children.get(from.name);
    if (!node) throw new FsError('ENOENT', src);
    const dstNode = this.#tryResolve(dst);
    const to = dstNode instanceof Dir ? { dir: dstNode, name: from.name } : this.#parentOf(dst);
    for (let a = to.dir; a; a = a.parent) {
      if (a === node) throw new FsError('EINVAL', `cannot move ${src} into its own subtree ${dst}`);
    }
    const existing = to.dir.children.get(to.name);
    if (existing === node) return;
    if (existing && !(existing instanceof File && node instanceof File)) throw new FsError('EEXIST', dst);
    from.dir.children.delete(from.name);
    node.name = to.name;
    node.parent = to.dir;
    to.dir.children.set(to.name, node);
  }

  /** Depth-first, children in sorted order, symlinks not followed. filter gets { path, name, type, size }. */
  find(start, filter = () => true) {
    const out = [];
    const stack = [this.#resolve(start)];
    while (stack.length > 0) {
      const node = stack.pop();
      const info = {
        path: pathOf(node),
        name: node.name,
        type: node instanceof Dir ? 'dir' : node instanceof File ? 'file' : 'link',
        size: node.size,
      };
      if (filter(info)) out.push(info.path);
      if (node instanceof Dir) {
        const names = [...node.children.keys()].sort().reverse();   // reversed so the smallest pops first
        for (const n of names) stack.push(node.children.get(n));
      }
    }
    return out;
  }

  #fileForWriting(path) {
    const node = this.#tryResolve(path);
    if (node instanceof File) return node;
    if (node) throw new FsError('EISDIR', path);
    const { dir, name } = this.#parentOf(path);
    if (dir.children.has(name)) throw new FsError('EEXIST', path);   // e.g. a dangling symlink
    const file = new File(name, dir);
    dir.children.set(name, file);
    return file;
  }
}

module.exports = { MemFS, FsError, normalize, MAX_SYMLINK_HOPS };
