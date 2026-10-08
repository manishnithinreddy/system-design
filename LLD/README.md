# LLD — Low-Level Design

## Interviews
| Problem | What it mainly teaches | Code |
|---|---|---|
| [Rate Limiter](interviews/rate-limiter/README.md) | Algorithms, Strategy/Factory, thread safety, testable time, distributed atomicity | [Java](interviews/rate-limiter/java/) · [JS](interviews/rate-limiter/js/) |
| [Parking Lot](interviews/parking-lot/README.md) | OOP modelling (enum vs inheritance, record vs class), Strategy/Observer/State, money, concurrent spot claiming, DB-level atomicity | [Java](interviews/parking-lot/java/) · [JS](interviews/parking-lot/js/) |
| [LRU Cache](interviews/lru-cache/README.md) | HashMap + doubly linked list, O(1), thread safety where reads are writes, lock striping, TTL, LFU, stampede protection, Caffeine | [Java](interviews/lru-cache/java/) · [JS](interviews/lru-cache/js/) |
| [Elevator System](interviews/elevator-system/README.md) | LOOK scheduling with sorted sets, dispatch cost functions, Command + single-writer concurrency, state machines, maintenance/degraded modes | [Java](interviews/elevator-system/java/) · [JS](interviews/elevator-system/js/) |
| [Splitwise](interviews/splitwise/README.md) | Exact money and largest-remainder splits, sealed variants, append-only ledger, greedy debt simplification, idempotency | [Java](interviews/splitwise/java/) · [JS](interviews/splitwise/js/) |
| [Movie Ticket Booking (BookMyShow)](interviews/movie-booking/README.md) | Seat holds with TTL, all-or-nothing reservations, pessimistic vs optimistic (CAS) concurrency, deadlocks, idempotent confirm | [Java](interviews/movie-booking/java/) · [JS](interviews/movie-booking/js/) |
| [KV Store with Transactions (mini Redis)](interviews/kv-store/README.md) | Nested transactions with undo logs, O(1) derived indexes, TTL, write-ahead log, crash recovery, compaction | [Java](interviews/kv-store/java/) · [JS](interviews/kv-store/js/) |
| [Task Scheduler (cron-like)](interviews/task-scheduler/README.md) | Min-heap + dispatcher that wakes on earlier tasks, fixed-rate vs fixed-delay, cron with time zones and DST, retries with jitter + dead letters, misfires, no overlap, graceful shutdown | [Java](interviews/task-scheduler/java/) · [JS](interviews/task-scheduler/js/) |
| [Logging Framework](interviews/logging-framework/README.md) | Levels and logger hierarchy, Strategy layouts, filter chain, async appender with back-pressure, MDC, JSON escaping, redaction | [Java](interviews/logging-framework/java/) · [JS](interviews/logging-framework/js/) |
| [Vending Machine](interviews/vending-machine/README.md) | State pattern, integer paise, change-making with limited coins (greedy vs DP), refunds on jams and timeouts, idempotent UPI callbacks | [Java](interviews/vending-machine/java/) · [JS](interviews/vending-machine/js/) |
| [Thread Pool / Connection Pool](interviews/thread-pool/README.md) | ThreadPoolExecutor semantics from scratch, rejection policies, sizing, fair connection pool with validation, max lifetime and leak detection | [Java](interviews/thread-pool/java/) · [JS](interviews/thread-pool/js/) |

## Libraries — Java
| | Use it for |
|---|---|
| [ConcurrentHashMap](libraries/java/concurrent-hashmap.md) | Thread-safe maps; atomic `computeIfAbsent` |
| [Atomics & CAS](libraries/java/atomics-and-cas.md) | Lock-free counters and state swaps |
| [Locks & synchronized](libraries/java/locks-and-synchronized.md) | Making read-modify-write atomic |
| [ScheduledExecutorService](libraries/java/scheduled-executor-service.md) | Periodic background work (cleanup) |
| [Time & Clock](libraries/java/time-and-clock.md) | Measuring intervals correctly; fake time in tests |
| [Production rate-limit libraries](libraries/java/production-rate-limit-libraries.md) | Guava, Bucket4j, Resilience4j, and gateway-level limiting |
| [Concurrent collections](libraries/java/concurrent-collections.md) | Skip-list sets, copy-on-write lists, blocking queues |
| [Enums & EnumMap](libraries/java/enums-and-enummap.md) | Fixed categories with data/behaviour; fast enum-keyed maps |
| [Records & immutability](libraries/java/records-and-immutability.md) | Value objects; record vs class |
| [BigDecimal & money](libraries/java/bigdecimal-and-money.md) | Exact money arithmetic and rounding |
| [java.time API](libraries/java/java-time-api.md) | Instant vs LocalDateTime, Duration vs Period, DST |
| [LinkedHashMap](libraries/java/linkedhashmap.md) | A ready-made LRU: access order + removeEldestEntry |
| [Caffeine & Guava Cache](libraries/java/caffeine-and-guava-cache.md) | Production in-process caches |
| [CompletableFuture](libraries/java/completablefuture.md) | Async results; sharing one in-flight load (single-flight) |
| [References & GC](libraries/java/references-and-gc.md) | Weak/soft references, heap and GC cost of caches |
| [TreeSet & PriorityQueue](libraries/java/treeset-and-priorityqueue.md) | Sorted sets with "next above X" queries vs heaps |
| [Blocking queues & producer-consumer](libraries/java/blocking-queues-and-producer-consumer.md) | Handing work between threads safely |
| [Executors & threads](libraries/java/executors-and-threads.md) | Thread pools, virtual threads, shutdown |
| [Streams & collectors](libraries/java/streams-and-collectors.md) | groupingBy, toMap, summing: aggregations without loops |
| [Sealed interfaces & pattern matching](libraries/java/sealed-interfaces-and-pattern-matching.md) | Closed sets of variants with compiler-checked switches |
| [BitSet & compact state](libraries/java/bitset-and-compact-state.md) | One bit per item: compact availability maps |
| [File I/O & fsync](libraries/java/file-io-and-fsync.md) | Writing files that survive crashes |
| [HikariCP & JDBC pools](libraries/java/hikaricp-and-jdbc-pools.md) | Connection pool settings, sizing, leaks, pgbouncer |
| [SLF4J, Logback & Log4j2](libraries/java/slf4j-logback-and-log4j2.md) | Production logging: facade vs implementation, async, MDC, Log4Shell |

