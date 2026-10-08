# HLD Interview: Design a Web Crawler

> "Design a crawler that downloads a billion web pages a month for a search engine, without overloading anyone's website."

This interview looks like a simple loop (fetch, extract links, repeat) and turns into a lesson in **scheduling under constraints**: the bottleneck isn't CPU or disk, it's that you may contact each website only so often. It also brings massive deduplication (Bloom filters, fingerprints), handling an untrusted and adversarial input (the web), and spending a fixed budget wisely.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains politeness, robots.txt, URL normalisation and the crawl loop, with `curl` commands you can run.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why each part of a crawler exists |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Crawl loop, per-host queues + delays, robots.txt, DNS caching, URL normalisation, Bloom filter sizing, exact content dedup, batch storage, partition by host |
| [L5-senior.md](L5-senior.md) | Senior | Mercator front/back queues + heap, prioritisation, SimHash near-dups, spider traps, recrawl with conditional GET, per-IP politeness, crash recovery |
| [L6-staff.md](L6-staff.md) | Staff | Crawl as budget allocation, selective JS rendering, multi-region, good-citizen and legal concerns, adversarial content, multi-tenant crawling, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Frontier | Queue per host + next allowed time | Front (priority) + back (politeness) queues + heap | Budget split: discovery vs refresh vs waste |
| Politeness | Fixed delay per host, robots.txt | Adaptive delay (10× fetch time), per-IP limits, `Retry-After` | Shared politeness across teams; opt-out; verifiable identity |
| Dedup | URL normalisation, Bloom filter, exact content hash | SimHash with block tables | Link farms, generated junk, cloaking |
| Freshness | Out of scope (first crawl) | Change-rate based recrawl, conditional GET | Measure freshness of what users see |
| Scale-out | Partition by host | Partitioned seen-set, cross-worker batching, crash recovery | Multi-region host placement |
| Content | HTML only | HTML, size/time limits | Selective JS rendering on its own budget |

## Building blocks used

**Concepts (new for this problem):** [URL frontier & politeness](../../concepts/url-frontier-and-politeness.md) · [Content fingerprinting & dedup](../../concepts/content-fingerprinting-and-dedup.md)

**Concepts (reused):** [Bloom filters](../../concepts/bloom-filters.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Idempotency](../../concepts/idempotency-and-delivery-semantics.md) · [Observability](../../concepts/observability.md) · [Resilience patterns](../../concepts/resilience-patterns.md) · [TLS](../../concepts/tls-and-mtls.md)

**Technologies:** [DNS](../../technologies/dns.md) (new) · [Object storage (S3)](../../technologies/object-storage.md) · [Cassandra](../../technologies/cassandra.md) · [Kafka](../../technologies/kafka.md)

**Related LLD:** [Task Scheduler](../../../LLD/interviews/task-scheduler/README.md): the same "heap of next-allowed times + one thread that waits for the earliest" mechanism, built in code. [Rate Limiter](../../../LLD/interviews/rate-limiter/README.md): politeness is rate limiting pointed outwards.

## The core insight

1. **Politeness, not hardware, limits a crawler.** At 1 request per second per host, crawling 800 pages/s means always having 800+ hosts ready. The frontier exists to make that true.
2. **Separate *which* from *when*.** Priority decides which URLs are worth fetching; politeness decides when each host may be contacted. Mixing them in one queue breaks both.
3. **The web is infinite and partly hostile.** Normalisation, dedup, per-host budgets and size/time limits aren't polish; without them the crawler spends its budget on junk.
