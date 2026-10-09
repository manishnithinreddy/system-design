package pubsub;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import pubsub.OverflowPolicy.Admission;

/**
 * One bounded queue inside a subscription, drained by exactly one consumer thread.
 * Messages with the same key always land in the same lane, so they come out in the order they went in.
 * At most ONE delivery per lane waits for an ack; the next message waits behind it. That is what keeps
 * per-key order even when a message must be redelivered (the redelivery goes first, before newer ones).
 */
final class Lane {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();   // one condition + signalAll: simple, and correct
    private final ArrayDeque<Message> queue = new ArrayDeque<>();
    private final int capacity;
    private Message retry;            // to redeliver next, ahead of the queue
    private int retryAttempt;
    private Delivery inFlight;        // handed out, waiting for ack (AT_LEAST_ONCE only)
    private boolean inHandler;        // the consumer thread is inside the handler right now
    private boolean closed;

    Lane(int capacity) { this.capacity = capacity; }

    // ------------------------------------------------------------------ producer side (used by OverflowPolicy)

    Admission tryAdd(Message m, Admission whenFull) {
        lock.lock();
        try {
            if (closed) return Admission.REJECTED;
            if (queue.size() >= capacity) return whenFull;
            append(m);
            return Admission.ACCEPTED;
        } finally { lock.unlock(); }
    }

    Admission addWaiting(Message m, Duration timeout) throws InterruptedException {
        lock.lock();
        try {
            long nanos = timeout.toNanos();
            while (!closed && queue.size() >= capacity) {
                if (nanos <= 0) return Admission.REJECTED;
                nanos = changed.awaitNanos(nanos);   // releases the lock while waiting
            }
            if (closed) return Admission.REJECTED;
            append(m);
            return Admission.ACCEPTED;
        } finally { lock.unlock(); }
    }

    Admission addEvictingOldest(Message m) {
        lock.lock();
        try {
            if (closed) return Admission.REJECTED;
            boolean evicted = queue.size() >= capacity;
            if (evicted) queue.pollFirst();
            append(m);
            return evicted ? Admission.EVICTED_OLDEST : Admission.ACCEPTED;
        } finally { lock.unlock(); }
    }

    private void append(Message m) {
        queue.addLast(m);
        changed.signalAll();
    }

    // ------------------------------------------------------------------ consumer side

    /** Waits until there is something to deliver and nothing unacked; returns null once the lane is closed. */
    Delivery takeNext(BiFunction<Message, Integer, Delivery> newDelivery) throws InterruptedException {
        lock.lock();
        try {
            while (!closed && (inFlight != null || (retry == null && queue.isEmpty()))) changed.await();
            if (closed) return null;
            Delivery d;
            if (retry != null) {
                d = newDelivery.apply(retry, retryAttempt);
                retry = null;
            } else {
                d = newDelivery.apply(queue.pollFirst(), 1);
            }
            if (d.isPending()) inFlight = d;
            inHandler = true;
            changed.signalAll();   // a slot was freed: wake blocked publishers
            return d;
        } finally { lock.unlock(); }
    }

    void handlerReturned() {
        lock.lock();
        try { inHandler = false; changed.signalAll(); } finally { lock.unlock(); }
    }

    /** Acked, or given up on (dead-lettered): the lane may deliver the next message. */
    void finish(Delivery d) {
        lock.lock();
        try {
            if (inFlight == d) inFlight = null;
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Nacked or timed out: deliver this same message again before anything newer. */
    void redeliver(Delivery d) {
        lock.lock();
        try {
            if (inFlight == d) {
                inFlight = null;
                retry = d.message();
                retryAttempt = d.attempt() + 1;
            }
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** The in-flight delivery if its ack deadline has passed (it is expired as a side effect), else null. */
    Delivery expireOverdue(Instant now) {
        lock.lock();
        try {
            return inFlight != null && inFlight.expireIfDue(now) ? inFlight : null;
        } finally { lock.unlock(); }
    }

    /** Messages not yet finished: queued + waiting for redelivery + waiting for ack. */
    int backlog() {
        lock.lock();
        try { return queue.size() + (retry != null ? 1 : 0) + (inFlight != null ? 1 : 0); } finally { lock.unlock(); }
    }

    /** Waits (real time) until everything is delivered and acked and the handler is idle. */
    boolean awaitIdle(long deadlineNanos) throws InterruptedException {
        lock.lock();
        try {
            while (backlog() > 0 || inHandler) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return false;
                changed.awaitNanos(left);
            }
            return true;
        } finally { lock.unlock(); }
    }

    void close() {
        lock.lock();
        try { closed = true; changed.signalAll(); } finally { lock.unlock(); }
    }
}
