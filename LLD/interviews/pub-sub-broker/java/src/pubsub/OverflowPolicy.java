package pubsub;

import java.time.Duration;

/**
 * Strategy: what a subscription does when a publisher hands it a message and its queue is full.
 * Each policy is a tiny record; the subscription doesn't know which one it has.
 */
public sealed interface OverflowPolicy {

    enum Admission { ACCEPTED, EVICTED_OLDEST, DROPPED, REJECTED }

    Admission admit(Lane lane, Message m) throws InterruptedException;

    /** Publisher waits for space, at most {@code timeout}; then the message is rejected. Nothing is lost silently. */
    static OverflowPolicy block(Duration timeout) { return new Block(timeout); }
    /** The incoming message is thrown away. Good for metrics, where the next sample replaces it anyway. */
    static OverflowPolicy dropNewest() { return new DropNewest(); }
    /** The oldest waiting message is thrown away to make room. Good for "latest state" feeds (prices, positions). */
    static OverflowPolicy dropOldest() { return new DropOldest(); }
    /** The publisher gets an immediate error and decides itself (retry later, fail the request, ...). */
    static OverflowPolicy reject() { return new Reject(); }

    record Block(Duration timeout) implements OverflowPolicy {
        public Admission admit(Lane lane, Message m) throws InterruptedException { return lane.addWaiting(m, timeout); }
    }

    record DropNewest() implements OverflowPolicy {
        public Admission admit(Lane lane, Message m) { return lane.tryAdd(m, Admission.DROPPED); }
    }

    record DropOldest() implements OverflowPolicy {
        public Admission admit(Lane lane, Message m) { return lane.addEvictingOldest(m); }
    }

    record Reject() implements OverflowPolicy {
        public Admission admit(Lane lane, Message m) { return lane.tryAdd(m, Admission.REJECTED); }
    }
}
