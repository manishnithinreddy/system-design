# HLD Interview: Design a Chat System (like WhatsApp)

> "Design a messaging app that supports 1:1 and group chats, delivery/read receipts, online status, and works when users go offline."

The URL shortener was read-heavy and stateless; the notification system was an async pipeline. Chat adds something new: **hundreds of millions of long-lived connections** (stateful servers!), **real-time delivery**, **strict per-conversation ordering**, and **offline sync**. It's one of the best problems for showing you understand stateful infrastructure, which plays well to an infra background.

## How to read this folder

> 👉 **Ever wondered what ✓, ✓✓ and blue ticks mean to the server, or how a message reaches a phone that was off? Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains WebSockets, receipts, sync, groups and E2EE through what you see in WhatsApp.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know each chat feature and the mechanism behind it |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | WebSocket gateways, a session registry, message storage, offline push + sync, receipts, small groups |
| [L5-senior.md](L5-senior.md) | Senior | Ordering with per-conversation sequence numbers, idempotent sends, inbox-based sync, group fan-out trade-offs, presence at scale, media via object storage, reconnect storms |
| [L6-staff.md](L6-staff.md) | Staff | E2EE consequences, multi-region, connection-fleet operations (deploys, draining, backpressure), huge groups/channels, abuse without reading content, retention/cost, build vs buy |

**Suggested order:** product page → L4 → L5 → L6.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Connections | WebSocket gateways, Redis maps user → gateway | Heartbeats, reconnect with jitter, gateway failure | Fleet ops: draining on deploy, connection limits, backpressure, regional homing |
| Ordering | Server timestamps | Per-conversation sequence numbers from a single owner | Multi-device and multi-region ordering; what's "good enough" |
| Delivery | Deliver if online, else push; fetch on open | At-least-once + client message IDs; sync since last seq; per-device acks | Delivery SLOs; what "delivered" means with E2EE and many devices |
| Groups | Loop over members | Fan-out on write ≤ N members, different path for large groups | Channels with millions of readers: fan-out on read, caching, rate limits |
| Storage | Messages table in Cassandra | Partitioning by conversation, hot partitions, inbox tables | Retention: delete after delivery? cost; data residency |
| Presence | Online flag in Redis | Heartbeat + TTL, subscribe-on-view to avoid broadcast storms | Privacy settings; cost of presence at 100M+ online |
| Security | TLS | Auth on connect; media via pre-signed URLs | E2EE: server can't read content → no server search, metadata-based abuse detection |

## Building blocks used

**Technologies:** [WebSockets & SSE](../../technologies/websockets-and-sse.md) · [Pub/sub](../../technologies/pub-sub.md) · [Redis](../../technologies/redis.md) · [Cassandra / DynamoDB](../../technologies/cassandra.md) · [Kafka](../../technologies/kafka.md) · [Object storage](../../technologies/object-storage.md) · [CDN](../../technologies/cdn.md) · [Push / email / SMS providers](../../technologies/push-email-sms-providers.md) · [Load balancer](../../technologies/load-balancer.md)

**Concepts:** [Message ordering & sequencing](../../concepts/message-ordering-and-sequencing.md) · [Presence & heartbeats](../../concepts/presence-and-heartbeats.md) · [End-to-end encryption](../../concepts/end-to-end-encryption.md) · [Fan-out](../../concepts/fan-out.md) · [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Consistent hashing](../../concepts/consistent-hashing.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Case studies:** [WhatsApp vs Telegram](../../../case-studies/whatsapp-vs-telegram.md) · [Discord: how it stores trillions of messages](../../../case-studies/discord-message-storage.md)

## The core insight

1. **Gateways are stateful; everything else should be stateless.** Isolate the "holding millions of connections" problem in a thin gateway layer, and keep business logic in services you can deploy freely.
2. **Order comes from a sequence number, not a clock.** One owner per conversation hands out 1, 2, 3…; devices sync with "give me everything after #N".
3. **At-least-once delivery + client message IDs = no loss, no duplicates.** The same pattern as the [notification system](../notification-system/README.md), applied to real-time.
