package pool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Plain-Java tests (no JUnit) so the code runs with just javac + java.
 * Determinism: tasks block on a CountDownLatch ("gate") so "all workers busy, queue full" is a state we
 * build on purpose, not a race. Time for the connection pool comes from a ManualClock.
 */
public final class PoolTests {
    private static int passed = 0;
    static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    static final Duration LONG = Duration.ofSeconds(5);

    public static void main(String[] args) throws Exception {
        // thread pool
        runsAllTasksAndCompletesFutures();
        growsBeyondCoreOnlyWhenQueueIsFullThenAborts();
        unboundedStyleQueueNeverUsesMaxThreads();
        callerRunsExecutesOnCallerThread();
        discardCancelsNewTask();
        discardOldestDropsQueueHead();
        workerSurvivesTaskExceptions();
        gracefulShutdownFinishesQueuedTasks();
        shutdownNowInterruptsAndReturnsPending();
        keepAliveShrinksBackToCore();
        // connection pool
        borrowAndReleaseReusesSameConnection();
        maxSizeRespectedAndBorrowTimesOut();
        waitersAreServedInFifoOrder();
        brokenConnectionReplacedOnBorrow();
        maxLifetimeRetiresConnections();
        leakDetectionReportsBorrowerOnce();
        doubleCloseIsHarmless();
        closeClosesIdleAndRejectsBorrowers();
        concurrentStressNeverExceedsMaxAndLosesNothing();
        System.out.println("All " + passed + " tests passed.");
    }

    // =============================================================== thread pool

