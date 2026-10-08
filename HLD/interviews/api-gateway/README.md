# HLD Interview: Design an API Gateway

> "Design the front door for all of our company's APIs: routing, authentication, rate limiting, and resilience for hundreds of backend services."

A great problem for someone from infrastructure: it's less about storing data and more about **request-path engineering**: latency budgets, failure isolation, dynamic configuration, security, and observability. The gateway is in front of *everything*, so its availability and safety requirements are higher than any single service's.

## How to read this folder

> 👉 **Not sure what a gateway does beyond "proxy requests"? Start with [00-understand-the-product.md](00-understand-the-product.md).** It follows one request through TLS, auth, rate limiting, routing and retries, and compares it with the Kubernetes Ingress you already know.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know each gateway function and why it's centralised |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Stateless gateway fleet behind an L4 LB; routing table; JWT/API key auth; rate limiting; timeouts; logging with request IDs |
| [L5-senior.md](L5-senior.md) | Senior | Control plane vs data plane, config validation & rollout, service discovery, local+global rate limits, timeout/retry budgets, circuit breakers, load shedding, tracing, failure modes per dependency |
| [L6-staff.md](L6-staff.md) | Staff | Gateway as a multi-tenant platform: self-service with guardrails, blast radius (sharded gateways), edge vs mesh boundary, build vs buy, API lifecycle, DDoS layers, SLOs stricter than any backend |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Architecture | Stateless nodes + LB | Data plane (proxies) + control plane (config) | Multiple gateway shards/cells by domain; edge layer (CDN/WAF) in front |
| Config | Config file, reload | Versioned, validated, pushed incrementally, staged rollout, last-known-good | Self-service via policy-as-code, per-team ownership, review guardrails |
| Auth | Validate JWT, API keys | JWKS caching & rotation, revocation, mTLS to backends | Identity strategy across products; token exchange; zero-trust stance |
| Rate limiting | Per key, Redis counters | Local token buckets + global limits, fail-open policy | Quotas as product (plans), fairness across tenants |
| Resilience | Timeouts | Retry budgets, circuit breakers, bulkheads, load shedding by priority | Degradation strategy company-wide; gateway can't be the outage |
| Observability | Access logs, request ID | RED metrics per route, distributed tracing, sampling | SLOs per API, error budgets, the gateway as the user-experience sensor |

## Building blocks used

**Technologies:** [Service mesh & Envoy](../../technologies/service-mesh-and-envoy.md) · [Load balancer](../../technologies/load-balancer.md) · [Redis](../../technologies/redis.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md) · [CDN](../../technologies/cdn.md) · [Kafka](../../technologies/kafka.md)

**Concepts:** [Authentication, OAuth & JWT](../../concepts/authentication-oauth-jwt.md) · [Service discovery](../../concepts/service-discovery.md) · [TLS & mTLS](../../concepts/tls-and-mtls.md) · [Observability](../../concepts/observability.md) · [Resilience patterns](../../concepts/resilience-patterns.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Caching strategies](../../concepts/caching-strategies.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Related LLD:** [Rate limiter](../../../LLD/interviews/rate-limiter/README.md): the algorithms and the distributed version used inside the gateway.

## The core insight

1. **The gateway must be boring, stateless and fast.** Every request pays its latency; every outage of it is a total outage. Keep per-request work local (cached keys, local token buckets) and push state out.
2. **Configuration is the riskiest input.** Most gateway incidents are bad config, not bad code. Treat config like code: validated, versioned, rolled out gradually, instantly revertible.
3. **Protect the gateway from the backends.** Timeouts, retry budgets, circuit breakers and load shedding stop one slow service from consuming all gateway capacity.
