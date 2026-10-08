# HLD Interview: Design Search Autocomplete (Typeahead)

> "As the user types into the search box, show the 10 most likely completions, in under 100 ms, for 100 million daily users."

This interview teaches the **offline/online split**: do the expensive work (counting billions of searches, ranking, building an index) ahead of time, so each request is a cheap in-memory lookup. On top of that come tries, top-k selection, streaming counts for trending topics, sharding hot keys, and a lot of judgement about what must never be suggested.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains prefixes, tries, debouncing and the build pipeline, with real suggestion APIs you can `curl`.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why each feature exists |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Read path vs build path, trie with top-k per node, sizing → replicate not shard, CDN + browser caching, debouncing, blocklist, safe index rollout |
| [L5-senior.md](L5-senior.md) | Senior | Base + fresh (trending) indexes, trending score, Count-Min Sketch sizing, sharding by prefix hash + hot prefixes, ranking signals, typo tolerance, languages |
| [L6-staff.md](L6-staff.md) | Staff | Goal metrics and guardrails, personalisation with privacy, safety/legal system, feedback loops and A/B testing, global serving and memory cost, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Index | Trie with top-10 per node, ~10 GB, full copy per server | Prefix → top-k map sharded by hash; head table for hot prefixes | Compressed structures (FSTs); region-specific placement |
| Freshness | Daily rebuild | Fresh index every ~5 min from a stream job, merged per request | Review queue for trending; manipulation defences |
| Ranking | Decayed counts | + trending, region/language, result quality, acceptance | Goal metrics, debiased feedback, A/B tests |
| Counting | Daily batch group-by | Windowed/decayed counts, Count-Min Sketch, Space-Saving | Distinct users, not events |
| Safety | Blocklist + min distinct users | Serve-time blocklist in seconds | Privacy, defamation, legal removals with audit |
| Latency | In-memory + CDN + debounce | Fuzzy only when needed | Network dominates: edge-serve the head |

## Building blocks used

**Concepts (new for this problem):** [Tries & prefix search](../../concepts/tries-and-prefix-search.md) · [Top-k & heavy hitters](../../concepts/top-k-and-heavy-hitters.md) · [Inverted index](../../concepts/inverted-index.md)

**Concepts (reused):** [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Observability](../../concepts/observability.md)

**Technologies:** [Elasticsearch](../../technologies/elasticsearch.md) (new) · [Kafka](../../technologies/kafka.md) · [Stream processing](../../technologies/stream-processing.md) · [CDN](../../technologies/cdn.md) · [Object storage](../../technologies/object-storage.md) · [Redis](../../technologies/redis.md) · [DNS](../../technologies/dns.md)

**Related LLD:** [Logging Framework](../../../LLD/interviews/logging-framework/README.md): the query logs this system counts start life as log events; its async, bounded, drop-when-full pipeline is the same back-pressure thinking as the event stream here.

## The core insight

1. **Precompute the answer, serve a lookup.** Each keystroke should cost a few memory reads. Counting, ranking and filtering all happen offline.
2. **Small index, huge traffic → replicate; big index → shard by something that spreads hot keys.** Know which situation you're in from the numbers.
3. **Fresh and complete are different jobs.** A daily complete index plus a small minutes-old trending index beats rebuilding everything constantly.
4. **A suggestion is the product speaking.** Privacy thresholds, blocklists and fast removal are requirements, not polish.
