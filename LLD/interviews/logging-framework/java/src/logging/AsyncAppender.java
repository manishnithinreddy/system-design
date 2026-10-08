package logging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a slow appender (file, network) so the CALLER only pays for a queue insert.
 * One background thread drains the bounded queue in batches and calls the real appender.
 * What to do when the queue is full is an explicit policy (back-pressure).
 */
public final class AsyncAppender implements Appender {
    public enum OverflowPolicy {
        /** Caller waits for space: nothing lost, but a slow disk slows the application. */
        BLOCK,
        /** Caller never waits: a full queue drops the new event (counted). */
        DROP,
        /** Like Logback's AsyncAppender default: when 80% full, drop TRACE/DEBUG/INFO; WARN/ERROR still block. */
        DISCARD_BELOW_WARN_WHEN_80_PERCENT_FULL
    }

    private static final int BATCH = 64;

    private final Appender delegate;
    private final BlockingQueue<LogEvent> queue;
    private final int capacity;
    private final OverflowPolicy policy;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean closed;

    public AsyncAppender(Appender delegate, int capacity, OverflowPolicy policy) {
        this.delegate = delegate;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.policy = policy;
        // Daemon: a forgotten close() must not keep the JVM alive. Shutdown hook calls close() to flush.
        this.worker = Thread.ofPlatform().name("log-async").daemon(true).start(this::drainLoop);
    }

    @Override
    public void append(LogEvent event) {
        if (closed) { dropped.incrementAndGet(); return; }
        switch (policy) {
            case BLOCK -> put(event);
            case DROP -> { if (!queue.offer(event)) dropped.incrementAndGet(); }
            case DISCARD_BELOW_WARN_WHEN_80_PERCENT_FULL -> {
                boolean nearlyFull = queue.size() >= capacity * 8 / 10;
                if (nearlyFull && !event.level().isAtLeast(Level.WARN)) dropped.incrementAndGet();
                else put(event);
            }
        }
    }

    private void put(LogEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // keep the caller's interrupt; lose this one event
            dropped.incrementAndGet();
        }
    }

    /** Single consumer: events reach the delegate in exactly the order they were queued. */
    private void drainLoop() {
        List<LogEvent> batch = new ArrayList<>(BATCH);
        while (true) {
            LogEvent first;
            try {
                first = queue.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                first = null;
            }
            if (first == null) {
                if (closed && queue.isEmpty()) break;   // closed AND nothing left: done
                continue;
            }
            batch.add(first);
            queue.drainTo(batch, BATCH - 1);              // grab whatever else is waiting, one lock round-trip
            for (LogEvent e : batch) {
                try {
                    delegate.append(e);
                } catch (RuntimeException ex) {          // a broken appender must not kill the worker
                    System.err.println("[logging] appender failed: " + ex);
                }
            }
            batch.clear();
        }
    }

    public long dropped() { return dropped.get(); }

    public int pending() { return queue.size(); }

    /**
     * Stop accepting, let the worker drain everything already queued, then close the delegate.
     * Known gap (kept simple on purpose): an append() racing with close() can pass the `closed` check
     * after the worker has exited, and that one event is lost uncounted. Real libraries close under a lock.
     */
    @Override
    public void close() {
        closed = true;
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delegate.close();
    }
}
