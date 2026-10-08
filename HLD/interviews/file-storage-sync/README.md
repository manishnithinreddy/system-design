# HLD Interview: Design File Storage & Sync (Dropbox / Google Drive)

> "Design Dropbox: a folder that stays in sync across a user's devices and teammates, handles files up to 50 GB, keeps version history, and never loses data."

This interview teaches **separating metadata from bytes**, **content-addressed, deduplicated blocks**, **sync as a journal plus a cursor**, **conflict detection with a base version**, durability at exabyte scale, and why the **client** is half the system. It pairs with the [In-memory File System LLD](../../../LLD/interviews/file-system/README.md) (trees, paths, permissions on one machine).

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It walks through upload → missing blocks → notify, with a real rsync run showing one changed byte in 50 MB sending only ~36 KB.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why blocks, versions, journals and conflicted copies exist |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Metadata vs blocks, commit-then-upload-missing, optimistic version check, journal + cursor + long-poll, versions and trash, sharing |
| [L5-senior.md](L5-senior.md) | Senior | Content-defined chunking and deltas, three-way conflicts, file IDs, namespace sharding, erasure coding, safe block GC, notification tier, client realities, dedup privacy |
| [L6-staff.md](L6-staff.md) | Staff | Storage economics and owning storage, sync-engine correctness, ransomware recovery, compliance, derived data, abuse, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Chunking | Fixed 4 MB blocks, hashed | Content-defined chunking + in-block deltas | Block-size arithmetic at exabyte scale |
| Consistency | parentVersion check on commit | Base/local/remote comparison; stable file IDs | Randomized deterministic testing of the sync engine |
| Metadata | Files, versions, journal | Sharded by namespace; snapshots for new devices | Residency per namespace; audit; legal hold |
| Blocks | Object storage keyed by hash | Erasure coding; refcount + mark-and-sweep GC with grace period | Build own storage only with a cost model |
| Notifications | Long-poll, pull changes | ~400-server notification tier, data-free pings | — |
| Recovery | Versions + trash | GC safety | Point-in-time restore for ransomware |

## Building blocks used

**Concepts (new for this problem):** [Chunking & block-level dedup](../../concepts/chunking-and-block-level-dedup.md) · [File sync & conflict resolution](../../concepts/file-sync-and-conflict-resolution.md)

**Concepts (reused):** [Content fingerprinting & dedup](../../concepts/content-fingerprinting-and-dedup.md) · [Resumable & chunked uploads](../../concepts/resumable-and-chunked-uploads.md) · [Message ordering & sequencing](../../concepts/message-ordering-and-sequencing.md) · [Idempotency](../../concepts/idempotency-and-delivery-semantics.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Vector clocks & conflicts](../../concepts/vector-clocks-and-conflict-resolution.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Optimistic locking](../../../LLD/concepts/optimistic-vs-pessimistic-locking.md) · [Access control models](../../../LLD/concepts/access-control-models.md)

**Technologies:** [Object storage](../../technologies/object-storage.md) · [Kafka](../../technologies/kafka.md) · [PostgreSQL](../../technologies/postgresql.md)

**Under the Hood:** [rsync's rolling hash](../../../under-the-hood/rsync-rolling-hash.md) · [Content-defined chunking](../../../under-the-hood/content-defined-chunking.md) · [Git's object store](../../../under-the-hood/git-object-store.md) · [epoll](../../../under-the-hood/epoll.md)

**Related LLD:** [In-memory File System](../../../LLD/interviews/file-system/README.md)

## The core insight

1. **Metadata and bytes are different problems.** Small, transactional, sharded metadata; huge, immutable, content-addressed blocks.
2. **Name data by its hash** and you get dedup, integrity checks and idempotent uploads for free.
3. **Sync = an ordered journal per namespace + a cursor per device**, with notifications that only say "go pull".
4. **Conflicts need a base version.** Two states can't tell "they changed it" from "we both did"; timestamps lie.
