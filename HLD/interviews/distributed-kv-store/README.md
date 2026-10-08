# HLD Interview: Design a Distributed Key-Value Store (Dynamo / Cassandra style)

> "Design a key-value store that holds hundreds of terabytes, serves a million operations per second with low latency, and keeps working when machines fail."

This is the interview where you build the **database itself**, not use one. It brings together almost every distributed-systems idea: partitioning, replication, quorums, conflict resolution, failure detection, repair, storage engines and consensus. After it, the "Cassandra" or "etcd" boxes in other designs stop being black boxes.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It tells the Amazon shopping-cart story, explains quorums with a picture, and shows `nodetool status` and `cqlsh` consistency levels you can try.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why each mechanism exists |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Consistent hashing + vnodes, N = 3 replication, coordinator, quorum R/W, basic failure handling, storage basics |
| [L5-senior.md](L5-senior.md) | Senior | Tunable consistency math, sloppy quorum + hinted handoff, conflict resolution (LWW vs vector clocks), read repair, Merkle anti-entropy, gossip + phi failure detection, LSM storage, tombstones, hot keys, tail latency |
| [L6-staff.md](L6-staff.md) | Staff | Leaderless (AP) vs consensus-based (CP, multi-Raft) and choosing per workload; multi-region; operations (repair, compaction, rebalancing, upgrades); correctness testing (fault injection, Jepsen); build vs buy |

**Suggested order:** product page → L4 → L5 → L6. This one is dense; it's fine to read L5 twice.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Partitioning | Consistent hashing with vnodes | Rebalancing by streaming ranges; hot keys | Range vs hash partitioning; splitting/merging ranges |
| Replication | N = 3, coordinator writes to all, waits for W | Preference lists across racks/zones; sloppy quorum, hints | Leaderless vs Raft per range; multi-region topologies |
| Consistency | Quorum R + W > N | Exact guarantees and where they break (sloppy quorum, LWW clock skew) | Linearizability where needed; per-use-case choice |
| Conflicts | Last write wins | Vector clocks / siblings; CRDTs | Avoid conflicts by design (single leader per key range) |
| Repair | — | Read repair, hinted handoff, Merkle anti-entropy | Repair scheduling, gc_grace and zombie data |
| Membership | Static list | Gossip + phi accrual failure detector | Seed nodes, split brain, operator workflows |
| Storage | Append log + in-memory index | LSM tree: WAL, memtable, SSTables, compaction, Bloom filters, tombstones | Compaction strategy tuning, disk-full and write stalls |

## Building blocks used

**Concepts (new for this problem):** [LSM trees & storage engines](../../concepts/lsm-trees-and-storage-engines.md) · [Gossip & failure detection](../../concepts/gossip-and-failure-detection.md) · [Vector clocks & conflict resolution](../../concepts/vector-clocks-and-conflict-resolution.md) · [Merkle trees & anti-entropy](../../concepts/merkle-trees-and-anti-entropy.md) · [Hinted handoff & sloppy quorum](../../concepts/hinted-handoff-and-sloppy-quorum.md) · [Consensus & Raft](../../concepts/consensus-and-raft.md)

**Concepts (reused):** [Consistent hashing](../../concepts/consistent-hashing.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [CAP & consistency](../../concepts/cap-and-consistency.md) · [Bloom filters](../../concepts/bloom-filters.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Technologies:** [Cassandra / DynamoDB](../../technologies/cassandra.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md) · [Redis](../../technologies/redis.md) · [PostgreSQL](../../technologies/postgresql.md)

**Related LLD:** [In-memory KV store with transactions](../../../LLD/interviews/kv-store/README.md): the single-node engine (WAL, recovery, transactions).

## The core insight

1. **Partition for size, replicate for survival, quorum for consistency.** Three separate decisions, each with its own knob.
2. **Leaderless replication buys availability and pays in conflicts and repair.** Every mechanism in L5 (hints, read repair, Merkle trees, vector clocks) exists to clean up after "any replica can accept writes".
3. **Not every workload should accept that trade.** Metadata, locks and money want a single leader per key range (Raft): fewer surprises, at the cost of availability during failures.