## Libraries — JavaScript
| | Use it for |
|---|---|
| [Event loop & concurrency](libraries/js/event-loop-and-concurrency.md) | Why Node needs no locks — and where races still happen |
| [Map vs Object](libraries/js/map-vs-object.md) | Choosing a key→value registry |
| [Express middleware](libraries/js/express-middleware.md) | Plugging cross-cutting logic into HTTP handling |
| [Money & numbers in JS](libraries/js/money-and-numbers-in-js.md) | Floating point traps, integer paise, BigInt, formatting |
| [Classes & private fields](libraries/js/classes-and-private-fields.md) | `#private`, `Object.freeze`, enums in JS |
| [LRU with Map](libraries/js/lru-with-map.md) | Map insertion order as a 20-line LRU |
| [Sorted collections in JS](libraries/js/sorted-collections-in-js.md) | Living without TreeSet |
| [Async/await & timers](libraries/js/async-await-and-timers.md) | Promises, timers, and keeping logic testable |
| [node:test runner](libraries/js/node-test-runner.md) | Built-in testing without Jest |
| [fs & durability in Node](libraries/js/fs-and-durability-in-node.md) | Appending, fsync and atomic renames in Node |
| [Worker threads & the libuv pool](libraries/js/worker-threads-and-libuv-pool.md) | What runs off the event loop, UV_THREADPOOL_SIZE, worker_threads |
| [Logging in Node](libraries/js/logging-in-node.md) | When console.log blocks, pino/winston, AsyncLocalStorage context |

## Concepts
[OOP modelling](concepts/oop-modeling.md) · [Hash map & linked list](concepts/hashmap-and-linked-list.md) · [Cache eviction policies](concepts/cache-eviction-policies.md) · [Big-O complexity](concepts/big-o-complexity.md) · [State machines](concepts/state-machines.md) · [Scheduling algorithms](concepts/scheduling-algorithms.md) · [Single-writer principle](concepts/single-writer-principle.md) · [Splitting money & rounding](concepts/splitting-money-and-rounding.md) · [Ledgers & event sourcing](concepts/ledgers-and-event-sourcing.md) · [Greedy algorithms](concepts/greedy-algorithms.md) · [Optimistic vs pessimistic locking](concepts/optimistic-vs-pessimistic-locking.md) · [Deadlocks & lock ordering](concepts/deadlocks-and-lock-ordering.md) · [Holds, reservations & TTL](concepts/holds-reservations-and-ttl.md) · [Transactions & isolation](concepts/transactions-and-isolation.md) · [Undo & redo logs](concepts/undo-logs-and-redo-logs.md) · [Durability, WAL & snapshots](concepts/durability-wal-and-snapshots.md) · [UML class diagrams](concepts/uml-class-diagrams.md) · [Design patterns](concepts/design-patterns.md) · [SOLID](concepts/solid-principles.md) · [Thread-safety basics](concepts/thread-safety-basics.md) · [Timers, delay queues & timing wheels](concepts/timers-delay-queues-and-timing-wheels.md) · [Cron & recurring schedules](concepts/cron-and-recurring-schedules.md) · [Back-pressure](concepts/back-pressure.md) · [ThreadLocal & context propagation](concepts/thread-local-and-context-propagation.md) · [Coin change & dynamic programming](concepts/coin-change-and-dynamic-programming.md) · [Resource pools & sizing](concepts/resource-pools-and-sizing.md) · [Virtual threads](concepts/virtual-threads.md)
