# HLD Interview: Design a News Feed (like Instagram / X / Facebook)

> "Users follow other users. Design the system that shows each user a feed of posts from the people they follow."

The defining challenge: **reads are enormous and must be instant, while writes have wildly uneven fan-out**. A normal user's post goes to a few hundred feeds; a celebrity's goes to hundreds of millions. The interview is about **precomputation vs on-demand work**, caching, ranking pipelines and keeping precomputed data correct.

## How to read this folder

> 👉 **Never thought about where your feed comes from? Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains the follow graph, push vs pull, ranking, infinite scroll and counters through one Instagram session.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know what a feed is made of and why each feature exists |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Post service, follow graph, fan-out on write into cached feed lists, hydration, chronological feed, cursor pagination, media via CDN |
| [L5-senior.md](L5-senior.md) | Senior | Hybrid fan-out (celebrities), async fan-out workers, cache sizing, read-time filtering, counters, ranking pipeline basics, cache-miss rebuild, failure modes |
| [L6-staff.md](L6-staff.md) | Staff | Ranking as a product and integrity problem, experimentation, degradation (serve chronological if ranking fails), multi-region, privacy/deletion propagation, cost |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Fan-out | Push to every follower | Hybrid: push for normal users, pull for celebrities; skip inactive users | Tuning thresholds with data; cost of fan-out vs read compute |
| Feed storage | Redis list of post IDs per user | Capped lists, memory sizing, rebuild on cache miss | Multi-region placement; what to recompute vs replicate |
| Ordering | Chronological | Ranking pipeline (candidates → scoring → re-rank) | Ranking objectives, integrity, experiments, fallbacks |
| Correctness | — | Filter deleted/blocked/unfollowed at read time | Deletion/privacy propagation guarantees, legal timelines |
| Counters | Column in posts table | Sharded/async counters, approximate display | Counter correctness vs cost; anti-abuse (fake likes) |
| Pagination | Cursor | Stable cursors across ranked feeds | Session-consistent feeds, "new posts" UX |

## Building blocks used

**Technologies:** [Redis](../../technologies/redis.md) · [Graph databases & adjacency lists](../../technologies/graph-databases.md) · [Cassandra](../../technologies/cassandra.md) · [PostgreSQL](../../technologies/postgresql.md) · [Kafka](../../technologies/kafka.md) · [Object storage](../../technologies/object-storage.md) · [CDN](../../technologies/cdn.md) · [Load balancer](../../technologies/load-balancer.md)

**Concepts:** [Fan-out](../../concepts/fan-out.md) · [Feed ranking](../../concepts/feed-ranking.md) · [Pagination](../../concepts/pagination.md) · [Counters at scale](../../concepts/counters-at-scale.md) · [Bloom filters](../../concepts/bloom-filters.md) · [Caching strategies](../../concepts/caching-strategies.md) · [ID generation](../../concepts/id-generation.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Related:** the [chat system](../chat-system/README.md) uses fan-out for groups; the [notification system](../notification-system/README.md) uses it for broadcasts.

## The core insight

1. **Precompute for the many, compute on demand for the few.** Push posts into follower feeds for normal users; pull celebrity posts at read time.
2. **Store IDs, not posts, in feeds.** Feeds are lists of post IDs; full posts are fetched ("hydrated") from a shared cache. Edits, deletes and counts stay in one place.
3. **Precomputed data goes stale, so re-check at read time.** Deleted posts, blocks and unfollows are filtered when the feed is served.
