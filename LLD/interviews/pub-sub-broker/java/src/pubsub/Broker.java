package pubsub;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * An in-process message broker. Publishers call publish(topic, key, payload); the broker finds every
 * subscription whose pattern matches (TopicTrie) and offers the message to each one (fan-out).
 * Optionally a topic also keeps a retained, replayable log (TopicLog): the Kafka model next to the queue model.
 */
public final class Broker implements AutoCloseable {

    /** What happened to one publish, across all matching subscriptions. */
    public record PublishResult(String messageId, int accepted, int dropped, int rejected, long logOffset) {
        public boolean fullyAccepted() { return dropped == 0 && rejected == 0; }
    }

    private final Clock clock;
    private final TopicTrie<Subscription> routes = new TopicTrie<>();
    private final Set<Subscription> all = ConcurrentHashMap.newKeySet();
    private final Map<String, Subscription> byName = new ConcurrentHashMap<>();
    private final Map<String, TopicLog> logs = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean accepting = true;
    private ScheduledExecutorService sweeper;

    public Broker(Clock clock) { this.clock = clock; }

    // ------------------------------------------------------------------ publish

    public PublishResult publish(String topic, String key, String payload) {
        return publish(topic, key, payload, Map.of());
    }

    public PublishResult publish(String topic, String key, String payload, Map<String, String> headers) {
        if (topic.contains("*") || topic.contains("#")) throw new IllegalArgumentException("wildcards are for subscribing: " + topic);
        if (!accepting) throw new IllegalStateException("broker is shutting down");
        Message m = new Message("m-" + ids.incrementAndGet(), topic, key, payload, clock.instant(), headers);
        return route(m);
    }

    /** Internal publish of a failed message; allowed during shutdown so draining can still dead-letter. */
    void deadLetter(Message m) { route(m); }

    private PublishResult route(Message m) {
        TopicLog log = logs.get(m.topic());
        long offset = log == null ? -1 : log.append(m);
        int accepted = 0, dropped = 0, rejected = 0;
        for (Subscription s : routes.match(m.topic())) {
            try {
                switch (s.offer(m)) {
                    case ACCEPTED -> accepted++;
                    case EVICTED_OLDEST -> { accepted++; dropped++; }
                    case DROPPED -> dropped++;
                    case REJECTED -> rejected++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                rejected++;
            }
        }
        return new PublishResult(m.id(), accepted, dropped, rejected, offset);
    }

    // ------------------------------------------------------------------ subscribe

    /** L4 shortcut: a fresh subscription with default settings and an auto-acking callback. */
    public Subscription subscribe(String pattern, Consumer<Message> callback) {
        return subscribe(pattern, "sub-" + ids.incrementAndGet(), SubscriptionConfig.defaults(), MessageHandler.autoAck(callback));
    }

    public Subscription subscribe(String pattern, String name, SubscriptionConfig config, MessageHandler handler) {
        TopicTrie.words(pattern);   // validate before starting threads
        Subscription s = new Subscription(name, pattern, config, handler, this, clock);
        if (byName.putIfAbsent(name, s) != null) {
            s.close();
            throw new IllegalArgumentException("subscription exists: " + name);
        }
        all.add(s);
        routes.add(pattern, s);
        return s;
    }

    void unsubscribe(Subscription s) {
        routes.remove(s.pattern(), s);
        all.remove(s);
        byName.remove(s.name(), s);
    }

    // ------------------------------------------------------------------ retained log (L6)

    /** From now on, also append this topic's messages to a replayable log with the given retention. */
    public TopicLog retain(String topic, int maxRecords, Duration maxAge) {
        return logs.computeIfAbsent(topic, t -> new TopicLog(maxRecords, maxAge, clock));
    }

    // ------------------------------------------------------------------ housekeeping and shutdown

    /** Redeliver messages whose ack deadline passed, and apply log retention. Tests call it directly. */
    public void sweep() {
        var now = clock.instant();
        for (Subscription s : all) s.sweep(now);
        for (TopicLog log : logs.values()) log.enforceRetention();
    }

    /** Production mode: run sweep() in the background every {@code period}. */
    public synchronized void startSweeper(Duration period) {
        if (sweeper != null) return;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("broker-sweeper").unstarted(r));
        sweeper.scheduleAtFixedRate(this::sweep, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Graceful shutdown: refuse new publishes, let subscriptions finish what they hold (up to {@code grace}),
     * then stop everything. Returns how many messages were still unfinished (lost) when time ran out.
     */
    public int shutdown(Duration grace) throws InterruptedException {
        accepting = false;
        long deadline = System.nanoTime() + grace.toNanos();
        List<Subscription> subs = List.copyOf(all);
        int left = 0;
        for (Subscription s : subs) {
            s.awaitIdle(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            left += s.stats().backlog();
            s.close();
        }
        synchronized (this) { if (sweeper != null) sweeper.shutdownNow(); }
        return left;
    }

    @Override public void close() {
        try {
            shutdown(Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
