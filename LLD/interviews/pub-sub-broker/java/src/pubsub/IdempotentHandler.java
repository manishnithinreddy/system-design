package pubsub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Decorator that turns at-least-once delivery into "effectively once" processing: it remembers the ids of
 * messages already processed and skips repeats. The memory is bounded (oldest ids forgotten first), so a
 * duplicate arriving after {@code capacity} newer messages would slip through: size it for your redelivery window.
 * In production the "seen" record must be written in the SAME transaction as the side effect.
 */
public final class IdempotentHandler implements MessageHandler {
    private final Consumer<Message> process;
    private final Map<String, Boolean> seen;
    private long duplicates;

    public IdempotentHandler(Consumer<Message> process, int capacity) {
        this.process = process;
        this.seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > capacity; }
        };
    }

    @Override public void onMessage(Delivery d) {
        processOnce(d.message());
        d.ack();
    }

    /** Runs the side effect unless this message id was already processed. Returns false for a duplicate. */
    public synchronized boolean processOnce(Message m) {
        if (seen.containsKey(m.id())) {
            duplicates++;
            return false;
        }
        process.accept(m);
        seen.put(m.id(), Boolean.TRUE);
        return true;
    }

    public synchronized long duplicates() { return duplicates; }
}
