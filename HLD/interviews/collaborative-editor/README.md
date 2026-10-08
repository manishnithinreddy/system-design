# HLD Interview: Design a Collaborative Editor (Google Docs)

> "Design Google Docs: many people edit the same document at the same time, see each other's changes and cursors in real time, and never lose anyone's work."

This interview is about **concurrency you can see**: two people change the same sentence at once, and every screen must end up identical and sensible. It teaches Operational Transformation and CRDTs, a single sequencer per document, local-first latency (apply your own edits immediately), event-sourced history with snapshots, presence, and failover that neither loses nor duplicates a keystroke.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** Its "The cat sat" example shows exactly why positions must be transformed.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Understand the shifted-position problem and each feature |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | One owner per doc, server-ordered OT with a worked example, client with one op in flight, presence, op log + snapshots, permissions |
| [L5-senior.md](L5-senior.md) | Senior | Collaborative undo, CRDT mechanics and choice, offline merges, failover with opId dedup and fencing, hot docs, compaction, rich text, anchored comments |
| [L6-staff.md](L6-staff.md) | Staff | Model as a long-term bet, multi-region homing, correctness verification, enterprise security, history cost, platform reuse, build vs buy |

**Suggested order:** product page → L4 → L5 → L6. Pair it with the [Text Editor LLD](../../../LLD/interviews/text-editor/README.md) for the single-user side (buffers, command-based undo).

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Merge | Server-ordered OT, basic transform rules | CRDT alternative; rich-text marks | Choice driven by product; libraries vs build |
| Ordering | One owner per doc (lease) | Failover: replay log, dedup by opId, fencing | Document home region; migration |
| Client | Local apply, one op in flight | Offline queue, long merges | E2EE option and its costs |
| Storage | Op log + snapshots, history | Compaction and retention | History cost arithmetic |
| Scale | Shard by document; connection gateways | Hot docs: batching, viewer tier, editor caps | Shared collaboration platform |
| Correctness | Convergence requirement | Document checksums | Randomized tests, model checking, shadow mode |

## Building blocks used

**Concepts (new for this problem):** [Operational transformation & CRDTs](../../concepts/operational-transformation-and-crdts.md) · [Real-time collaboration & presence](../../concepts/real-time-collaboration-and-presence.md)

**Concepts (reused):** [Message ordering & sequencing](../../concepts/message-ordering-and-sequencing.md) · [Presence & heartbeats](../../concepts/presence-and-heartbeats.md) · [Distributed locks & leases](../../concepts/distributed-locks-and-leases.md) · [Idempotency](../../concepts/idempotency-and-delivery-semantics.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Fan-out](../../concepts/fan-out.md) · [CAP & consistency](../../concepts/cap-and-consistency.md) · [Ledgers & event sourcing](../../../LLD/concepts/ledgers-and-event-sourcing.md)

**Technologies:** [WebSockets & SSE](../../technologies/websockets-and-sse.md) · [Cassandra](../../technologies/cassandra.md) · [PostgreSQL](../../technologies/postgresql.md) · [Object storage](../../technologies/object-storage.md)

**Under the Hood:** [epoll](../../../under-the-hood/epoll.md) (millions of open connections) · [Git's object store](../../../under-the-hood/git-object-store.md) (versions as a DAG of snapshots) · [Postgres MVCC](../../../under-the-hood/postgres-mvcc.md) (another way to let readers and writers coexist)

**Related LLD:** [Text Editor with Undo/Redo](../../../LLD/interviews/text-editor/README.md)

## The core insight

1. **Positions shift under concurrent edits.** Either transform operations against each other (OT) or stop using positions (CRDTs).
2. **One sequencer per document** makes ordering trivial; per-document traffic is tiny, so shard by document.
3. **Apply your own edits immediately,** reconcile later: latency hiding is the whole point of the algorithms.
4. **The log is the truth, snapshots are the cache,** and history is append-only (even "restore" is a new edit).
