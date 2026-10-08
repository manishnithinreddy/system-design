# System Design Interview Prep

Interview walkthroughs written as **interviewer ↔ candidate dialogues**, each answered at three levels (**L4 mid**, **L5 senior**, **L6 staff**), plus a separate reference library explaining every technology, library and concept they use: what problem it solves, when to use it, when **not** to, and what it gets confused with.

## Layout

```
HLD/                         High-level design (architecture)
├── interviews/<problem>/    00-understand-the-product.md + README + L4-mid.md + L5-senior.md + L6-staff.md
├── technologies/            Redis, Kafka, Postgres, Cassandra, CDN, ...
└── concepts/                ID generation, caching, sharding, CAP, ...
LLD/                         Low-level design (classes + code)
├── interviews/<problem>/    00-understand-the-product.md + README + L4/L5/L6 + runnable java/ and js/ code
├── libraries/java/          ConcurrentHashMap, atomics, locks, ...
├── libraries/js/            event loop, Map, middleware, ...
└── concepts/                design patterns, SOLID, thread safety
```

## Interviews

| Type | Problem | Files |
|---|---|---|
| HLD | [URL Shortener](HLD/interviews/url-shortener/README.md) | [Product intro](HLD/interviews/url-shortener/00-understand-the-product.md) · [L4](HLD/interviews/url-shortener/L4-mid.md) · [L5](HLD/interviews/url-shortener/L5-senior.md) · [L6](HLD/interviews/url-shortener/L6-staff.md) |
| HLD | [Notification System](HLD/interviews/notification-system/README.md) | [Product intro](HLD/interviews/notification-system/00-understand-the-product.md) · [L4](HLD/interviews/notification-system/L4-mid.md) · [L5](HLD/interviews/notification-system/L5-senior.md) · [L6](HLD/interviews/notification-system/L6-staff.md) |
| HLD | [Chat System](HLD/interviews/chat-system/README.md) | [Product intro](HLD/interviews/chat-system/00-understand-the-product.md) · [L4](HLD/interviews/chat-system/L4-mid.md) · [L5](HLD/interviews/chat-system/L5-senior.md) · [L6](HLD/interviews/chat-system/L6-staff.md) |
| LLD | [Rate Limiter](LLD/interviews/rate-limiter/README.md) | [Product intro](LLD/interviews/rate-limiter/00-understand-the-product.md) · [L4](LLD/interviews/rate-limiter/L4-mid.md) · [L5](LLD/interviews/rate-limiter/L5-senior.md) · [L6](LLD/interviews/rate-limiter/L6-staff.md) |
| LLD | [Parking Lot](LLD/interviews/parking-lot/README.md) | [Product intro](LLD/interviews/parking-lot/00-understand-the-product.md) · [L4](LLD/interviews/parking-lot/L4-mid.md) · [L5](LLD/interviews/parking-lot/L5-senior.md) · [L6](LLD/interviews/parking-lot/L6-staff.md) |
| LLD | [LRU Cache](LLD/interviews/lru-cache/README.md) | [Product intro](LLD/interviews/lru-cache/00-understand-the-product.md) · [L4](LLD/interviews/lru-cache/L4-mid.md) · [L5](LLD/interviews/lru-cache/L5-senior.md) · [L6](LLD/interviews/lru-cache/L6-staff.md) |

Indexes: [HLD](HLD/README.md) · [LLD](LLD/README.md)

## How to study a problem

1. Read the problem's `00-understand-the-product.md`. It explains the product as a user would experience it, so you know *what* you're designing and *why* each feature exists.
2. Read the problem's `README.md` (the level comparison table tells you what changes between levels).
3. Cover the candidate's answers and try to answer each interviewer question yourself first.
4. When a term is unfamiliar, follow its link into `technologies/`, `libraries/` or `concepts/`. Each reference page ends with an "interview cheat-sheet" you can say out loud.
5. For LLD, run the code and break it (e.g. remove `synchronized` and watch the concurrency test fail).

## Running the LLD code

```sh
LLD/interviews/rate-limiter/java/run.sh                 # Java 21+, tests + demo
cd LLD/interviews/rate-limiter/js && node --test        # Node 22+, tests
LLD/interviews/parking-lot/java/run.sh                  # Java 21+, tests + demo
cd LLD/interviews/parking-lot/js && node --test         # Node 22+, tests
LLD/interviews/lru-cache/java/run.sh                    # Java 21+, tests + demo
cd LLD/interviews/lru-cache/js && node --test           # Node 22+, tests
```
