# KV Store — L6 (Staff) LLD Interview

> **Level expectation:** the L5 store has one client. Now: *"Thousands of clients connect over the network and run transactions concurrently, we need snapshots without pausing, and a replica for failover."* You reason about isolation, MVCC vs locking vs optimistic transactions, threading models, background persistence, and replication, and you compare with how Redis, etcd and Postgres actually do it. Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. The new problem: many clients, one dataset

**🧑‍💼 Interviewer:** Two clients each `BEGIN`, change overlapping keys, and one rolls back. What happens in your L5 design?

**🧑‍💻 Candidate:** It breaks. The undo stack belongs to **the store**, not to a client. Client A's `BEGIN` and client B's `SET` end up in the same layer, and A's rollback would undo B's work. Transactions must become **per session**, and then we must decide what each session **sees** of the others' uncommitted changes. That's **isolation** ([transactions & isolation](../../concepts/transactions-and-isolation.md)).

Three families of answers:

| Approach | How | Used by |
|---|---|---|
| **No concurrent transactions** | Queue each transaction's commands; execute the whole transaction at once on the single thread (no interleaving at all) | Redis `MULTI`/`EXEC` |
| **Optimistic** | Run without locks; at commit, check that nothing you read was changed by others; if it was, abort and retry | Redis `WATCH`, etcd `Txn` (compare-and-swap on revisions) |
| **MVCC** (multi-version concurrency control) | Writers create new versions; each transaction reads a consistent snapshot; conflicts detected at commit | Postgres, MySQL InnoDB, etcd (revisions), CockroachDB |

---

## 2. Option A: Redis-style: queue, then execute atomically

```text
client A: MULTI → SET x 1 → INCR y → EXEC   (commands only QUEUED until EXEC)
server:   on EXEC, run all queued commands back-to-back on the single thread
```

- **Isolation for free:** single-threaded execution means nothing interleaves with an executing transaction.
- **But:** you can't read a value mid-transaction and decide what to write based on it (reads return `QUEUED`). There's **no rollback**: if a command fails at runtime, the others still apply.
- For read-then-write logic, Redis offers **`WATCH`**: if a watched key changes before `EXEC`, the transaction aborts and the client retries (optimistic concurrency, [optimistic vs pessimistic](../../concepts/optimistic-vs-pessimistic-locking.md)). Or a **Lua script**, which runs atomically on the server.

> 📝 **Note:** Explaining that Redis "transactions" give atomic execution but not rollback, and that `WATCH` is optimistic locking, shows you know the real tool, not just the textbook.

## 3. Option B: MVCC with versions

**🧑‍💻 Candidate:** If clients need interactive transactions (read, think, write, rollback) with isolation, store **versions**:

```text
key "balance:alice":  [v1: 100 (commit ts 10)] → [v2: 70 (commit ts 17)]
transaction T started at ts 15 → reads 100 (latest version committed ≤ 15)
```

- Each transaction gets a **start timestamp** and reads the newest version committed before it: a consistent **snapshot**, and readers never block writers.
- Writes are buffered in the transaction (like our pending log) and installed at commit with a **commit timestamp**.
- **Conflict check at commit** (first-committer-wins): if another transaction committed a newer version of a key I wrote after my start, abort. That's **snapshot isolation**. Full **serializable** needs more (tracking reads, as Postgres SSI does).
- Old versions need **garbage collection** once no running transaction can see them (Postgres `VACUUM`, etcd compaction).
- Our L5 undo log becomes unnecessary for rollback (uncommitted writes never touched shared data), but versions cost memory and GC work.

## 4. Threading model

| Model | Pros | Cons |
|---|---|---|
| **Single-threaded executor** (Redis) | No locks, atomic commands, simple, predictable latency; ~100k+ ops/s per core | One core; one slow command (e.g. `KEYS *`) blocks everyone |
| **Single thread + I/O threads** (Redis 6+) | Network parsing/writing in parallel, execution still single-threaded | Still one core for execution |
| **Sharded single-threaded** (e.g. Dragonfly, KeyDB-style, or N Redis instances) | Scales across cores; each shard owns its keys | Multi-key transactions across shards need coordination |
| **Multi-threaded with locks** | Uses all cores for one dataset | Lock contention, deadlocks, harder reasoning |

