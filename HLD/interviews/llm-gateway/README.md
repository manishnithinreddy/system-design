# HLD Interview: Design an LLM Gateway

> "Design an LLM gateway: one internal endpoint that all teams use to call LLM providers, with rate limits, cost control, caching, failover and observability."

This interview is the [API gateway](../api-gateway/README.md) problem with three twists: **requests are priced by size** (tokens, so limits and budgets must count tokens), **calls are slow and streamed** (seconds, long-lived connections), and **providers are unreliable and rate-limited** (so failover, queues and load shedding matter). Treat the LLM as an expensive, slow, flaky dependency you do not control. This is the exact mindset of infra work. See also [Interviews in the AI era §5.2](../../../guides/interviews-in-the-ai-era.md).

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** The "AI bill is 6x the plan" story, tokens, streaming and why latency is seconds.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know tokens, streaming, and why a shared doorway helps |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates (50 req/s, 8.64B tokens/day, ~$15k/day illustrative), per-team keys, adapters, RPM + TPM token buckets, metering, SSE pass-through, timeouts and cost-aware retries |
| [L5-senior.md](L5-senior.md) | Senior | Reserve-then-settle budgets, hard/soft caps, exact vs semantic cache, failover + circuit breakers, priority queues and load shedding, mid-stream failures, PII/injection guardrails, safe prompt logging |
| [L6-staff.md](L6-staff.md) | Staff | Multi-region and data residency, self-hosted vs API, chargeback, eval gates for model switches, build vs buy, platform SLOs (TTFT, tokens/s), lock-in |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Limits | Per-team RPM + TPM buckets | Monthly budgets, reserve-then-settle | Chargeback, quota market, forecasting |
| Reliability | Timeout + bounded retry | Circuit breakers, fallback, queues, shedding | Multi-region, SLOs, error budgets |
| Cache | None (mention) | Exact + semantic with thresholds | Cache isolation per tenant, ROI tracking |
| Safety | Auth, key per team | PII redaction, injection checks, log retention | Residency, compliance, audit |
| Models | Provider adapters | Model routing by task | Self-host vs API, eval gates, anti-lock-in |

## Building blocks used

**Concepts:** [Counters at scale](../../concepts/counters-at-scale.md) · [Caching strategies](../../concepts/caching-strategies.md) · [Resilience patterns](../../concepts/resilience-patterns.md) · [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Observability](../../concepts/observability.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · RAG & vector search (embeddings for the semantic cache; page being written: [rag-and-vector-search](../../concepts/rag-and-vector-search.md)) · LLM evals, guardrails & prompt injection (page being written: [llm-evals-guardrails-and-prompt-injection](../../concepts/llm-evals-guardrails-and-prompt-injection.md))

**Technologies:** [Redis](../../technologies/redis.md) · [Kafka](../../technologies/kafka.md) · [WebSockets and SSE](../../technologies/websockets-and-sse.md)

**Under the Hood:** LLM inference serving (page being written: [llm-inference-serving](../../../under-the-hood/llm-inference-serving.md))

**Related HLD / LLD:** [API Gateway](../api-gateway/README.md) · [Rate Limiter (LLD)](../../../LLD/interviews/rate-limiter/README.md)

**Guide:** [Interviews in the AI era](../../../guides/interviews-in-the-ai-era.md)

## The core insight

1. **Tokens are the currency.** Limit, meter and budget in tokens, not just requests, and remember output tokens are unknown until the response ends (reserve, then settle).
2. **Every provider call is slow, flaky and billed.** Timeouts, bounded retries, breakers, fallbacks and queues are the design, not decoration.
3. **The gateway is the one place to enforce policy:** keys, budgets, redaction, logging and routing, so that teams stop reinventing them.

## Numbers to remember (illustrative, not real prices)

| Quantity | Value used in this folder | Where derived |
|---|---|---|
| Teams / average rate | 200 teams, 50 req/s | [L4 §2](L4-mid.md) |
| Tokens per request | 2,000 (1,500 in + 500 out) | [L4 §2](L4-mid.md) |
| Tokens per day | 50 × 86,400 × 2,000 = 8.64B | [L4 §2](L4-mid.md) |
| Illustrative price | $1 per M input tokens, $4 per M output tokens | [L4 §2](L4-mid.md) |
| Cost per day | 4.32M requests × $0.0035 = $15,120 | [L4 §2](L4-mid.md) |
| Concurrent streams | 50 req/s × 8 s = 400 (2,000 at 5x peak) | [L4 §2](L4-mid.md) |

## Questions interviewers love here

1. "A team's retry bug is burning money. How fast do you stop it, and who is told?" (L5 budgets, L4 retries)
2. "The provider returns 429 for everyone. Whose request waits and whose is dropped?" (L5 priority and shedding)
3. "The stream died after 300 tokens. Retry? Bill? Show what?" (L5 mid-stream failure)
4. "Can the semantic cache ever show team A an answer meant for team B?" (L5 cache isolation)
5. "Legal says EU prompts must not leave the EU. What changes?" (L6 residency)
6. "Would you build this or buy one?" (L6 build vs buy)

## Common thread with other problems

Same shapes appear elsewhere: the token bucket from the [rate limiter](../../../LLD/interviews/rate-limiter/README.md), the edge checks of the [API gateway](../api-gateway/README.md), the metering pipeline of [ad click aggregation](../ad-click-aggregation/README.md) (usage events counted and billed exactly), and the breaker/fallback patterns in [resilience patterns](../../concepts/resilience-patterns.md).
