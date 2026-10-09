package pubsub;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One attempt to deliver one message to one subscription. The handler ends it with ack() or nack();
 * if neither happens before the ack deadline, the broker's sweep expires it and redelivers.
 * ack, nack and expiry can race (a late ack arriving just as the sweep runs): the compare-and-set
 * on {@code state} lets exactly one of them win, and the others become no-ops.
 */
public final class Delivery {
    private static final int PENDING = 0, ACKED = 1, NACKED = 2, EXPIRED = 3;

    private final Message message;
    private final int attempt;
    private final Instant ackDeadline;
    private final Subscription subscription;
    private final Lane lane;
    private final AtomicInteger state;

    Delivery(Message message, int attempt, Instant ackDeadline, Subscription subscription, Lane lane, boolean preAcked) {
        this.message = message;
        this.attempt = attempt;
        this.ackDeadline = ackDeadline;
        this.subscription = subscription;
        this.lane = lane;
        this.state = new AtomicInteger(preAcked ? ACKED : PENDING);   // AT_MOST_ONCE: done before the handler runs
    }

    public Message message() { return message; }
    /** 1 for the first delivery, 2 for the first redelivery, ... */
    public int attempt() { return attempt; }

    public void ack() {
        if (state.compareAndSet(PENDING, ACKED)) subscription.acked(this);
    }

    public void nack() {
        if (state.compareAndSet(PENDING, NACKED)) subscription.failed(this);
    }

    boolean isPending() { return state.get() == PENDING; }
    boolean expireIfDue(Instant now) { return !now.isBefore(ackDeadline) && state.compareAndSet(PENDING, EXPIRED); }
    Lane lane() { return lane; }
}
