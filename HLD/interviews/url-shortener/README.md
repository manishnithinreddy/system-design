# HLD Interview: Design a URL Shortener (like bit.ly / tinyurl)

> "Given a long URL, return a short one. When someone opens the short one, send them to the long one."

It sounds trivial, and that's exactly why it's a favourite: the *feature* is tiny, so the whole interview is about **scale, ID generation, caching, storage choice, and trade-offs**. The same question is asked at every level — what changes is how deep you go and what you notice without being prompted.

## How to read this folder

| File | Who it's for | What "good" looks like |
|---|---|---|
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 (2–4 yrs) | A correct, working design. Sensible components, clean API, a reasonable code-generation scheme, a cache. Answers follow-ups when asked. |
| [L5-senior.md](L5-senior.md) | Senior (5+ yrs) | Numbers drive decisions. Compares 2–3 options for each hard part and picks one with reasons. Handles custom aliases, expiry, analytics, failure modes **without being asked**. |
| [L6-staff.md](L6-staff.md) | Staff | Questions the requirements, designs for multi-region, abuse, cost, operability and evolution. Talks about SLOs, blast radius, migration paths and what *not* to build. |

**Suggested order:** read L4 first even if you're targeting L5. Each level assumes the previous one and only explains what's new.

## The same problem at three levels — at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Requirements | Lists the obvious ones | Adds non-functional numbers (latency, availability) | Splits SLOs per path (redirect ≫ create), asks about abuse, compliance, business model |
| Estimates | Rough QPS and storage | Uses estimates to *justify* choices (cache size, DB choice, code length) | Adds cost, multi-region traffic split, growth |
| Short code | Base62 of DB auto-increment ID | Compares hash vs counter vs pre-generated keys; counter ranges | Globally unique without cross-region coordination; enumeration/security |
| Storage | One [PostgreSQL](../../technologies/postgresql.md) + read replicas | Key-value store ([Cassandra/DynamoDB](../../technologies/cassandra.md)) vs Postgres, with reasons | Multi-region replication, data residency, lifecycle/TTL cost |
| Caching | [Redis](../../technologies/redis.md) cache-aside | Hot keys, stampede, negative caching, sizing | Multi-layer: [CDN](../../technologies/cdn.md)/edge → local → Redis → DB |
| Analytics | "We could log clicks" | Async via [Kafka](../../technologies/kafka.md), never on the redirect path | Edge logs, aggregation pipeline, privacy |
| Failure | Mentions replicas | Redis down, DB down, ID service down — what happens | Region down, degraded modes, blast radius, runbooks |

## Building blocks used (read these if a term is unfamiliar)

**Technologies:** [Load balancer](../../technologies/load-balancer.md) · [Redis](../../technologies/redis.md) · [PostgreSQL](../../technologies/postgresql.md) · [Cassandra / DynamoDB](../../technologies/cassandra.md) · [Kafka](../../technologies/kafka.md) · [CDN](../../technologies/cdn.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md)

**Concepts:** [Back-of-the-envelope estimation](../../concepts/back-of-the-envelope.md) · [ID generation](../../concepts/id-generation.md) · [Caching strategies](../../concepts/caching-strategies.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [CAP & consistency](../../concepts/cap-and-consistency.md)

## The core insight (remember this even if you forget everything else)

1. **It's read-heavy (~100:1).** The redirect path is the product. Optimise it ruthlessly: cache, keep it tiny, never do slow work on it.
2. **The hard part is generating short codes that are unique, short, and not guessable — at scale, without a bottleneck.**
3. **The data model is a single key → value lookup.** That's why a key-value store fits, and why joins/SQL features aren't what you're buying.
