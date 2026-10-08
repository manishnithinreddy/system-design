package pool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** A readable walk-through: a thread pool under load (with CallerRuns back-pressure), then a connection pool. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        threadPoolUnderLoad();
        System.out.println();
        connectionPoolStory();
    }

    static void threadPoolUnderLoad() throws Exception {
        System.out.println("--- Thread pool: core=2, max=4, queue=4, CALLER_RUNS ---");
        var pool = new SimpleThreadPool(2, 4, Duration.ofSeconds(1), 4, RejectionPolicy.CALLER_RUNS, "worker");
        var gate = new CountDownLatch(1);          // holds every pool task, so the steps below are exact
        String caller = Thread.currentThread().getName();
        for (int i = 1; i <= 9; i++) {
            int n = i;
            Runnable task = () -> {
                if (Thread.currentThread().getName().equals(caller)) {
                    System.out.println("  task " + n + ": pool saturated -> ran on the CALLER thread '" + caller
                            + "' (the producer is slowed down: back-pressure)");
                } else {
                    try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            };
            pool.execute(task);
            var st = pool.stats();     // threads and queued are exact here; "active" would race with thread start-up
            System.out.println("  after task " + n + ": threads=" + st.poolSize() + " queued=" + st.queued()
                    + " rejected=" + st.rejected());
        }
        gate.countDown();
        pool.shutdown();
        pool.awaitTermination(Duration.ofSeconds(5));
        System.out.println("  after shutdown: " + pool.stats());
    }

    static void connectionPoolStory() throws Exception {
        System.out.println("--- Connection pool: maxSize=2, maxLifetime=30 min, leakThreshold=2 s ---");
        var seq = new AtomicInteger();
        Connector<String> db = new Connector<>() {
            public String create() { String c = "conn-" + seq.incrementAndGet(); System.out.println("  [db] open " + c); return c; }
            public boolean isValid(String c) { return true; }
            public void close(String c) { System.out.println("  [db] close " + c); }
        };
        var clock = new ManualClock(Instant.parse("2026-10-08T09:00:00Z"));
        var cfg = ConnectionPool.Config.of(2)
                .withMaxLifetime(Duration.ofMinutes(30), Duration.ZERO)
                .withLeakThreshold(Duration.ofSeconds(2));
        var pool = new ConnectionPool<>(db, cfg, clock, leak -> {
            System.out.println("  WARN leak? connection #" + leak.connectionId() + " held for " + leak.heldFor().toSeconds()
                    + " s, borrowed at:");
            StackTraceElement[] st = leak.borrowedAt().getStackTrace();
            for (int i = 0; i < st.length; i++)
                if (st[i].getClassName().equals(Demo.class.getName())) System.out.println("      at " + st[i]);
        });

        try (var c = pool.borrow(Duration.ofSeconds(1))) { System.out.println("  request 1 uses " + c.get()); }
        try (var c = pool.borrow(Duration.ofSeconds(1))) { System.out.println("  request 2 uses " + c.get() + " (reused, no new TCP connection)"); }

        var forgotten = forgetfulRepository(pool);             // a bug: never closed
        var other = pool.borrow(Duration.ofSeconds(1));
        System.out.println("  " + pool.stats());
        try {
            pool.borrow(Duration.ofMillis(100));
        } catch (TimeoutException e) {
            System.out.println("  request 3 timed out after 100 ms: pool exhausted (in HikariCP: connectionTimeout)");
        }
        other.close();                                          // the well-behaved borrower returns its connection
        clock.advance(Duration.ofSeconds(3));
        pool.housekeep();                                       // HikariCP: leakDetectionThreshold
        forgotten.close();

        clock.advance(Duration.ofMinutes(31));
        System.out.println("  31 minutes later, housekeeping retires connections past maxLifetime:");
        pool.housekeep();
        System.out.println("  " + pool.stats());
        pool.close();
    }

    static PooledConnection<String> forgetfulRepository(ConnectionPool<String> pool) throws Exception {
        return pool.borrow(Duration.ofSeconds(1));
    }
}