**🧑‍💻 Candidate:** For an in-memory store, the single-writer model ([single-writer principle](../../concepts/single-writer-principle.md)) plus sharding is usually the best trade-off: most operations take ~1 µs, so the bottleneck is network I/O, not CPU. Our L5 store already assumes a single executing thread, so a network server would put commands from all connections onto one queue (like the [elevator system](../elevator-system/L5-senior.md#33-concurrency-commands--a-single-writer)).

---

## 5. Snapshots without stopping the world

**🧑‍💻 Candidate:** A full snapshot of 50 GB can take a minute to write. Blocking all commands for a minute is unacceptable. Options:
- **Fork + copy-on-write** (Redis RDB/BGREWRITEAOF): the OS `fork()`s the process; the child sees a frozen copy of memory and writes it out, while the parent keeps serving. Memory pages are only physically copied when the parent modifies them. Risk: a write-heavy workload during the snapshot can nearly **double memory**, which is a classic Redis OOM incident.
- **MVCC snapshot:** with versions, a snapshot is just "read everything as of timestamp T", while writes continue creating newer versions.
- **Incremental:** snapshot key ranges in chunks, interleaved with normal work, plus the log covering changes made during the snapshot.

> 🛠️ **Infra note:** "Redis needs ~2× RAM headroom during BGSAVE on write-heavy instances" is exactly the kind of production knowledge that comes from on-call. Mention it if you've seen it.

---

## 6. Replication and failover

- **Asynchronous replication** (Redis default): the primary streams its command log to replicas. Fast, but a failover can lose the last writes acknowledged by the primary but not yet replicated. `WAIT N timeout` can make a client wait for N replicas, reducing (not eliminating) that window.
- **Consensus replication** (etcd): every write goes through **Raft**: committed only when a majority has it in their logs ([consensus & Raft](../../../HLD/concepts/consensus-and-raft.md)). Slower per write, but no acknowledged write is lost on failover. That's why Kubernetes stores cluster state in etcd, not Redis.
- The log we built in L5 is the same thing replication ships: **the log is the database**, and data structures are a cache of it ([ledgers & event sourcing](../../concepts/ledgers-and-event-sourcing.md)).

---

## 7. Curveballs

**🧑‍💼 Interviewer:** A customer's Redis latency spikes to seconds every few minutes.

**🧑‍💻 Candidate:** Usual suspects: (1) **fork** for RDB/AOF rewrite on a large instance: `fork()` copies page tables, which can take hundreds of ms on tens of GB, especially without huge pages tuned; (2) a slow **O(n) command** (`KEYS *`, big `SMEMBERS`, deleting a huge key) blocking the single thread (check `SLOWLOG`); (3) **fsync** stalls with `appendfsync always` on a busy disk; (4) swap. Each has a known fix: schedule/limit snapshots, `SCAN` instead of `KEYS`, `UNLINK` for big deletes, `everysec`, disable swap.

**🧑‍💼 Interviewer:** Should our team build its own KV store?

**🧑‍💻 Candidate:** Almost certainly not. Redis/Valkey, etcd, RocksDB (embedded), and managed services cover nearly every need, and the hard parts (crash safety, replication, edge cases found by years of production) are exactly what you'd get wrong. Building this one is for understanding: it makes you much better at *operating* and *choosing* the real ones.

---

## 8. What the interviewer was evaluating (L6)

- [ ] Saw that a shared undo stack breaks with multiple clients; moved to per-session transactions
- [ ] Compared queue-and-execute (Redis), optimistic (WATCH / CAS), and MVCC with their isolation guarantees
- [ ] Explained snapshot isolation, conflict detection and version GC
- [ ] Threading models and why single-threaded + sharding fits in-memory stores
- [ ] Non-blocking snapshots: fork/COW (and its memory risk), MVCC, incremental
- [ ] Async vs consensus replication; why etcd uses Raft
- [ ] Production debugging knowledge (fork latency, O(n) commands, fsync stalls)

## 9. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| One global transaction stack for all clients | Clients undo each other's work |
| Claiming Redis MULTI/EXEC has rollback | It doesn't; shows shallow knowledge of the tool |
| Adding locks to make it "multi-threaded" without measuring | Slower and buggier than a single thread for µs-scale ops |
| Blocking snapshots on large datasets | Minutes of downtime |
| Ignoring fork's memory overhead | OOM kills under write-heavy load |
| Treating async replication as lossless | Acknowledged writes lost on failover |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
