# Durability: Write-Ahead Logs, fsync and Snapshots

## 1. One-line summary

**Durable** means "once I told you OK, the change survives a crash or power cut"; you get it by appending each committed change to a log file, forcing it to the physical disk with **fsync**, and periodically compacting the log into a **snapshot** so recovery stays fast.

## 2. The problem it solves

An in-memory store (like the kv-store interview or [Redis](../../HLD/technologies/redis.md)) loses everything on restart. The obvious fixes both hurt:

- **Dump the whole map to disk on every write** — rewriting 1 GB to change 10 bytes.
- **Dump the whole map every 5 minutes** — a crash loses up to 5 minutes of writes.

The middle ground every database uses: **append only the change** (cheap, sequential) to a log, and make *that* durable. Infra analogy: the etcd behind your k8s cluster does exactly this — every write goes to its WAL and is fsync'd before the API server gets "OK", and etcd snapshots periodically so the WAL doesn't grow forever.

## 3. How it works

### Why `write()` is not "on disk"

💡 **syscall**: a request from your program to the operating system kernel (e.g. `write`, `fsync`).
💡 **Page cache**: RAM the OS uses to hold file data. A `write()` call copies your bytes into the page cache and returns in microseconds; the kernel writes them to the disk **later** (on Linux, typically within ~5–30 s).

So after `write()` returns:

| Event | Data in page cache survives? |
|---|---|
| Your process crashes / is OOM-killed / `kill -9` | **yes** — the kernel still owns the page cache and will write it |
| Kernel panic, power loss, VM host dies | **no** — RAM is gone |

💡 **fsync**: a syscall that blocks until the file's data (and metadata, like its size) have actually reached the storage device. Only after `fsync` returns is the write durable against power loss. (`fdatasync` skips metadata that isn't needed to read the data back.)

```mermaid
flowchart LR
    A["app buffer<br/>(BufferedWriter)"] -->|"flush() → write()"| B["OS page cache (RAM)"]
    B -->|"fsync() or kernel writeback"| C["disk controller cache"]
    C -->|"device flush"| D["persistent media"]
```

Note the **two** buffers before the OS: a Java `BufferedWriter`'s internal buffer is lost even on a plain process crash if you didn't `flush()`. See [file-io-and-fsync](../libraries/java/file-io-and-fsync.md) and [fs-and-durability-in-node](../libraries/js/fs-and-durability-in-node.md).

### What fsync costs

💡 Units: 1 ms (millisecond) = 1,000 µs (microseconds); 1 µs = 1,000 ns (nanoseconds). A `HashMap.put` is ~tens of ns.

| Storage | Typical fsync latency (varies a lot) |
|---|---|
| Datacenter NVMe SSD with power-loss protection | ~20 µs – 0.2 ms |
| Consumer SSD / laptop | ~0.5 – several ms |
| Cloud network disk (EBS, Persistent Disk) | ~1 – 5 ms |
| Spinning HDD | ~5 – 15 ms |

Arithmetic: at 2 ms per fsync, fsync-per-write caps you at 1 s / 2 ms = **500 writes/s** per log, versus millions of in-memory ops/s. The fix is **group commit**: collect every commit that arrived in the last few ms and fsync once for all of them — 1 fsync for 200 commits gives ~100,000 commits/s with the same disk.

### fsync policies (Redis `appendfsync`)

| Policy | When fsync happens | What a power loss can lose | Throughput |
|---|---|---|---|
| `always` | before replying to the client (Redis batches one fsync per event-loop round) | nothing acknowledged | lowest |
| `everysec` (Redis default for AOF) | a background thread, once per second | ~1 s of writes (up to ~2 s if the disk is slow) | near in-memory |
| `no` | whenever the OS decides | ~up to 30 s on Linux defaults | highest |

A process crash (not power loss) loses almost nothing even with `no`, because the page cache survives. The policy is a **knob between latency and data loss**, just like choosing `acks=all` vs `acks=1` in a [Kafka](../../HLD/technologies/kafka.md) producer.

### Append-only logs and torn writes

Appending is fast (sequential disk writes, no seeking) and simple (never modify old bytes). But a crash can stop a write **halfway**: 💡 a **torn write** is a record of which only the first part reached disk — e.g. `SET user:1 Al` instead of `SET user:1 Alice\n`.

Ways to detect the broken tail during recovery:

1. **Length prefix + checksum** per record: `[length][CRC32][payload]`. 💡 **CRC32** is a cheap 32-bit fingerprint of bytes; if any byte differs, it almost certainly won't match. A short read or a mismatch ⇒ torn tail.
2. **Begin/end markers** around a transaction batch: `BEGIN 3 … COMMIT`. A batch without its end marker is ignored, so a transaction is replayed **all or nothing** (atomicity across the crash).
3. Then **truncate** the file at the last good record so new appends don't land after garbage. Redis does this with `aof-load-truncated yes`; `redis-check-aof --fix` does it offline.

