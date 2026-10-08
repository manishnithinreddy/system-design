# HLD Interview: Design a Ride-Sharing Service (like Uber / Ola)

> "Riders request rides; nearby drivers get matched and pick them up. Design the backend."

What makes this one different: the core data (**driver locations**) changes every few seconds for millions of objects, queries are **geographic** ("near this point"), and assignment must be **exclusive** under heavy concurrency. Add a trip state machine, live tracking, surge pricing and payments across services, and it touches almost every building block in this repo.

## How to read this folder

> 👉 **Never thought about what happens between "Book" and "Ravi is arriving"? Start with [00-understand-the-product.md](00-understand-the-product.md).** It walks through one ride: live locations, "who's near me", offers with timeouts, surge and payment holds.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know each step of a ride and the mechanism behind it |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Location updates into an in-memory geo index, nearby search, atomic driver assignment, trip state machine in a DB, live tracking |
| [L5-senior.md](L5-senior.md) | Senior | Geo-sharding by city/cell, ETA ranking, offer leases with timeouts, location ingestion at 250k/s, surge via stream processing, payment saga, failure modes |
| [L6-staff.md](L6-staff.md) | Staff | Marketplace view: batch matching, supply positioning, surge policy, city-as-a-cell architecture, two-sided experiments, safety, fraud (GPS spoofing), degraded modes |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Locations | Redis GEO with latest position per driver | Sharded by city/H3 cell; ingestion via gateways + Kafka; TTL for stale drivers | Location quality: GPS jitter, spoofing, map matching |
| Search | Radius search, nearest first | Ring expansion over cells; rank by road ETA | Batch matching across many requests at once |
| Assignment | Conditional DB update `WHERE status = 'AVAILABLE'` | Offer lease with TTL, sequential offers, timeouts | Acceptance-rate modelling; fairness to drivers |
| Pricing | Fixed fare formula | Surge per cell from stream processing | Surge as policy: caps, regulation, smoothing, experiments |
| Payments | Charge at end | Saga: authorise → capture → payout, compensations, idempotency | Reconciliation, fraud, multi-country payment methods |
| Architecture | Services + DB + Redis | Geo-partitioned services | City as a cell: blast radius, regional failover, data residency |

## Building blocks used

**Technologies:** [Redis (incl. GEO)](../../technologies/redis.md) · [Stream processing](../../technologies/stream-processing.md) · [Kafka](../../technologies/kafka.md) · [WebSockets & SSE](../../technologies/websockets-and-sse.md) · [PostgreSQL](../../technologies/postgresql.md) · [Cassandra](../../technologies/cassandra.md) · [Push providers](../../technologies/push-email-sms-providers.md) · [ZooKeeper / etcd](../../technologies/zookeeper-etcd.md) · [Load balancer](../../technologies/load-balancer.md)

**Concepts:** [Geospatial indexing](../../concepts/geospatial-indexing.md) · [Distributed locks & leases](../../concepts/distributed-locks-and-leases.md) · [Sagas & distributed transactions](../../concepts/sagas-and-distributed-transactions.md) · [Idempotency](../../concepts/idempotency-and-delivery-semantics.md) · [Presence & heartbeats](../../concepts/presence-and-heartbeats.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [CAP & consistency](../../concepts/cap-and-consistency.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Related LLD:** [Elevator system](../../../LLD/interviews/elevator-system/README.md) (dispatching with a cost function; state machines), [Parking lot](../../../LLD/interviews/parking-lot/L6-staff.md) (atomic claiming with `SKIP LOCKED`).

**Case study:** [Uber: from monolith to H3 and microservices](../../../case-studies/uber-from-monolith-to-h3-and-microservices.md): what Uber actually built (Ringpop, H3, Schemaless, Cadence).

## The core insight

1. **Live locations are ephemeral: keep the latest in memory, indexed by cell.** They're rebuilt from the next update within seconds; durability isn't needed for the index.
2. **Assignment is the one thing that must be strongly consistent.** One driver, one trip, enforced atomically (conditional update / lease), even though almost everything else can be eventually consistent.
3. **Partition by geography.** Riders and drivers in Bengaluru never interact with those in Mumbai, so cities are natural shards and failure domains.
