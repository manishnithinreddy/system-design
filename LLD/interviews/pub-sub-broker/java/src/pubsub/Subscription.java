package pubsub;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import pubsub.OverflowPolicy.Admission;

/**
 * A named subscription to a topic pattern. Every subscription gets its own copy of each message (fan-out);
 * inside one subscription, {@code consumers} threads share the work (a consumer group / competing consumers).
 * Each consumer thread owns one lane, so a slow handler only slows its own lane, never the publisher
 * (unless the lane is full and the policy is BLOCK) and never other subscriptions.
 */
public final class Subscription implements AutoCloseable {

    /** A snapshot of the counters. {@code backlog} is the queue-model "lag": messages not yet finished. */
    public record Stats(long offered, long dropped, long rejected, long delivered, long redelivered,
                        long acked, long deadLettered, long handlerErrors, int backlog) {}

    private final String name;
    private final String pattern;
    private final SubscriptionConfig config;
    private final MessageHandler handler;
    private final Broker broker;
    private final Clock clock;
    private final Lane[] lanes;
    private final Thread[] consumers;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final LongAdder offered = new LongAdder(), dropped = new LongAdder(), rejected = new LongAdder(),
            delivered = new LongAdder(), redelivered = new LongAdder(), acked = new LongAdder(),
            deadLettered = new LongAdder(), handlerErrors = new LongAdder();

    Subscription(String name, String pattern, SubscriptionConfig config, MessageHandler handler, Broker broker, Clock clock) {
        this.name = name;
        this.pattern = pattern;
        this.config = config;
        this.handler = handler;
        this.broker = broker;
        this.clock = clock;
        this.lanes = new Lane[config.consumers()];
        this.consumers = new Thread[config.consumers()];
        for (int i = 0; i < lanes.length; i++) {
            Lane lane = lanes[i] = new Lane(config.capacityPerLane());
            consumers[i] = Thread.ofPlatform().daemon().name(name + "-consumer-" + i).start(() -> consume(lane));
        }
    }

    public String name() { return name; }
    public String pattern() { return pattern; }

    // ------------------------------------------------------------------ publish side

    /** Called on the publisher's thread. Picks a lane, then lets the overflow policy decide. */
    Admission offer(Message m) throws InterruptedException {
        offered.increment();
        Admission a = config.overflow().admit(laneFor(m), m);
        switch (a) {
            case DROPPED, EVICTED_OLDEST -> dropped.increment();
            case REJECTED -> rejected.increment();
            case ACCEPTED -> { }
        }
        return a;
    }

    /** Same key, same lane: per-key order. No key: spread round-robin for throughput. */
    private Lane laneFor(Message m) {
        if (m.key() != null) return lanes[Math.floorMod(m.key().hashCode(), lanes.length)];
        return lanes[Math.floorMod(roundRobin.getAndIncrement(), lanes.length)];
    }

    // ------------------------------------------------------------------ consumer side

    private void consume(Lane lane) {
        while (true) {
            Delivery d;
            try {
                d = lane.takeNext((m, attempt) -> new Delivery(m, attempt, clock.instant().plus(config.ackTimeout()),
                        this, lane, config.mode() == DeliveryMode.AT_MOST_ONCE));
            } catch (InterruptedException e) {
                return;
            }
            if (d == null) return;   // lane closed
            delivered.increment();
            if (d.attempt() > 1) redelivered.increment();
            try {
                handler.onMessage(d);
            } catch (Exception e) {   // one bad message must not kill the consumer thread
                handlerErrors.increment();
                d.nack();             // AT_MOST_ONCE: a no-op, the message is gone
            } finally {
                lane.handlerReturned();
            }
        }
    }

    void acked(Delivery d) {
        acked.increment();
        d.lane().finish(d);
    }

    /** Nack or ack timeout: redeliver, or after maxAttempts move it to the dead-letter topic. */
    void failed(Delivery d) {
        if (d.attempt() < config.maxAttempts()) {
            d.lane().redeliver(d);
            return;
        }
        if (config.deadLetterTopic() != null) {
            // Publish to the DLQ BEFORE releasing the lane: a crash in between gives a duplicate, never a loss.
            broker.deadLetter(d.message().copyTo(config.deadLetterTopic(), Map.of(
                    "dlq.subscription", name, "dlq.attempts", String.valueOf(d.attempt()), "dlq.originalTopic", d.message().topic())));
        }
        deadLettered.increment();
        d.lane().finish(d);
    }

    /** Expires deliveries whose ack deadline passed. Called by the broker's sweeper (or a test). */
    void sweep(Instant now) {
        for (Lane lane : lanes) {
            Delivery d = lane.expireOverdue(now);
            if (d != null) failed(d);
        }
    }

    // ------------------------------------------------------------------ lifecycle and metrics

    /** Waits (real time) until every lane is empty, acked and idle. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Lane lane : lanes) if (!lane.awaitIdle(deadline)) return false;
        return true;
    }

    /** Unsubscribe: stop routing new messages here, stop the consumer threads, discard what's left. */
    @Override public void close() {
        broker.unsubscribe(this);
        for (Lane lane : lanes) lane.close();
        for (Thread t : consumers) t.interrupt();   // wakes a handler stuck in a blocking call
    }

    public Stats stats() {
        int backlog = 0;
        for (Lane lane : lanes) backlog += lane.backlog();
        return new Stats(offered.sum(), dropped.sum(), rejected.sum(), delivered.sum(), redelivered.sum(),
                acked.sum(), deadLettered.sum(), handlerErrors.sum(), backlog);
    }
}
