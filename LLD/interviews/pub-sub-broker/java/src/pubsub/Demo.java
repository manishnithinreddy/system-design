package pubsub;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A guided tour: fan-out, back-pressure policies, ack timeout -> DLQ, consumer group with keys, replay. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-09T04:30:00Z"));
        Broker broker = new Broker(clock);

        System.out.println("--- 1. Fan-out: every subscription gets its own copy; a stuck one hurts nobody ---");
        CountDownLatch gate = new CountDownLatch(1);
        List<String> email = Collections.synchronizedList(new ArrayList<>()), analytics = Collections.synchronizedList(new ArrayList<>());
        broker.subscribe("orders.created", m -> email.add(m.payload()));
        broker.subscribe("orders.*", m -> { analytics.add(m.payload()); await(gate); });
        for (int i = 1; i <= 5; i++) broker.publish("orders.created", null, "order-" + i);
        waitFor(() -> email.size() == 5 && analytics.size() == 1);
        System.out.println("  email     got " + email);
        System.out.println("  analytics got " + analytics + " (stuck in its handler, 4 more waiting in its own queue)");
        gate.countDown();
        waitFor(() -> analytics.size() == 5);
        System.out.println("  analytics unstuck, now has " + analytics.size());

        System.out.println("--- 2. Back-pressure: queue of 3, handler stuck on message 0, then 1..7 published ---");
        for (OverflowPolicy policy : List.of(OverflowPolicy.dropNewest(), OverflowPolicy.dropOldest(), OverflowPolicy.reject(),
                OverflowPolicy.block(Duration.ofMillis(20)))) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            List<String> got = Collections.synchronizedList(new ArrayList<>());
            String topic = "ticks." + policy.getClass().getSimpleName().toLowerCase();
            Subscription s = broker.subscribe(topic, topic, SubscriptionConfig.defaults().withCapacity(3).withOverflow(policy),
                    MessageHandler.autoAck(m -> { got.add(m.payload()); entered.countDown(); await(release); }));
            broker.publish(topic, null, "0");
            entered.await();
            int dropped = 0, rejected = 0;
            for (int i = 1; i <= 7; i++) {
                Broker.PublishResult r = broker.publish(topic, null, String.valueOf(i));
                dropped += r.dropped();
                rejected += r.rejected();
            }
            release.countDown();
            waitFor(() -> got.size() == 4);
            System.out.printf("  %-12s delivered %s  dropped=%d rejected(publisher told)=%d%n", policy.getClass().getSimpleName(), got, dropped, rejected);
            s.close();
        }

        System.out.println("--- 3. At-least-once: a consumer that never acks (ack timeout 30 s, max 3 attempts) ---");
        broker.subscribe("payments.dlq", m -> System.out.println("  DLQ received " + m.payload() + " after " + m.headers().get("dlq.attempts") + " attempts"));
        LinkedBlockingQueue<Delivery> seen = new LinkedBlockingQueue<>();
        broker.subscribe("payments", "ledger", SubscriptionConfig.defaults().withMode(DeliveryMode.AT_LEAST_ONCE)
                .withAckTimeout(Duration.ofSeconds(30)).withMaxAttempts(3).withDeadLetterTopic("payments.dlq"), seen::add);
        broker.publish("payments", "acct-7", "pay-42");
        for (int i = 0; i < 3; i++) {
            Delivery d = seen.poll(5, TimeUnit.SECONDS);
            System.out.println("  t=+" + (i * 30) + "s delivered " + d.message().payload() + " attempt " + d.attempt() + ", no ack...");
            clock.advance(Duration.ofSeconds(30));
            broker.sweep();
        }
        Thread.sleep(50);   // let the DLQ subscriber print

        System.out.println("--- 4. Consumer group of 3 with keys: one consumer per key, order kept per key ---");
        Map<String, List<String>> byKey = new ConcurrentHashMap<>();
        Map<String, String> owner = new ConcurrentHashMap<>();
        Subscription group = broker.subscribe("shipments", "tracking", SubscriptionConfig.defaults().withConsumers(3),
                MessageHandler.autoAck(m -> {
                    byKey.computeIfAbsent(m.key(), k -> Collections.synchronizedList(new ArrayList<>())).add(m.payload());
                    owner.put(m.key(), Thread.currentThread().getName());
                }));
        for (String step : List.of("packed", "shipped", "delivered"))
            for (String parcel : List.of("parcel-A", "parcel-B", "parcel-C", "parcel-D")) broker.publish("shipments", parcel, step);
        waitFor(() -> group.stats().delivered() == 12);
        new TreeMap<>(byKey).forEach((k, v) -> System.out.println("  " + k + " -> " + owner.get(k) + " saw " + v));

        System.out.println("--- 5. The log model: retained, replayable, offsets per group ---");
        TopicLog log = broker.retain("audit", 1000, Duration.ofDays(7));
        for (int i = 0; i < 5; i++) broker.publish("audit", null, "event-" + i);
        var batch = log.poll("billing", TopicLog.StartFrom.EARLIEST, 3);
        System.out.println("  billing polled offsets " + batch.stream().map(TopicLog.Record::offset).toList() + ", commits 3, lag " + lagAfter(log, "billing", 3));
        System.out.println("  new group 'fraud' from EARLIEST reads " + log.poll("fraud", TopicLog.StartFrom.EARLIEST, 100).size() + " records (history is still there)");
        log.commit("billing", 0);
        System.out.println("  billing replays from offset 0: " + log.poll("billing", TopicLog.StartFrom.EARLIEST, 100).size() + " records");

        System.out.println("--- 6. Graceful shutdown ---");
        System.out.println("  unfinished messages at shutdown: " + broker.shutdown(Duration.ofSeconds(5)));
    }

    static long lagAfter(TopicLog log, String group, long offset) {
        log.commit(group, offset);
        return log.lag(group);
    }

    static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    }
}
