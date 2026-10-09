package pubsub;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class PubSubTests {
    private static int passed = 0;
    static final Instant T0 = Instant.parse("2026-10-09T04:30:00Z");

    public static void main(String[] args) throws Exception {
        // L4: fan-out, isolation, unsubscribe
        fanOutEverySubscriberGetsEveryMessageInOrder();
        slowSubscriberDoesNotBlockPublisherOrOthers();
        unsubscribeStopsDelivery();
        atMostOnceHandlerExceptionLosesOnlyThatMessage();
        // L5: back-pressure
        dropNewestDropsExactlyTheOverflow();
        dropOldestKeepsTheNewest();
        rejectTellsThePublisher();
        blockWaitsForSpaceThenTimesOut();
        blockPolicyLosesNothingUnderConcurrency();
        // L5: acks, redelivery, DLQ, groups, ordering, idempotency
        nackRedeliversThenSucceeds();
        ackTimeoutRedeliversThenDeadLetters();
        idempotentConsumerAppliesSideEffectOnce();
        perKeyOrderPreservedInConsumerGroup();
        consumerGroupSharesWorkWithoutDuplicates();
        // L6: wildcards, log, retention, shutdown
        wildcardPatternsMatchLikeATopicExchange();
        logReplaysFromEarliestLatestAndCommittedOffsets();
        retentionDeletesByCountAndAge();
        gracefulShutdownDrainsThenStops();
        System.out.println("All " + passed + " tests passed.");
        System.exit(0);   // never hang on a stuck daemon thread
    }

    // ================================================================== helpers

    static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    /** Wait for a STATE (not a guessed number of milliseconds); fail after 10 s. */
    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(1);
        }
    }

    static void ok(String name) {
        passed++;
        System.out.println("  ok  " + name);
    }

    static SubscriptionConfig atLeastOnce() {
        return SubscriptionConfig.defaults().withMode(DeliveryMode.AT_LEAST_ONCE);
    }

    static List<String> payloads(List<Message> ms) {
        synchronized (ms) { return ms.stream().map(Message::payload).toList(); }
    }

    static List<String> range(int from, int toExclusive) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) out.add(String.valueOf(i));
        return out;
    }

    // ================================================================== L4

    static void fanOutEverySubscriberGetsEveryMessageInOrder() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            List<List<Message>> inboxes = new ArrayList<>();
            for (int s = 0; s < 3; s++) {
                List<Message> inbox = Collections.synchronizedList(new ArrayList<>());
                inboxes.add(inbox);
                b.subscribe("orders", inbox::add);
            }
            for (int i = 0; i < 1000; i++) assertEquals(3, b.publish("orders", null, String.valueOf(i)).accepted(), "3 copies");
            for (List<Message> inbox : inboxes) {
                await(() -> inbox.size() == 1000, "all 1000 delivered");
                assertEquals(range(0, 1000), payloads(inbox), "same order as published");
            }
        }
        ok("fan-out: 3 subscribers each get all 1,000 messages, in publish order");
    }

    static void slowSubscriberDoesNotBlockPublisherOrOthers() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Message> slow = Collections.synchronizedList(new ArrayList<>());
            List<Message> fast = Collections.synchronizedList(new ArrayList<>());
            b.subscribe("orders", m -> { slow.add(m); awaitQuietly(gate); });
            b.subscribe("orders", fast::add);
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) b.publish("orders", null, String.valueOf(i));
            long publishMillis = (System.nanoTime() - start) / 1_000_000;
            await(() -> fast.size() == 100, "fast subscriber got everything");
            await(() -> slow.size() == 1, "slow subscriber received its first message");
            assertEquals(1, slow.size(), "...and is stuck on it");
            assertTrue(publishMillis < 1000, "publisher was not held up by the slow subscriber (" + publishMillis + " ms)");
            gate.countDown();
            await(() -> slow.size() == 100, "slow subscriber catches up");
        }
        ok("a stuck subscriber blocks neither the publisher nor the other subscriber");
    }

    static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void unsubscribeStopsDelivery() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            List<Message> inbox = Collections.synchronizedList(new ArrayList<>());
            Subscription s = b.subscribe("orders", inbox::add);
            b.publish("orders", null, "before");
            await(() -> inbox.size() == 1, "first delivered");
            s.close();
            assertEquals(0, b.publish("orders", null, "after").accepted(), "nobody is subscribed any more");
            Thread.sleep(20);
            assertEquals(List.of("before"), payloads(inbox), "nothing after unsubscribe");
        }
        ok("unsubscribe: no more deliveries, publish reaches 0 subscribers");
    }

    static void atMostOnceHandlerExceptionLosesOnlyThatMessage() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            List<Message> inbox = Collections.synchronizedList(new ArrayList<>());
            Subscription s = b.subscribe("orders", m -> {
                if (m.payload().equals("2")) throw new IllegalStateException("bug in handler");
                inbox.add(m);
            });
            for (int i = 1; i <= 3; i++) b.publish("orders", null, String.valueOf(i));
            await(() -> inbox.size() == 2, "1 and 3 delivered");
            assertEquals(List.of("1", "3"), payloads(inbox), "2 is lost, the consumer thread survived");
            assertEquals(1L, s.stats().handlerErrors(), "error counted");
            assertEquals(0L, s.stats().redelivered(), "at-most-once never redelivers");
        }
        ok("at-most-once: a throwing handler loses that message only; consumer keeps going");
    }

    // ================================================================== L5: back-pressure

    /** A subscription whose handler blocks on the first message, so the queue (capacity 5) fills up. */
    record Stuck(Subscription sub, List<Message> inbox, CountDownLatch gate, List<Broker.PublishResult> results) {}

    static Stuck stuck(Broker b, OverflowPolicy policy, int publishCount) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        List<Message> inbox = Collections.synchronizedList(new ArrayList<>());
        Subscription s = b.subscribe("ticks", "stuck", SubscriptionConfig.defaults().withCapacity(5).withOverflow(policy),
                MessageHandler.autoAck(m -> { inbox.add(m); entered.countDown(); awaitQuietly(gate); }));
        List<Broker.PublishResult> results = new ArrayList<>();
        results.add(b.publish("ticks", null, "0"));
        entered.await();   // message 0 is now inside the handler; the queue is empty
        for (int i = 1; i < publishCount; i++) results.add(b.publish("ticks", null, String.valueOf(i)));
        return new Stuck(s, inbox, gate, results);
    }

    static void dropNewestDropsExactlyTheOverflow() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            Stuck t = stuck(b, OverflowPolicy.dropNewest(), 20);
            assertEquals(14, t.results().stream().mapToInt(Broker.PublishResult::dropped).sum(), "20 - 1 in handler - 5 queued");
            t.gate().countDown();
            await(() -> t.inbox().size() == 6, "drained");
            assertEquals(range(0, 6), payloads(t.inbox()), "the first six survive");
            assertEquals(14L, t.sub().stats().dropped(), "drop counter");
        }
        ok("drop-newest: exactly 14 of 20 dropped, the oldest 6 delivered");
    }

    static void dropOldestKeepsTheNewest() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            Stuck t = stuck(b, OverflowPolicy.dropOldest(), 20);
            assertEquals(20, t.results().stream().mapToInt(Broker.PublishResult::accepted).sum(), "every new message gets in");
            assertEquals(14, t.results().stream().mapToInt(Broker.PublishResult::dropped).sum(), "14 evictions");
            t.gate().countDown();
            await(() -> t.inbox().size() == 6, "drained");
            assertEquals(List.of("0", "15", "16", "17", "18", "19"), payloads(t.inbox()), "the newest five survive");
        }
        ok("drop-oldest: 14 evicted, the newest 5 (plus the one in progress) delivered");
    }

    static void rejectTellsThePublisher() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            Stuck t = stuck(b, OverflowPolicy.reject(), 20);
            long rejected = t.results().stream().filter(r -> r.rejected() == 1).count();
            assertEquals(14L, rejected, "publisher saw 14 errors");
            assertTrue(t.results().get(19).rejected() == 1 && !t.results().get(19).fullyAccepted(), "result says so");
            t.gate().countDown();
            await(() -> t.inbox().size() == 6, "drained");
            assertEquals(range(0, 6), payloads(t.inbox()), "accepted ones delivered");
        }
        ok("reject: the publisher gets 14 rejections it can act on");
    }

    static void blockWaitsForSpaceThenTimesOut() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            Stuck t = stuck(b, OverflowPolicy.block(Duration.ofMillis(300)), 6);   // 1 in handler + 5 queued: full
            long start = System.nanoTime();
            Broker.PublishResult r = b.publish("ticks", null, "late");
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(1, r.rejected(), "rejected after the timeout");
            assertTrue(waitedMs >= 250, "publisher really waited (" + waitedMs + " ms)");

            Thread publisher = Thread.ofPlatform().start(() -> b.publish("ticks", null, "waits"));
            await(() -> publisher.getState() == Thread.State.TIMED_WAITING, "publisher blocked on a full queue");
            t.gate().countDown();   // consumer frees space
            publisher.join(5000);
            await(() -> t.inbox().size() == 7, "everything accepted was delivered");
            assertEquals(List.of("0", "1", "2", "3", "4", "5", "waits"), payloads(t.inbox()), "in order");
        }
        ok("block: publisher waits for space; gives up (rejected) after its timeout");
    }

    static void blockPolicyLosesNothingUnderConcurrency() throws Exception {
        int publishers = 8, perPublisher = 5000, total = publishers * perPublisher;
        try (Broker b = new Broker(new ManualClock(T0))) {
            List<Subscription> subs = new ArrayList<>();
            List<Map<String, Integer>> lastSeq = new ArrayList<>();
            AtomicInteger outOfOrder = new AtomicInteger();
            for (int s = 0; s < 3; s++) {
                Map<String, Integer> last = new ConcurrentHashMap<>();
                lastSeq.add(last);
                subs.add(b.subscribe("events", "s" + s,
                        atLeastOnce().withCapacity(8).withOverflow(OverflowPolicy.block(Duration.ofSeconds(30))),
                        MessageHandler.autoAck(m -> {
                            String[] p = m.payload().split(":");   // "publisher:seq"
                            int seq = Integer.parseInt(p[1]);
                            Integer prev = last.put(p[0], seq);
                            if (prev != null && prev != seq - 1) outOfOrder.incrementAndGet();
                        })));
            }
            AtomicInteger notFullyAccepted = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int p = 0; p < publishers; p++) {
                String id = "p" + p;
                threads.add(Thread.ofPlatform().start(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < perPublisher; i++)
                        if (!b.publish("events", null, id + ":" + i).fullyAccepted()) notFullyAccepted.incrementAndGet();
                }));
            }
            start.countDown();
            for (Thread t : threads) t.join();
            assertEquals(0, notFullyAccepted.get(), "no publish was dropped or rejected");
            for (Subscription s : subs) await(() -> s.stats().acked() == total, s.name() + " acked all " + total);
            for (Subscription s : subs) assertEquals(0L, s.stats().dropped() + s.stats().rejected(), "no loss in " + s.name());
            assertEquals(0, outOfOrder.get(), "each publisher's messages arrive in its order");
        }
        ok("block under load: 8 publishers x 5,000 into 3 subscriptions (capacity 8): 0 lost, per-publisher order kept");
    }

    // ================================================================== L5: acks, redelivery, groups

    static void nackRedeliversThenSucceeds() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            Subscription s = b.subscribe("orders", "billing", atLeastOnce(), d -> {
                if (d.attempt() == 1) throw new RuntimeException("database hiccup");
                d.ack();
            });
            b.publish("orders", null, "order-1");
            await(() -> s.stats().acked() == 1, "acked on the retry");
            assertEquals(2L, s.stats().delivered(), "delivered twice");
            assertEquals(1L, s.stats().redelivered(), "one redelivery");
        }
        ok("at-least-once: an exception is a nack, the message comes back and succeeds");
    }

    static void ackTimeoutRedeliversThenDeadLetters() throws Exception {
        ManualClock clock = new ManualClock(T0);
        try (Broker b = new Broker(clock)) {
            List<Message> dlq = Collections.synchronizedList(new ArrayList<>());
            b.subscribe("orders.dlq", dlq::add);
            BlockingQueue<Delivery> seen = new LinkedBlockingQueue<>();
            Subscription s = b.subscribe("orders", "billing",
                    atLeastOnce().withAckTimeout(Duration.ofSeconds(30)).withMaxAttempts(3).withDeadLetterTopic("orders.dlq"),
                    seen::add);   // never acks: a consumer that hangs or crashed
            b.publish("orders", null, "poison");
            b.publish("orders", null, "next");
            Delivery first = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Delivery d = seen.poll(5, TimeUnit.SECONDS);
                assertTrue(d != null, "attempt " + attempt + " delivered");
                if (first == null) first = d;
                assertEquals("poison", d.message().payload(), "same message, 'next' waits behind it");
                assertEquals(attempt, d.attempt(), "attempt number");
                clock.advance(Duration.ofSeconds(29));
                b.sweep();
                assertTrue(seen.poll(30, TimeUnit.MILLISECONDS) == null, "not redelivered before the 30 s deadline");
                clock.advance(Duration.ofSeconds(1));
                b.sweep();
            }
            Delivery afterDlq = seen.poll(5, TimeUnit.SECONDS);
            assertEquals("next", afterDlq.message().payload(), "the lane moves on after dead-lettering");
            await(() -> dlq.size() == 1, "poison message is in the DLQ");
            Message dead = dlq.get(0);
            assertEquals("poison", dead.payload(), "DLQ payload");
            assertEquals("3", dead.headers().get("dlq.attempts"), "DLQ says how many attempts");
            assertEquals("orders", dead.headers().get("dlq.originalTopic"), "DLQ says where it came from");
            first.ack();   // a very late ack for attempt 1: ignored
            assertEquals(0L, s.stats().acked(), "late ack ignored");
            assertEquals(1L, s.stats().deadLettered(), "dead-letter counter");
            assertEquals(2L, s.stats().redelivered(), "two redeliveries");
        }
        ok("ack timeout: redelivered at exactly 30 s, twice, then dead-lettered with headers; late ack ignored");
    }

    static void idempotentConsumerAppliesSideEffectOnce() throws Exception {
        ManualClock clock = new ManualClock(T0);
        try (Broker b = new Broker(clock)) {
            AtomicInteger applied = new AtomicInteger();
            IdempotentHandler idem = new IdempotentHandler(m -> applied.incrementAndGet(), 10_000);
            Subscription s = b.subscribe("payments", "ledger", atLeastOnce().withAckTimeout(Duration.ofSeconds(10)), d -> {
                idem.processOnce(d.message());
                if (d.attempt() > 1) d.ack();   // attempt 1: work done, but the ack is "lost"
            });
            b.publish("payments", "acct-7", "credit 500");
            await(() -> s.stats().delivered() == 1, "first delivery");
            clock.advance(Duration.ofSeconds(10));
            b.sweep();
            await(() -> s.stats().acked() == 1, "acked on the redelivery");
            assertEquals(2L, s.stats().delivered(), "delivered twice (at-least-once)");
            assertEquals(1, applied.get(), "side effect applied once");
            assertEquals(1L, idem.duplicates(), "one duplicate skipped");
        }
        ok("idempotent consumer: lost ack -> redelivery -> duplicate skipped, side effect once");
    }

    static void perKeyOrderPreservedInConsumerGroup() throws Exception {
        int publishers = 8, keysPerPublisher = 5, perKey = 500;
        try (Broker b = new Broker(new ManualClock(T0))) {
            Map<String, Integer> lastSeq = new ConcurrentHashMap<>();
            Map<String, Set<String>> threadsPerKey = new ConcurrentHashMap<>();
            AtomicInteger violations = new AtomicInteger();
            Subscription s = b.subscribe("orders", "fulfilment",
                    atLeastOnce().withConsumers(4).withCapacity(16).withOverflow(OverflowPolicy.block(Duration.ofSeconds(30))),
                    MessageHandler.autoAck(m -> {
                        int seq = Integer.parseInt(m.payload());
                        Integer prev = lastSeq.put(m.key(), seq);
                        if (seq != (prev == null ? 0 : prev + 1)) violations.incrementAndGet();
                        threadsPerKey.computeIfAbsent(m.key(), k -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread().getName());
                    }));
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < publishers; p++) {
                int pid = p;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int seq = 0; seq < perKey; seq++)
                        for (int k = 0; k < keysPerPublisher; k++) b.publish("orders", "order-" + pid + "-" + k, String.valueOf(seq));
                }));
            }
            for (Thread t : threads) t.join();
            int total = publishers * keysPerPublisher * perKey;
            await(() -> s.stats().acked() == total, "all " + total + " acked");
            assertEquals(0, violations.get(), "every key's messages arrived 0,1,2,... with no gaps or swaps");
            assertTrue(threadsPerKey.values().stream().allMatch(t -> t.size() == 1), "each key is handled by exactly one consumer");
            long consumersUsed = threadsPerKey.values().stream().flatMap(Set::stream).distinct().count();
            assertEquals(4L, consumersUsed, "40 keys spread over all 4 consumers");
        }
        ok("per-key order: 40 keys x 500 from 8 publishers, 4 consumers: in order, one consumer per key");
    }

    static void consumerGroupSharesWorkWithoutDuplicates() throws Exception {
        int total = 10_000;
        try (Broker b = new Broker(new ManualClock(T0))) {
            Map<String, Integer> groupSeen = new ConcurrentHashMap<>(), auditSeen = new ConcurrentHashMap<>();
            Map<String, Integer> perConsumer = new ConcurrentHashMap<>();
            Subscription group = b.subscribe("clicks", "workers", atLeastOnce().withConsumers(4).withCapacity(64),
                    MessageHandler.autoAck(m -> {
                        groupSeen.merge(m.id(), 1, Integer::sum);
                        perConsumer.merge(Thread.currentThread().getName(), 1, Integer::sum);
                    }));
            Subscription audit = b.subscribe("clicks", "audit", atLeastOnce().withCapacity(64),
                    MessageHandler.autoAck(m -> auditSeen.merge(m.id(), 1, Integer::sum)));
            List<Thread> threads = new ArrayList<>();
            for (int p = 0; p < 4; p++)
                threads.add(Thread.ofPlatform().start(() -> { for (int i = 0; i < total / 4; i++) b.publish("clicks", null, "c"); }));
            for (Thread t : threads) t.join();
            await(() -> group.stats().acked() == total && audit.stats().acked() == total, "both subscriptions done");
            assertEquals(total, groupSeen.size(), "group processed every message");
            assertTrue(groupSeen.values().stream().allMatch(c -> c == 1), "...exactly once (no duplicates inside the group)");
            assertEquals(total, auditSeen.size(), "the other subscription also got every message (fan-out)");
            assertEquals(4, perConsumer.size(), "all 4 consumers did work");
            assertTrue(perConsumer.values().stream().allMatch(c -> c >= total / 8), "work is shared " + perConsumer);
        }
        ok("consumer group: 10,000 messages shared by 4 consumers, none twice; a second subscription gets all");
    }

    // ================================================================== L6

    static void wildcardPatternsMatchLikeATopicExchange() throws Exception {
        TopicTrie<String> trie = new TopicTrie<>();
        for (String p : List.of("orders.*", "orders.#", "*.created", "#", "orders.eu.created", "payments.#")) trie.add(p, p);
        assertEquals(Set.of("orders.*", "orders.#", "*.created", "#"), Set.copyOf(trie.match("orders.created")), "orders.created");
        assertEquals(Set.of("orders.#", "#", "orders.eu.created"), Set.copyOf(trie.match("orders.eu.created")), "* is one word only");
        assertEquals(Set.of("orders.#", "#"), Set.copyOf(trie.match("orders")), "# matches zero words");
        assertEquals(Set.of("#"), Set.copyOf(trie.match("shipping.eu.delayed")), "only the catch-all");
        trie.remove("#", "#");
        assertEquals(List.of(), trie.match("shipping.eu.delayed"), "removed");
        try (Broker b = new Broker(new ManualClock(T0))) {
            List<Message> eu = Collections.synchronizedList(new ArrayList<>());
            b.subscribe("orders.eu.*", eu::add);
            b.subscribe("payments.#", m -> { });
            assertEquals(1, b.publish("orders.eu.created", null, "x").accepted(), "routed to orders.eu.*");
            assertEquals(0, b.publish("orders.us.created", null, "y").accepted(), "not routed");
            await(() -> eu.size() == 1, "delivered");
        }
        ok("wildcards: * = one word, # = zero or more words, via the trie and through the broker");
    }

    static void logReplaysFromEarliestLatestAndCommittedOffsets() throws Exception {
        try (Broker b = new Broker(new ManualClock(T0))) {
            TopicLog log = b.retain("orders", 1000, Duration.ofDays(7));
            for (int i = 0; i < 10; i++) assertEquals((long) i, b.publish("orders", null, "o" + i).logOffset(), "offset");
            List<TopicLog.Record> batch = log.poll("billing", TopicLog.StartFrom.EARLIEST, 100);
            assertEquals(10, batch.size(), "new group from EARLIEST reads history");
            assertEquals(batch, log.poll("billing", TopicLog.StartFrom.EARLIEST, 100), "no commit -> same records again");
            log.commit("billing", batch.get(batch.size() - 1).offset() + 1);
            assertEquals(0L, log.lag("billing"), "caught up");
            assertEquals(List.of(), log.poll("analytics", TopicLog.StartFrom.LATEST, 100), "new group from LATEST skips history");
            b.publish("orders", null, "o10");
            b.publish("orders", null, "o11");
            assertEquals(2L, log.lag("billing"), "lag = end - committed");
            assertEquals(List.of(10L, 11L), log.poll("analytics", TopicLog.StartFrom.LATEST, 100).stream().map(TopicLog.Record::offset).toList(), "only new");
            log.commit("billing", 0);   // replay: e.g. after fixing a bug in billing
            assertEquals(12, log.poll("billing", TopicLog.StartFrom.EARLIEST, 100).size(), "replayed from 0");
        }
        ok("log: offsets, EARLIEST vs LATEST, poll without commit repeats, lag, replay by committing an old offset");
    }

    static void retentionDeletesByCountAndAge() throws Exception {
        ManualClock clock = new ManualClock(T0);
        try (Broker b = new Broker(clock)) {
            TopicLog log = b.retain("prices", 5, Duration.ofMinutes(1));
            b.publish("prices", "INFY", "1500");
            assertEquals(1, log.poll("slow", TopicLog.StartFrom.EARLIEST, 1).size(), "slow group starts at 0");
            for (int i = 1; i < 8; i++) b.publish("prices", "INFY", String.valueOf(1500 + i));
            assertEquals(3L, log.earliestOffset(), "only the newest 5 kept (offsets 3..7)");
            assertEquals(5L, log.lag("slow"), "lag counts only what still exists");
            assertEquals(3L, log.poll("slow", TopicLog.StartFrom.EARLIEST, 10).get(0).offset(), "fell behind: skipped to earliest");
            clock.advance(Duration.ofSeconds(61));
            b.sweep();
            assertEquals(8L, log.earliestOffset(), "all older than 1 minute: deleted");
            assertEquals(8L, log.endOffset(), "offsets are never reused");
            assertEquals(0L, log.lag("slow"), "nothing left to read");
        }
        ok("retention: by count (keep 5) and by age (1 minute); offsets keep counting");
    }

    static void gracefulShutdownDrainsThenStops() throws Exception {
        Broker b = new Broker(new ManualClock(T0));
        AtomicInteger done = new AtomicInteger();
        b.subscribe("jobs", "workers", atLeastOnce().withConsumers(2), MessageHandler.autoAck(m -> {
            sleepQuietly(1);
            done.incrementAndGet();
        }));
        for (int i = 0; i < 200; i++) b.publish("jobs", null, "j" + i);
        assertEquals(0, b.shutdown(Duration.ofSeconds(10)), "nothing left behind");
        assertEquals(200, done.get(), "every queued job finished before shutdown returned");
        try {
            b.publish("jobs", null, "late");
            throw new AssertionError("publish after shutdown must fail");
        } catch (IllegalStateException expected) { }

        Broker b2 = new Broker(new ManualClock(T0));
        CountDownLatch never = new CountDownLatch(1);
        b2.subscribe("jobs", "stuck", atLeastOnce(), d -> awaitQuietly(never));
        for (int i = 0; i < 5; i++) b2.publish("jobs", null, "j" + i);
        assertEquals(5, b2.shutdown(Duration.ofMillis(100)), "grace period over: 5 reported as unfinished");
        ok("graceful shutdown: drains within the grace period, reports what it couldn't finish");
    }

    static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