```java
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

final class Frames {
    static void append(DataOutputStream out, String batch) throws IOException {
        byte[] payload = batch.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(payload);
        out.writeInt(payload.length);              // length prefix
        out.writeLong(crc.getValue());             // checksum
        out.write(payload);
    }

    /** Returns every complete, valid batch; stops at the first torn or corrupt one. */
    static List<String> readAll(DataInputStream in) throws IOException {
        List<String> batches = new ArrayList<>();
        while (true) {
            try {
                int len = in.readInt();
                long expected = in.readLong();
                if (len < 0 || len > 64 << 20) break;           // garbage length
                byte[] payload = in.readNBytes(len);
                if (payload.length < len) break;                // torn: file ended mid-record
                CRC32 crc = new CRC32(); crc.update(payload);
                if (crc.getValue() != expected) break;          // corrupt bytes
                batches.add(new String(payload, StandardCharsets.UTF_8));
            } catch (EOFException e) { break; }                 // ended inside the header
        }
        return batches;
    }
}
```

### Compaction and snapshots

The log grows forever: `SET counter 1`, `SET counter 2`, … a million times is a million records for **one** key. Two fixes:

- **Log rewrite / compaction**: write a new log containing just the current state (`SET counter 1000000`), then atomically swap it in. Redis `BGREWRITEAOF` does this in a **forked** child process (💡 `fork` creates a copy of the process; the OS shares memory pages between parent and child and copies a page only when one of them writes to it — copy-on-write), so the main thread keeps serving.
- **Snapshot + log tail**: save the full state to a snapshot file (Redis **RDB**), remember the log position, start a fresh log. Recovery = load snapshot, replay only the tail. Since Redis 7, the AOF is "multi-part": a base snapshot file + incremental files + a manifest.

The swap must be crash-safe: write `snapshot.tmp` → fsync it → **rename** over `snapshot` (atomic on POSIX file systems) → fsync the directory so the rename itself is durable.

### Recovery time vs log size

Replay time ≈ records since the last snapshot ÷ replay speed.

- 10,000 writes/s, no compaction for a day: 10,000 × 86,400 = **864M records**. At ~1M records/s replay ⇒ 864 s ≈ **14 min** of downtime on restart.
- Snapshot every hour: at most 10,000 × 3,600 = 36M records ⇒ **~36 s**.

So snapshot frequency is a direct knob on restart time (your pod's readiness delay) — the same trade-off as Kafka log compaction or etcd's `--snapshot-count`.

## 4. When to use it

- Any state that is the **source of truth** and must survive restarts: databases, queues, lock services, ledgers ([ledgers-and-event-sourcing](ledgers-and-event-sourcing.md)).
- Append-only log when writes are small and frequent; snapshot when the log is long relative to the live state.
- `always`/group commit for money and orders; `everysec` for sessions, counters, rate limits.

## 5. When NOT to use it

- **Pure caches** — the source of truth is elsewhere; persistence just slows restart and writes.
- **fsync per write on slow disks at high QPS** — you'll cap at a few hundred writes/s; batch instead.
- **Rewriting the whole file in place for every change** — not atomic, and a crash mid-write corrupts everything.
- **Trusting durability on one machine for critical data** — a dead disk loses it all; you also need replication ([sharding-and-replication](../../HLD/concepts/sharding-and-replication.md)).

## 6. Commonly confused with

| | Append-only log (AOF/WAL) | Snapshot (RDB) |
|---|---|---|
| Write cost | tiny per change | full state each time |
| Data loss window | ≤ fsync interval | since the last snapshot |
| Recovery speed | slow if long | fast (load one file) |
| File size | grows until compacted | ≈ live data |

| | `flush()` | `fsync` |
|---|---|---|
| Moves data | app buffer → OS page cache | page cache → disk |
| Survives process crash | yes | yes |
| Survives power loss | **no** | yes |

## 7. Common mistakes / misuse

1. Acknowledging the client **before** the log write/fsync — then "committed" data can vanish.
2. Assuming `close()` or `flush()` means durable.
3. Not handling a torn tail — recovery crashes on garbage, or worse, replays half a transaction.
4. Retrying a failed fsync and trusting it: on Linux a failed fsync can drop the dirty pages, so a retry "succeeds" without the data (Postgres's 2018 "fsyncgate"). Safest response: crash and recover from the log.
5. Snapshot written in place instead of temp + fsync + rename + directory fsync.
6. Never compacting, then wondering why restarts take 20 minutes.

## 8. Interview cheat-sheet

- "Durable means it survives power loss, which requires fsync — a write only reaches the OS page cache."
- "I append each committed transaction to an append-only file as one batch framed by length and checksum, and replay on startup."
- "fsync policy is configurable like Redis: always, every second, or never — trading latency for at most ~1 s of loss."
- "On recovery I stop at the first incomplete or corrupt batch and truncate there, so a torn write never applies half a transaction."
- "To bound recovery time I compact the log into a snapshot: write temp, fsync, atomic rename."

## 9. Used in

- [LLD: Design an In-Memory Key-Value Store with Transactions](../interviews/kv-store/README.md) — AOF with batch markers, fsync policies (always / every second / never), torn-write detection on replay, snapshot compaction.
- Related: [undo-logs-and-redo-logs](undo-logs-and-redo-logs.md), [transactions-and-isolation](transactions-and-isolation.md), [HLD: Redis](../../HLD/technologies/redis.md), [HLD: PostgreSQL](../../HLD/technologies/postgresql.md), [HLD: Kafka](../../HLD/technologies/kafka.md).
