package pubsub;

import java.time.Duration;

/**
 * Settings for one subscription. {@code consumers} is the size of the consumer group: that many threads
 * share the work, each owning one lane (a bounded queue of {@code capacityPerLane} messages).
 */
public record SubscriptionConfig(int consumers, int capacityPerLane, OverflowPolicy overflow, DeliveryMode mode,
                                 Duration ackTimeout, int maxAttempts, String deadLetterTopic) {

    public SubscriptionConfig {
        if (consumers < 1 || capacityPerLane < 1 || maxAttempts < 1) throw new IllegalArgumentException("sizes must be >= 1");
    }

    /** The simple L4 behaviour: one consumer, at-most-once, publisher waits up to 1 s if the queue is full. */
    public static SubscriptionConfig defaults() {
        return new SubscriptionConfig(1, 1024, OverflowPolicy.block(Duration.ofSeconds(1)), DeliveryMode.AT_MOST_ONCE,
                Duration.ofSeconds(30), 5, null);
    }

    public SubscriptionConfig withConsumers(int n) { return new SubscriptionConfig(n, capacityPerLane, overflow, mode, ackTimeout, maxAttempts, deadLetterTopic); }
    public SubscriptionConfig withCapacity(int n) { return new SubscriptionConfig(consumers, n, overflow, mode, ackTimeout, maxAttempts, deadLetterTopic); }
    public SubscriptionConfig withOverflow(OverflowPolicy p) { return new SubscriptionConfig(consumers, capacityPerLane, p, mode, ackTimeout, maxAttempts, deadLetterTopic); }
    public SubscriptionConfig withMode(DeliveryMode m) { return new SubscriptionConfig(consumers, capacityPerLane, overflow, m, ackTimeout, maxAttempts, deadLetterTopic); }
    public SubscriptionConfig withAckTimeout(Duration d) { return new SubscriptionConfig(consumers, capacityPerLane, overflow, mode, d, maxAttempts, deadLetterTopic); }
    public SubscriptionConfig withMaxAttempts(int n) { return new SubscriptionConfig(consumers, capacityPerLane, overflow, mode, ackTimeout, n, deadLetterTopic); }
    public SubscriptionConfig withDeadLetterTopic(String t) { return new SubscriptionConfig(consumers, capacityPerLane, overflow, mode, ackTimeout, maxAttempts, t); }
}
