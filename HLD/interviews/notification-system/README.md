# HLD Interview: Design a Notification System

> "Design a service that lets every team in the company send notifications to users over push, SMS, email and in-app."

Unlike the URL shortener (one tiny read path), this is a **write-heavy, asynchronous pipeline** with unreliable third parties at the end. The interview is about **queues, retries, idempotency, priorities, fan-out and rate limits**: how to move hundreds of millions of messages a day through systems you don't control without losing, duplicating or delaying the important ones.

## How to read this folder

> 👉 **Not sure what a "notification platform" does beyond showing pop-ups? Start with [00-understand-the-product.md](00-understand-the-product.md).** It walks through one food order that triggers 7 notifications, shows how push actually reaches a phone, and maps everything to Prometheus Alertmanager, which you may already know.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know the product from the user's and the company's side |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Async design with a queue per channel, workers, providers, preferences, basic retries and a status table |
| [L5-senior.md](L5-senior.md) | Senior | Priority isolation, idempotency, backoff + DLQ, provider rate limits and failover, scheduling, broadcast fan-out, failure modes |
| [L6-staff.md](L6-staff.md) | Staff | Delivery guarantees stated honestly, multi-tenant fairness, cost (SMS dominates), SMS fraud, compliance, SLOs per priority, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Core flow | API → queue → channel workers → providers | Same, plus explicit priority lanes and a scheduler | Same, as a multi-tenant platform with per-tenant quotas |
| Reliability | "Retry on failure" | Retryable vs permanent errors, exponential backoff + jitter, DLQ, circuit breaker, provider failover | Delivery guarantee written as a contract: at-least-once + dedup; what "delivered" even means across providers |
| Duplicates | Mentions it | Idempotency keys with TTL'd dedup store; transactional outbox on the producer side | Where dedup can and can't work end to end; user-visible impact per channel |
| Priority | Not separated | Separate queues/workers/provider quotas for critical vs bulk | Cell isolation; synthetic OTP canaries; SLO per priority |
| Broadcast | "Loop over users" | Batch expansion job, chunking, throttling to provider limits | Cost controls, audience approval, kill switch |
| Rate limits | — | Per-user frequency caps, per-provider token buckets | Per-tenant budgets, fairness, abuse (SMS pumping) |
| Data | One SQL table for status | Cassandra for notification log/inbox, Redis for prefs cache/dedup | Retention, privacy, data residency, consent audit trail |

## Building blocks used

**Technologies:** [Message queues (SQS/RabbitMQ)](../../technologies/message-queues.md) · [Kafka](../../technologies/kafka.md) · [Push / email / SMS providers](../../technologies/push-email-sms-providers.md) · [WebSockets & SSE](../../technologies/websockets-and-sse.md) · [Redis](../../technologies/redis.md) · [PostgreSQL](../../technologies/postgresql.md) · [Cassandra / DynamoDB](../../technologies/cassandra.md) · [Load balancer](../../technologies/load-balancer.md)

**Concepts:** [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Fan-out](../../concepts/fan-out.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Caching strategies](../../concepts/caching-strategies.md) · [Sharding & replication](../../concepts/sharding-and-replication.md)

**Related LLD:** [Rate Limiter](../../../LLD/interviews/rate-limiter/README.md), used here for per-user caps and provider quotas.

## The core insight

1. **Accept fast, deliver later.** The API only validates and enqueues (`202 Accepted`). Everything slow or unreliable happens behind a queue.
2. **Isolate by priority.** An OTP must never wait behind a marketing campaign. Separate lanes all the way to the provider quota.
3. **At-least-once + idempotency.** You can't get "exactly once" across a third-party SMS gateway. Retry until success, and make duplicates harmless.
