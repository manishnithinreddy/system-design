package kvstore;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * In-memory key-value store with nested transactions, TTLs and an optional write-ahead log.
 *
 * Transactions = a STACK of undo logs. Each BEGIN pushes an empty map; the first time a key is
 * changed inside that layer we record its ORIGINAL entry (or "absent").
 *   ROLLBACK: put every recorded original back, pop the layer.
 *   COMMIT (nested): pop the layer and merge its undo log into the parent, keeping the parent's
 *                    own (older) original when both touched the same key.
 *   COMMIT (outermost): pop the layer; its changes are now permanent and are written to the log.
 *
 * NOT thread-safe by design: like Redis, one thread executes all commands (single writer).
 */
public final class KeyValueStore {
    private final Map<String, Entry> data = new HashMap<>();
    private final Map<String, Integer> valueCounts = new HashMap<>();       // value -> how many keys hold it
    private final PriorityQueue<Expiry> expiries = new PriorityQueue<>();   // for active expiry
    private final Deque<Map<String, Optional<Entry>>> undoStack = new ArrayDeque<>();
    private final Deque<List<String>> pendingLog = new ArrayDeque<>();      // log records per open layer
    private final Clock clock;
    private final WriteAheadLog log;                                        // null = memory only

    private record Expiry(long at, String key) implements Comparable<Expiry> {
        public int compareTo(Expiry o) { return Long.compare(at, o.at); }
    }

    public KeyValueStore(Clock clock) {
        this(clock, null);
    }

    public KeyValueStore(Clock clock, WriteAheadLog log) {
        this.clock = clock;
        this.log = log;
        if (log != null) log.replay(this::rawPut, this::rawRemove);    // recover committed state
    }

    // ---------------- commands ----------------

    public Optional<String> get(String key) {
        purgeExpired();
        Entry e = data.get(key);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    public void set(String key, String value) {
        purgeExpired();
        write(key, new Entry(value, Entry.NEVER));                       // like Redis, SET clears any TTL
    }

    public boolean delete(String key) {
        purgeExpired();
        if (!data.containsKey(key)) return false;
        write(key, null);
        return true;
    }

    /** How many keys currently hold exactly this value. O(1). */
    public int count(String value) {
        purgeExpired();
        return valueCounts.getOrDefault(value, 0);
    }

    public boolean expire(String key, long seconds) {
        purgeExpired();
        Entry e = data.get(key);
        if (e == null) return false;
        write(key, new Entry(e.value(), clock.millis() + seconds * 1000));
        return true;
    }

    /** -2 = no such key, -1 = no expiry, otherwise seconds left (rounded up). Same as Redis TTL. */
    public long ttl(String key) {
        purgeExpired();
        Entry e = data.get(key);
        if (e == null) return -2;
        if (e.expiresAtMillis() == Entry.NEVER) return -1;
        return (e.expiresAtMillis() - clock.millis() + 999) / 1000;
    }

    public void begin() {
        undoStack.push(new LinkedHashMap<>());
        pendingLog.push(new ArrayList<>());
    }

    public void rollback() {
        if (undoStack.isEmpty()) throw new NoTransactionException();
        Map<String, Optional<Entry>> layer = undoStack.pop();
        pendingLog.pop();                                                // these changes never reach the log
        layer.forEach((key, original) -> {
            if (original.isPresent()) rawPut(key, original.get()); else rawRemove(key);
        });
    }

    public void commit() {
        if (undoStack.isEmpty()) throw new NoTransactionException();
        Map<String, Optional<Entry>> layer = undoStack.pop();
        List<String> records = pendingLog.pop();
        if (!undoStack.isEmpty()) {                                      // nested: hand over to the parent
            Map<String, Optional<Entry>> parent = undoStack.peek();
            layer.forEach(parent::putIfAbsent);                          // parent's older original wins
            pendingLog.peek().addAll(records);
        } else if (log != null && !records.isEmpty()) {
            log.append(records, true);                                   // outermost: now durable
        }
    }

    public boolean inTransaction() {
        return !undoStack.isEmpty();
    }

    /** Rewrite the log as one record per live key (shrinks a log full of overwritten history). */
    public void compact() {
        if (log == null) return;
        if (inTransaction()) throw new IllegalStateException("cannot compact during a transaction");
        purgeExpired();
        log.rewrite(data);
    }

    public int size() {
        purgeExpired();
        return data.size();
    }

    // ---------------- internals ----------------

    /** Every change goes through here: remember the original for rollback, apply, log. */
    private void write(String key, Entry newEntry) {
        if (!undoStack.isEmpty()) {
            undoStack.peek().putIfAbsent(key, Optional.ofNullable(data.get(key)));
        }
        if (newEntry == null) rawRemove(key); else rawPut(key, newEntry);
        String record = newEntry == null ? WriteAheadLog.delete(key) : WriteAheadLog.set(key, newEntry);
        if (!pendingLog.isEmpty()) pendingLog.peek().add(record);
        else if (log != null) log.append(List.of(record), false);
    }

    private void rawPut(String key, Entry e) {
        Entry old = data.put(key, e);
        if (old != null) decrement(old.value());
        valueCounts.merge(e.value(), 1, Integer::sum);
        if (e.expiresAtMillis() != Entry.NEVER) expiries.add(new Expiry(e.expiresAtMillis(), key));
    }

    private void rawRemove(String key) {
        Entry old = data.remove(key);
        if (old != null) decrement(old.value());
    }

    private void decrement(String value) {
        valueCounts.computeIfPresent(value, (v, n) -> n == 1 ? null : n - 1);
    }

    /**
     * Active expiry: drop every key whose time has come, so COUNT and size stay exact.
     * The heap may contain stale entries (key overwritten or TTL changed): re-check before removing.
     * Expired keys are removed without undo records: restoring them on rollback would only
     * restore something that is already expired.
     */
    private void purgeExpired() {
        long now = clock.millis();
        while (!expiries.isEmpty() && expiries.peek().at() <= now) {
            Expiry x = expiries.poll();
            Entry current = data.get(x.key());
            if (current != null && current.expiresAtMillis() == x.at()) rawRemove(x.key());
        }
    }
}