    static void runsAllTasksAndCompletesFutures() throws Exception {
        var pool = SimpleThreadPool.fixed(3, 100);
        var count = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            futures.add(pool.submit(() -> { count.incrementAndGet(); return n * n; }));
        }
        assertEquals(49 * 49, futures.get(49).get(5, TimeUnit.SECONDS), "Future carries the result");
        pool.shutdown();
        assertTrue(pool.awaitTermination(LONG), "terminates after shutdown");
        assertEquals(50, count.get(), "every task ran exactly once");
        assertEquals(3, pool.stats().largest(), "fixed pool: exactly 3 threads were ever created");
        pass("runsAllTasksAndCompletesFutures");
    }

    static void growsBeyondCoreOnlyWhenQueueIsFullThenAborts() throws Exception {
        var pool = new SimpleThreadPool(1, 3, Duration.ofSeconds(30), 2, RejectionPolicy.ABORT, "grow");
        var gate = new CountDownLatch(1);
        var ran = new AtomicInteger();
        Runnable blocked = () -> { await(gate); ran.incrementAndGet(); };
        try {
            pool.execute(blocked);
            assertEquals(1, pool.poolSize(), "task 1: below core -> new thread");
            pool.execute(blocked);
            pool.execute(blocked);
            assertEquals(1, pool.poolSize(), "tasks 2-3: QUEUED, no new thread although max is 3");
            assertEquals(2, pool.stats().queued(), "queue now full");
            pool.execute(blocked);
            assertEquals(2, pool.poolSize(), "task 4: queue full -> extra thread");
            pool.execute(blocked);
            assertEquals(3, pool.poolSize(), "task 5: another extra thread, now at max");
            assertThrows(RejectedExecutionException.class, () -> pool.execute(blocked), "task 6: ABORT throws");
            assertEquals(1L, pool.stats().rejected(), "rejection counted");
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(LONG), "terminates");
            assertEquals(5, ran.get(), "the 5 accepted tasks all ran");
        } finally { pool.shutdownNow(); }
        pass("growsBeyondCoreOnlyWhenQueueIsFullThenAborts");
    }

    static void unboundedStyleQueueNeverUsesMaxThreads() throws Exception {
        // A "big enough" queue behaves like LinkedBlockingQueue: max=8 is dead configuration.
        var pool = new SimpleThreadPool(2, 8, Duration.ofSeconds(30), 10_000, RejectionPolicy.ABORT, "big-queue");
        var running = new AtomicInteger();
        var maxSeen = new AtomicInteger();
        var gate = new CountDownLatch(1);
        try {
            for (int i = 0; i < 200; i++) {
                pool.execute(() -> {
                    int now = running.incrementAndGet();
                    maxSeen.accumulateAndGet(now, Math::max);
                    await(gate);
                    running.decrementAndGet();
                });
            }
            assertEquals(2, pool.stats().largest(), "200 tasks, max=8, but only core=2 threads ever exist");
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(LONG), "terminates");
            assertTrue(maxSeen.get() <= 2, "never more than 2 tasks at once (saw " + maxSeen.get() + ")");
        } finally { pool.shutdownNow(); }
        pass("unboundedStyleQueueNeverUsesMaxThreads");
    }

    /** core=1, max=1, queue=1, worker blocked on the gate, queue holds one task: the NEXT task is rejected. */
    record Saturated(SimpleThreadPool pool, CountDownLatch gate, List<String> log) {}

    static Saturated saturated(RejectionPolicy policy) {
        var pool = new SimpleThreadPool(1, 1, Duration.ZERO, 1, policy, "sat-" + policy);
        var gate = new CountDownLatch(1);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        pool.execute(() -> { await(gate); log.add("running"); });
        pool.execute(() -> log.add("queued"));
        return new Saturated(pool, gate, log);
    }

    static void callerRunsExecutesOnCallerThread() throws Exception {
        var s = saturated(RejectionPolicy.CALLER_RUNS);
        var ranOn = new AtomicReference<String>();
        s.pool.execute(() -> ranOn.set(Thread.currentThread().getName()));    // returns only after it ran
        assertEquals(Thread.currentThread().getName(), ranOn.get(), "ran synchronously on the submitting thread");
        assertEquals(1L, s.pool.stats().rejected(), "counted as a rejection (it's the back-pressure signal)");
        s.gate.countDown();
        s.pool.shutdown();
        assertTrue(s.pool.awaitTermination(LONG), "terminates");
        assertEquals(List.of("running", "queued"), s.log, "pool's own tasks unaffected");
        pass("callerRunsExecutesOnCallerThread");
    }

    static void discardCancelsNewTask() throws Exception {
        var s = saturated(RejectionPolicy.DISCARD);
        Future<?> dropped = s.pool.submit(() -> s.log.add("new"));
        assertTrue(dropped.isCancelled(), "dropped task's Future is cancelled, so get() won't hang forever");
        assertThrows(CancellationException.class, () -> dropped.get(1, TimeUnit.SECONDS), "get() fails fast");
        s.gate.countDown();
        s.pool.shutdown();
        assertTrue(s.pool.awaitTermination(LONG), "terminates");
        assertEquals(List.of("running", "queued"), s.log, "new task never ran");
        pass("discardCancelsNewTask");
    }

    static void discardOldestDropsQueueHead() throws Exception {
        var s = saturated(RejectionPolicy.DISCARD_OLDEST);
        s.pool.execute(() -> s.log.add("new"));
        s.gate.countDown();
        s.pool.shutdown();
        assertTrue(s.pool.awaitTermination(LONG), "terminates");
        assertEquals(List.of("running", "new"), s.log, "oldest queued task dropped, new one ran");
        pass("discardOldestDropsQueueHead");
    }

    static void workerSurvivesTaskExceptions() throws Exception {
        var pool = SimpleThreadPool.fixed(1, 10);
        List<Throwable> reported = Collections.synchronizedList(new ArrayList<>());
        pool.setTaskErrorHandler(reported::add);
        try {
            Future<Object> f = pool.submit(() -> { throw new IllegalStateException("boom"); });
            try {
                f.get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected ExecutionException");
            } catch (ExecutionException e) {
                assertEquals("boom", e.getCause().getMessage(), "submit(): exception stored in the Future");
            }
            String first = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
            var done = new CountDownLatch(1);
            pool.execute(() -> { done.countDown(); throw new IllegalArgumentException("raw"); });
            await(done);
            String after = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
            assertEquals(first, after, "same worker thread still alive after both failures");
            assertEquals(1, reported.size(), "execute(): raw exception reaches the error handler");
            assertEquals(1L, pool.stats().failed(), "failed counter (FutureTask failures live in the Future)");
        } finally { pool.shutdownNow(); }
        pass("workerSurvivesTaskExceptions");
    }

    static void gracefulShutdownFinishesQueuedTasks() throws Exception {
        var pool = SimpleThreadPool.fixed(1, 10);
        var gate = new CountDownLatch(1);
        var ran = new AtomicInteger();
        pool.execute(() -> { await(gate); ran.incrementAndGet(); });
        for (int i = 0; i < 3; i++) pool.execute(ran::incrementAndGet);
        pool.shutdown();
        assertThrows(RejectedExecutionException.class, () -> pool.execute(ran::incrementAndGet), "no new work after shutdown");
        assertTrue(!pool.awaitTermination(Duration.ofMillis(20)), "not terminated while a task still runs");
        gate.countDown();
        assertTrue(pool.awaitTermination(LONG), "terminates once the queue is drained");
        assertEquals(4, ran.get(), "running + 3 queued tasks all completed");
        pass("gracefulShutdownFinishesQueuedTasks");
    }

    static void shutdownNowInterruptsAndReturnsPending() throws Exception {
        var pool = SimpleThreadPool.fixed(1, 10);
        var started = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        pool.execute(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.set(true); }
        });
        for (int i = 0; i < 3; i++) pool.execute(() -> { });
        await(started);
        List<Runnable> pending = pool.shutdownNow();
        assertEquals(3, pending.size(), "queued tasks handed back, never run");
        assertTrue(pool.awaitTermination(LONG), "terminates");
        assertTrue(interrupted.get(), "running task was interrupted");
        pass("shutdownNowInterruptsAndReturnsPending");
    }

    static void keepAliveShrinksBackToCore() throws Exception {
        var pool = new SimpleThreadPool(1, 3, Duration.ofMillis(30), 1, RejectionPolicy.ABORT, "shrink");
        var gate = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) pool.execute(() -> await(gate));   // 1 core + 1 queued + 2 extra
            assertEquals(3, pool.poolSize(), "grew to max");
            gate.countDown();
            waitUntil(() -> pool.poolSize() == 1, "extra threads exit after 30 ms idle");
            assertEquals(1, pool.poolSize(), "back to core, and core threads never time out");
        } finally { pool.shutdownNow(); }
        pass("keepAliveShrinksBackToCore");
    }

    // =============================================================== connection pool

    /** A fake "database connection": an id, a broken flag, a closed flag. */
    static final class FakeConn {
        final int id;
        volatile boolean broken, closed;
        FakeConn(int id) { this.id = id; }
    }

    /** Counts live connections and the maximum ever alive at the same time. */
    static final class FakeConnector implements Connector<FakeConn> {
        final AtomicInteger seq = new AtomicInteger(), alive = new AtomicInteger(), maxAlive = new AtomicInteger();
        final List<FakeConn> all = Collections.synchronizedList(new ArrayList<>());
        @Override public FakeConn create() {
            maxAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
            var c = new FakeConn(seq.incrementAndGet());
            all.add(c);
            return c;
        }
        @Override public boolean isValid(FakeConn c) { return !c.broken && !c.closed; }
        @Override public void close(FakeConn c) { if (!c.closed) { c.closed = true; alive.decrementAndGet(); } }
    }

    record CP(ConnectionPool<FakeConn> pool, FakeConnector db, ManualClock clock, List<ConnectionPool.LeakReport> leaks) {}

    static CP connPool(ConnectionPool.Config cfg) {
        var db = new FakeConnector();
        var clock = new ManualClock(T0);
        List<ConnectionPool.LeakReport> leaks = Collections.synchronizedList(new ArrayList<>());
        return new CP(new ConnectionPool<>(db, cfg, clock, leaks::add), db, clock, leaks);
    }

    static void borrowAndReleaseReusesSameConnection() throws Exception {
        var p = connPool(ConnectionPool.Config.of(5));
        int first;
        try (var c = p.pool.borrow(LONG)) { first = c.get().id; }
        try (var c = p.pool.borrow(LONG)) { assertEquals(first, c.get().id, "same physical connection reused"); }
        assertEquals(1, p.db.seq.get(), "created only once");
        var st = p.pool.stats();
        assertEquals(1, st.idle(), "back to idle");
        assertEquals(0, st.inUse(), "nothing borrowed");
        pass("borrowAndReleaseReusesSameConnection");
    }

    static void maxSizeRespectedAndBorrowTimesOut() throws Exception {
        var p = connPool(ConnectionPool.Config.of(2));
        var a = p.pool.borrow(LONG);
        var b = p.pool.borrow(LONG);
        long t0 = System.nanoTime();
        assertThrows(TimeoutException.class, () -> p.pool.borrow(Duration.ofMillis(50)), "third borrow waits, then times out");
        assertTrue(System.nanoTime() - t0 >= TimeUnit.MILLISECONDS.toNanos(45), "actually waited ~the timeout");
        assertEquals(2, p.db.seq.get(), "never created a third connection");
        assertEquals(1L, p.pool.stats().timeouts(), "timeout counted");
        a.close();
        try (var c = p.pool.borrow(Duration.ofMillis(50))) { assertEquals(a.id(), c.id(), "freed one is reused"); }
        b.close();
        pass("maxSizeRespectedAndBorrowTimesOut");
    }

    static void waitersAreServedInFifoOrder() throws Exception {
        var p = connPool(ConnectionPool.Config.of(1));
        var held = p.pool.borrow(LONG);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (String name : List.of("A", "B", "C", "D")) {
            int waitingBefore = threads.size();
            Thread t = Thread.ofPlatform().name("waiter-" + name).start(() -> {
                try (var c = p.pool.borrow(LONG)) { order.add(name); }
                catch (Exception e) { order.add(name + ":" + e); }
            });
            threads.add(t);
            waitUntil(() -> p.pool.stats().waiting() == waitingBefore + 1, name + " is in line");
        }
        held.close();                                    // hand-off chain: A -> B -> C -> D
        for (Thread t : threads) t.join(5_000);
        assertEquals(List.of("A", "B", "C", "D"), order, "served in arrival order");
        pass("waitersAreServedInFifoOrder");
    }

    static void brokenConnectionReplacedOnBorrow() throws Exception {
        var p = connPool(ConnectionPool.Config.of(3));
        FakeConn first;
        try (var c = p.pool.borrow(LONG)) { first = c.get(); }
        first.broken = true;                             // e.g. the DB restarted while it sat idle
        try (var c = p.pool.borrow(LONG)) {
            assertTrue(c.get() != first, "got a different, healthy connection");
            assertTrue(!c.get().broken, "healthy");
        }
        assertTrue(first.closed, "broken one was closed");
        assertEquals(1L, p.pool.stats().brokenReplaced(), "counted");
        assertEquals(1, p.pool.stats().total(), "slot reused: still 1 connection total");
        pass("brokenConnectionReplacedOnBorrow");
    }

    static void maxLifetimeRetiresConnections() throws Exception {
        var p = connPool(ConnectionPool.Config.of(3).withMaxLifetime(Duration.ofMinutes(30), Duration.ZERO));
        FakeConn idleOne, busyOne;
        var holder = p.pool.borrow(LONG);
        busyOne = holder.get();
        try (var c = p.pool.borrow(LONG)) { idleOne = c.get(); }
        p.clock.advance(Duration.ofMinutes(31));
        p.pool.housekeep();
        assertTrue(idleOne.closed, "idle connection past maxLifetime closed by housekeeping");
        assertTrue(!busyOne.closed, "in-use connection is NEVER closed under its user");
        holder.close();
        assertTrue(busyOne.closed, "...it is retired when returned");
        try (var c = p.pool.borrow(LONG)) { assertEquals(3, c.get().id, "a brand-new connection"); }
        assertEquals(2L, p.pool.stats().retired(), "two retirements");
        pass("maxLifetimeRetiresConnections");
    }

    static void leakDetectionReportsBorrowerOnce() throws Exception {
        var p = connPool(ConnectionPool.Config.of(2).withLeakThreshold(Duration.ofSeconds(2)));
        var leaked = p.pool.borrow(LONG);                // "forgot" to close
        p.clock.advance(Duration.ofSeconds(1));
        p.pool.housekeep();
        assertEquals(0, p.leaks.size(), "1 s < threshold: not a leak yet");
        p.clock.advance(Duration.ofSeconds(2));
        p.pool.housekeep();
        p.pool.housekeep();
        assertEquals(1, p.leaks.size(), "reported once, not on every housekeeping run");
        var r = p.leaks.get(0);
        assertEquals(Duration.ofSeconds(3), r.heldFor(), "held for 3 s");
        boolean pointsAtBorrower = false;
        for (StackTraceElement f : r.borrowedAt().getStackTrace())
            if (f.getMethodName().equals("leakDetectionReportsBorrowerOnce")) pointsAtBorrower = true;
        assertTrue(pointsAtBorrower, "stack trace shows WHO borrowed it");
        leaked.close();
        pass("leakDetectionReportsBorrowerOnce");
    }

    static void doubleCloseIsHarmless() throws Exception {
        var p = connPool(ConnectionPool.Config.of(2));
        var c = p.pool.borrow(LONG);
        c.close();
        c.close();                                       // e.g. explicit close() inside try-with-resources
        assertEquals(1, p.pool.stats().idle(), "returned once, not twice");
        assertThrows(IllegalStateException.class, c::get, "can't use a returned connection");
        var x = p.pool.borrow(LONG);
        var y = p.pool.borrow(LONG);
        assertTrue(x.get() != y.get(), "two borrowers never share one connection");
        x.close(); y.close();
        pass("doubleCloseIsHarmless");
    }

    static void closeClosesIdleAndRejectsBorrowers() throws Exception {
        var p = connPool(ConnectionPool.Config.of(1));
        var held = p.pool.borrow(LONG);
        var failure = new AtomicReference<Throwable>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try { p.pool.borrow(LONG).close(); } catch (Exception e) { failure.set(e); }
        });
        waitUntil(() -> p.pool.stats().waiting() == 1, "waiter in line");
        p.pool.close();
        waiter.join(5_000);
        assertTrue(failure.get() instanceof IllegalStateException, "waiter woken with 'pool closed', not left hanging");
        assertTrue(!held.get().closed, "in-use connection left alone...");
        held.close();
        assertTrue(p.db.all.get(0).closed, "...and closed when returned");
        assertEquals(0, p.db.alive.get(), "nothing left open");
        pass("closeClosesIdleAndRejectsBorrowers");
    }

    static void concurrentStressNeverExceedsMaxAndLosesNothing() throws Exception {
        var p = connPool(ConnectionPool.Config.of(4));
        int threads = 16, rounds = 500;
        var inside = new AtomicInteger();
        var maxInside = new AtomicInteger();
        var errors = new AtomicInteger();
        var start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            ts.add(Thread.ofPlatform().start(() -> {
                await(start);
                for (int i = 0; i < rounds; i++) {
                    try (var c = p.pool.borrow(LONG)) {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        if ((i + seed) % 97 == 0) c.get().broken = true;     // some die while in use
                        if (i % 10 == 0) Thread.yield();
                        inside.decrementAndGet();
                    } catch (Exception e) { errors.incrementAndGet(); }
                }
            }));
        }
        start.countDown();
        for (Thread t : ts) t.join(30_000);
        assertEquals(0, errors.get(), "no timeouts or failures");
        assertTrue(maxInside.get() <= 4, "never more than 4 borrowers at once (saw " + maxInside.get() + ")");
        assertTrue(p.db.maxAlive.get() <= 4, "never more than 4 physical connections alive (saw " + p.db.maxAlive.get() + ")");
        var st = p.pool.stats();
        assertEquals(0, st.inUse(), "all returned");
        assertEquals(st.total(), st.idle(), "every live connection is idle again: none lost");
        assertEquals(p.db.alive.get(), st.total(), "pool's count matches the database's");
        List<PooledConnection<FakeConn>> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) all.add(p.pool.borrow(Duration.ofMillis(200)));   // full capacity still usable
        all.forEach(PooledConnection::close);
        pass("concurrentStressNeverExceedsMaxAndLosesNothing");
    }

    // ---------------- helpers ----------------

    static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Spin (yielding) until a condition holds. Used to wait for a STATE, never "sleep 100 ms and hope". */
    static void waitUntil(BooleanSupplier cond, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting: " + what);
            Thread.yield();
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
