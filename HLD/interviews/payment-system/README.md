# HLD Interview: Design a Payment System (Marketplace Checkout)

> "Design the payment system for our e-commerce marketplace: buyers pay with cards or UPI, we keep a commission, sellers get paid."

This interview is the opposite of most HLD questions: the throughput is modest (hundreds of payments per second at peak), but **every single request must be right**. It teaches what to do when a network call's outcome is unknown, how to make retries harmless, how money is recorded so it can never silently disappear, and how your records are checked against the outside world every day.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** The "Payment pending… don't press back" story, PSPs, webhooks, ledgers and reconciliation in plain words, plus gateway test modes you can try.

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why idempotency, pending states, ledgers and reconciliation exist |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Payment state machine, idempotency keys in three layers, PSP sync + webhooks + status checks, double-entry ledger in the same transaction, refunds, integer paise |
| [L5-senior.md](L5-senior.md) | Senior | Outbox + idempotent consumers, payment ↔ order saga, unknown outcomes, three-way reconciliation, seller payouts, multi-PSP routing, hot ledger accounts, PCI and tokens |
| [L6-staff.md](L6-staff.md) | Staff | Fraud as expected cost, multi-currency, regulation → regional cells, RPO 0 DR, build vs buy, incremental migration |

**Suggested order:** product page → L4 → L5 → L6 → [See it work: saga with compensations](../../../see-it-work/saga-compensations/README.md).

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Correctness | State machine, conditional updates | Exactly-once effect via outbox | Invariants + four-eyes for corrections |
| Duplicates | Idempotency keys (API, PSP, webhooks) | Idempotent consumers, saga steps | Webhook storms, rate-limited processing |
| Unknown outcomes | PENDING + status checks | Three resolution paths, cutoffs, late success | Failover = in-flight payments go PENDING |
| Ledger | Double-entry in the same transaction | Hot accounts, sharding by merchant | Multi-currency, FX accounts, retention |
| Outside world | One PSP | Several PSPs, routing, reconciliation, payouts | Regional cells, licences, build vs buy |
| Risk | Signature-verified webhooks | PCI scope, tokens | Fraud scoring, step-up, chargeback ratio |

## Building blocks used

**Concepts (new for this problem):** [Payment reconciliation](../../concepts/payment-reconciliation.md)

**Concepts (reused):** [Idempotency & delivery semantics](../../concepts/idempotency-and-delivery-semantics.md) · [Sagas & distributed transactions](../../concepts/sagas-and-distributed-transactions.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Resilience patterns](../../concepts/resilience-patterns.md) · [Counters at scale](../../concepts/counters-at-scale.md) · [Sharding & replication](../../concepts/sharding-and-replication.md) · [Consensus & Raft](../../concepts/consensus-and-raft.md) · [Alerting & SLOs](../../concepts/alerting-and-slos.md) · [TLS & mTLS](../../concepts/tls-and-mtls.md) · [Back-of-the-envelope](../../concepts/back-of-the-envelope.md)

**Technologies:** [Payment gateways & PSPs](../../technologies/payment-gateways-and-psps.md) (new) · [PostgreSQL](../../technologies/postgresql.md) · [Kafka](../../technologies/kafka.md) · [Redis](../../technologies/redis.md)

**LLD concepts:** [State machines](../../../LLD/concepts/state-machines.md) · [Ledgers & event sourcing](../../../LLD/concepts/ledgers-and-event-sourcing.md) · [Transactions & isolation](../../../LLD/concepts/transactions-and-isolation.md) · [Splitting money & rounding](../../../LLD/concepts/splitting-money-and-rounding.md) · [Card & PIN security](../../../LLD/concepts/card-and-pin-security.md) (new)

**Under the Hood:** [Double-entry ledgers](../../../under-the-hood/double-entry-ledgers.md) · [UPI](../../../under-the-hood/upi.md)

**See it work:** [Saga with compensations](../../../see-it-work/saga-compensations/README.md): a runnable order → payment → shipment saga with injected failures, compensations and crash recovery.

**Related LLD:** [ATM / Digital Wallet](../../../LLD/interviews/digital-wallet/README.md): the same ledger, idempotency and state-machine ideas inside one process, in Java and JavaScript.

## The core insight

1. **A timeout is not a failure.** Unknown outcomes get their own state and are resolved from the outside (webhooks, status checks, reconciliation), never guessed.
2. **Make every step safe to repeat.** Idempotency keys from the app to the PSP, idempotent consumers, conditional state transitions: then retries, duplicates and crashes are harmless.
3. **Money is recorded, never overwritten.** Double-entry, append-only, written in the same transaction as the state change, and checked daily against the PSP and the bank.
