# fs and Durability in Node (append, fsync, atomic rename, readline)

## 1. One-line summary

Node's built-in `fs` module writes files through three API styles (sync, callback, promise); none of the convenient ones (`appendFileSync`, `writeFile`, streams) **fsync**, so for durability you open a file descriptor and call `fsyncSync(fd)` / `fileHandle.sync()` yourself, and you replace snapshots with write-temp → fsync → `rename`.

## 2. The problem it solves

The JS version of the kv-store needs an append-only file (AOF):

```js
fs.appendFileSync('data.aof', 'SET a 1\n');   // done... durable?
```

No. The bytes reached the **OS page cache** (💡 RAM where the kernel keeps file data and writes it to disk a few seconds later). That survives a Node crash, but not a power cut or kernel panic. Making it durable needs **fsync** (💡 a system call that waits until the data is physically on the disk). Concepts: [durability-wal-and-snapshots](../../concepts/durability-wal-and-snapshots.md).

## 3. How it works

### Three API styles for the same operations

| Style | Example | Blocks the event loop? |
|---|---|---|
| Sync | `fs.appendFileSync(p, s)` | **yes**, until the OS call returns |
| Callback | `fs.appendFile(p, s, err => {...})` | no; work runs on libuv's thread pool |
| Promise | `await fs.promises.appendFile(p, s)` (or `import { appendFile } from 'node:fs/promises'`) | no |

💡 **Event loop**: Node runs all your JS on one thread; while a sync call blocks, no other request, timer or callback runs ([event-loop-and-concurrency](event-loop-and-concurrency.md)). 💡 **libuv thread pool**: 4 background threads (by default) Node uses to run blocking file operations for the async APIs.

**When sync is fine**: startup (loading a snapshot before you accept connections), CLIs and scripts, tests — and a single-threaded store like Redis that intentionally does its "always" fsync on the command path. In a busy HTTP server, a 5 ms sync fsync stalls *every* client for 5 ms.

### Descriptor-level calls: where fsync lives

```js
import fs from 'node:fs';

const fd = fs.openSync('data.aof', 'a');        // 'a' = append, create if missing
fs.writeSync(fd, 'BEGIN\nSET a 1\nCOMMIT\n');   // → page cache
fs.fsyncSync(fd);                                // → disk (data + metadata)
// fs.fdatasyncSync(fd)                          // data only: sometimes faster
fs.closeSync(fd);                                // close does NOT fsync
```

💡 A **file descriptor (fd)** is a small integer the OS gives you for an open file; every later call refers to it.

### An append-only log with an fsync policy

```js
import fs from 'node:fs';

export class AppendLog {
  #fd; #policy; #lastSync = 0;
  constructor(path, policy = 'everysec') {     // 'always' | 'everysec' | 'no'
    this.#fd = fs.openSync(path, 'a');
    this.#policy = policy;
  }
  appendBatch(text, now = Date.now()) {
    fs.writeSync(this.#fd, text);               // string → UTF-8 bytes → page cache
    if (this.#policy === 'always' ||
        (this.#policy === 'everysec' && now - this.#lastSync >= 1000)) {
      fs.fdatasyncSync(this.#fd);
      this.#lastSync = now;
    }
  }
  close() { fs.fsyncSync(this.#fd); fs.closeSync(this.#fd); }
}
```

Private `#fields` are explained in [classes-and-private-fields](classes-and-private-fields.md). Passing `now` in makes the policy testable with a fake clock in [node:test](node-test-runner.md). With `everysec`, an idle log is never synced; real Redis uses a background timer for that (`setInterval(...).unref()`, see [async-await-and-timers](async-await-and-timers.md)).

### Promise version (servers)

```js
import { open } from 'node:fs/promises';

const fh = await open('data.aof', 'a');
await fh.write('SET a 1\n');
await fh.sync();                                // fsync, off the main thread
await fh.close();
```

### Atomic snapshot: temp → fsync → rename → fsync dir

```js
import fs from 'node:fs';
import path from 'node:path';

export function writeSnapshotAtomically(target, contents) {
  const dir = path.dirname(path.resolve(target));
  const tmp = `${target}.tmp`;                   // same directory = same file system
  const fd = fs.openSync(tmp, 'w');
  fs.writeSync(fd, contents);
  fs.fsyncSync(fd);                              // 1. temp bytes on disk
  fs.closeSync(fd);
  fs.renameSync(tmp, target);                    // 2. atomic replace on POSIX
  const dfd = fs.openSync(dir, 'r');             // 3. persist the rename (Linux/macOS;
  try { fs.fsyncSync(dfd); } finally { fs.closeSync(dfd); } //    Windows throws, skip there)
}
```

