# LLD — Low-Level Design

## Interviews
| Problem | What it mainly teaches | Code |
|---|---|---|
| [Rate Limiter](interviews/rate-limiter/README.md) | Algorithms, Strategy/Factory, thread safety, testable time, distributed atomicity | [Java](interviews/rate-limiter/java/) · [JS](interviews/rate-limiter/js/) |

## Libraries — Java
| | Use it for |
|---|---|
| [ConcurrentHashMap](libraries/java/concurrent-hashmap.md) | Thread-safe maps; atomic `computeIfAbsent` |
| [Atomics & CAS](libraries/java/atomics-and-cas.md) | Lock-free counters and state swaps |
| [Locks & synchronized](libraries/java/locks-and-synchronized.md) | Making read-modify-write atomic |
| [ScheduledExecutorService](libraries/java/scheduled-executor-service.md) | Periodic background work (cleanup) |
| [Time & Clock](libraries/java/time-and-clock.md) | Measuring intervals correctly; fake time in tests |
| [Production rate-limit libraries](libraries/java/production-rate-limit-libraries.md) | Guava, Bucket4j, Resilience4j, and gateway-level limiting |

## Libraries — JavaScript
| | Use it for |
|---|---|
| [Event loop & concurrency](libraries/js/event-loop-and-concurrency.md) | Why Node needs no locks — and where races still happen |
| [Map vs Object](libraries/js/map-vs-object.md) | Choosing a key→value registry |
| [Express middleware](libraries/js/express-middleware.md) | Plugging cross-cutting logic into HTTP handling |

## Concepts
[Design patterns](concepts/design-patterns.md) · [SOLID](concepts/solid-principles.md) · [Thread-safety basics](concepts/thread-safety-basics.md)