A reader (or the restarted process) sees either the complete old snapshot or the complete new one — never half. 💡 The rename edits the **directory** (a list of file names), which is why the directory needs its own fsync.

### Reading a log line by line

```js
import fs from 'node:fs';
import readline from 'node:readline';

const rl = readline.createInterface({
  input: fs.createReadStream('data.aof', { encoding: 'utf8' }),
  crlfDelay: Infinity,                           // treat \r\n as one line break
});
for await (const line of rl) {
  // parse; buffer lines between BEGIN and COMMIT, apply only complete batches
}
```

For a small file at startup, `fs.readFileSync(p, 'utf8').split('\n')` is simpler. Note: if the file doesn't end with `\n`, the last element is a possibly **torn** line (💡 a record cut off mid-write by a crash) — exactly what end markers or checksums are for.

## 4. When to use it

- `appendFileSync` / `writeFileSync`: scripts, tests, non-critical logs.
- `openSync` + `writeSync` + `fsyncSync`: a single-threaded store's AOF, CLIs that must not lose data.
- `fs/promises` + `fh.sync()`: durability inside a server that must stay responsive.
- Temp + `renameSync`: snapshots, generated config files, any "replace the whole file" operation.

## 5. When NOT to use it

- **Sync APIs in a request handler** of a busy server — they freeze the event loop.
- **`fs.createWriteStream` for durable logs** — `write()` returns before data is written, there's no built-in fsync, and you must wait for `'finish'`; fine for bulk export, awkward for commit semantics.
- **Concurrent `fs.promises.writeFile` on the same file** without awaiting — the Node docs call this unsafe; writes can interleave.
- **Hand-rolled persistence for production** — use SQLite (`node:sqlite`, experimental in Node 22), Redis, or Postgres.

## 6. Commonly confused with

| Call | Data goes to | Survives power loss? |
|---|---|---|
| `appendFileSync` / `writeFileSync` / `writeSync` | page cache | no |
| `closeSync(fd)` | — (no flush to disk) | no |
| `fdatasyncSync(fd)` | disk (data) | yes |
| `fsyncSync(fd)` / `fh.sync()` | disk (data + metadata) | yes |
| `renameSync(tmp, target)` | directory entry swapped atomically | after dir fsync |

| | `fs.readFileSync(...).split('\n')` | `readline` over a stream |
|---|---|---|
| Memory | whole file | one chunk at a time |
| Blocks | yes | no |
| Best for | small files at startup | large logs |

## 7. Common mistakes / misuse

1. Believing `appendFileSync` or `closeSync` makes data durable.
2. Calling `appendFileSync` per command on a hot path — each opens and closes the file; keep one fd open.
3. Forgetting `'utf8'` when reading → you get a `Buffer`, and `buffer.split` doesn't exist.
4. Firing many async appends without awaiting → order between them isn't guaranteed.
5. Rename across file systems (`/tmp` → data dir) → `EXDEV` error, not atomic.
6. Not handling a torn last line on replay.

## 8. Interview cheat-sheet

- "In Node, appendFileSync only reaches the page cache; for durability I keep an fd open and call fsyncSync after each committed batch, per the policy."
- "Sync fs calls block the event loop — fine at startup, in a CLI, or in a deliberately single-threaded store; in a server I'd use fs/promises and fh.sync()."
- "Snapshots: write a temp file in the same directory, fsync, renameSync over the target, then fsync the directory."
- "Replay uses readline and applies a batch only when its COMMIT marker is present, so a torn tail is ignored."

## 9. Used in

- [LLD: Design an In-Memory Key-Value Store with Transactions](../../interviews/kv-store/README.md) — JS version: append-only file with `always` / `everysec` / `no` policies, atomic snapshot rewrite, replay that skips an incomplete trailing batch.
- Related: [durability-wal-and-snapshots](../../concepts/durability-wal-and-snapshots.md), [event-loop-and-concurrency](event-loop-and-concurrency.md), [node-test-runner](node-test-runner.md), [async-await-and-timers](async-await-and-timers.md).
