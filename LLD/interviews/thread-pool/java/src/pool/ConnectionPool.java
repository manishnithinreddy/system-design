package pool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A small HikariCP-like pool of expensive connections.
 *
 *  - at most maxSize connections exist (idle + in use + being created)
 *  - borrow(timeout): reuse an idle one, else create one if under max, else WAIT in a FIFO line; give up after timeout
 *  - fair: a returned connection is HANDED DIRECTLY to the longest waiter; newcomers can't jump the line
 *  - validated on borrow (broken ones are closed and replaced), retired after maxLifetime (minus jitter)
 *  - leak detection: housekeep() reports connections held longer than leakThreshold, with the borrower's stack
 *
 * Slow work (create, isValid, close) always happens OUTSIDE the lock, so one slow database call
 * never freezes every other borrower.
 */
public final class ConnectionPool<C> implements AutoCloseable {

    public enum ConnState { IDLE, IN_USE, VALIDATING, CLOSED }

    /** Duration.ZERO disables maxLifetime / leakThreshold (HikariCP uses 0 the same way). */
    public record Config(int maxSize, Duration maxLifetime, Duration lifetimeJitter, Duration leakThreshold) {
        public static Config of(int maxSize) { return new Config(maxSize, Duration.ZERO, Duration.ZERO, Duration.ZERO); }
        public Config withMaxLifetime(Duration life, Duration jitter) { return new Config(maxSize, life, jitter, leakThreshold); }
        public Config withLeakThreshold(Duration t) { return new Config(maxSize, maxLifetime, lifetimeJitter, t); }
    }

    public record LeakReport(int connectionId, Duration heldFor, Throwable borrowedAt) {}

    public record Stats(int total, int idle, int inUse, int waiting, long created, long closed,
                        long brokenReplaced, long retired, long timeouts, long leaks) {
        @Override public String toString() {
            return "total=" + total + " idle=" + idle + " inUse=" + inUse + " waiting=" + waiting + " created=" + created
                    + " closed=" + closed + " broken=" + brokenReplaced + " retired=" + retired
                    + " timeouts=" + timeouts + " leaks=" + leaks;
        }
    }

    /** One physical connection plus the pool's bookkeeping about it. */
    static final class Entry<C> {
        final int id;
        final C connection;
        final Instant retireAt;          // null = never
        ConnState state;
        Instant borrowedAt;
        Throwable borrowStack;           // captured only when leak detection is on (it costs a stack walk)
        boolean leakReported;

        Entry(int id, C connection, Instant retireAt) { this.id = id; this.connection = connection; this.retireAt = retireAt; }
    }

    /** One thread waiting in line. The releaser fills `entry` (or grants `mayCreate`) and signals it. */
    private static final class Waiter<C> {
        final Condition wakeUp;
        Entry<C> entry;
        boolean mayCreate;
        Waiter(Condition c) { this.wakeUp = c; }
    }

    private final Connector<C> connector;
    private final Config config;
    private final Clock clock;
    private final Consumer<LeakReport> leakListener;

    private final ReentrantLock lock = new ReentrantLock(true);   // fair: lock acquisition is FIFO as well
    private final Deque<Entry<C>> idle = new ArrayDeque<>();      // LIFO: most recently used first (warm)
    private final Set<Entry<C>> inUse = new HashSet<>();          // includes VALIDATING
    private final Deque<Waiter<C>> waiters = new ArrayDeque<>();  // FIFO line of borrowers
    private int total;                                            // idle + inUse + being created/closed; <= maxSize
    private boolean closed;
    private long created, closedCount, brokenReplaced, retired, timeouts, leaks;
    private final AtomicInteger nextId = new AtomicInteger();
    private ScheduledExecutorService housekeeper;

    public ConnectionPool(Connector<C> connector, Config config, Clock clock, Consumer<LeakReport> leakListener) {
        if (config.maxSize() < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        this.connector = connector;
        this.config = config;
        this.clock = clock;
        this.leakListener = leakListener;
    }

    // ------------------------------------------------------------------ borrow

    public PooledConnection<C> borrow(Duration timeout) throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Entry<C> e = acquire(deadline);                 // under the lock: idle one, or null = "you may create"
            boolean fresh = (e == null);
            if (fresh) e = createEntry();                   // outside the lock
            else if (isExpired(e)) { destroy(e, Reason.RETIRED); continue; }
            else if (!isValidQuietly(e)) { destroy(e, Reason.BROKEN); continue; }   // replace and try again
            markInUse(e);
            return new PooledConnection<>(this, e);
        }
    }

    /** Returns an existing entry (now VALIDATING), or null meaning "a slot is reserved for you: create one". */
    private Entry<C> acquire(long deadline) throws TimeoutException, InterruptedException {
        lock.lockInterruptibly();
        try {
            if (closed) throw new IllegalStateException("pool closed");
            if (waiters.isEmpty()) {                        // nobody ahead of us in line
                Entry<C> e = idle.pollFirst();
                if (e != null) { e.state = ConnState.VALIDATING; inUse.add(e); return e; }
                if (total < config.maxSize()) { total++; return null; }
            }
            Waiter<C> w = new Waiter<>(lock.newCondition());
            waiters.addLast(w);
            try {
                while (w.entry == null && !w.mayCreate) {
                    if (closed) { waiters.remove(w); throw new IllegalStateException("pool closed"); }
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        waiters.remove(w);
                        timeouts++;
                        throw new TimeoutException("no connection within timeout; " + statsLocked());
                    }
                    w.wakeUp.awaitNanos(left);
                }
            } catch (InterruptedException ie) {
                waiters.remove(w);                          // don't lose what was handed to us just now
                if (w.entry != null) giveBack(w.entry);
                if (w.mayCreate) freeSlot();
                throw ie;
            }
            return w.entry;                                 // null if we were granted mayCreate
        } finally {
            lock.unlock();
        }
    }

    private Entry<C> createEntry() {
        C c;
        try {
            c = connector.create();
        } catch (Exception ex) {
            lock.lock();
            try { freeSlot(); } finally { lock.unlock(); }
            throw new IllegalStateException("could not create connection", ex);
        }
        Instant now = clock.instant();
        Instant retireAt = null;
        if (!config.maxLifetime().isZero()) {
            long jitterMs = config.lifetimeJitter().toMillis();
            long minus = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs + 1) : 0;
            retireAt = now.plus(config.maxLifetime()).minusMillis(minus);   // spread retirements out
        }
        Entry<C> e = new Entry<>(nextId.incrementAndGet(), c, retireAt);
        lock.lock();
        try {
            created++;
            e.state = ConnState.VALIDATING;
            inUse.add(e);
        } finally {
            lock.unlock();
        }
        return e;
    }

    private boolean isValidQuietly(Entry<C> e) {
        try { return connector.isValid(e.connection); } catch (RuntimeException ex) { return false; }
    }

    private void markInUse(Entry<C> e) {
        lock.lock();
        try {
            e.state = ConnState.IN_USE;
            e.borrowedAt = clock.instant();
            e.leakReported = false;
            e.borrowStack = config.leakThreshold().isZero() ? null
                    : new Throwable("connection #" + e.id + " borrowed by " + Thread.currentThread().getName());
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(Entry<C> e) {
        return e.retireAt != null && !clock.instant().isBefore(e.retireAt);
    }

    // ------------------------------------------------------------------ release / destroy

    /** Called by PooledConnection.close(). */
    void release(Entry<C> e) {
        boolean closeIt;
        lock.lock();
        try {
            inUse.remove(e);
            e.borrowStack = null;
            closeIt = closed || isExpired(e);
            if (closeIt) e.state = ConnState.CLOSED;
            else giveBack(e);
        } finally {
            lock.unlock();
        }
        if (closeIt) closeAndFreeSlot(e, closed ? Reason.POOL_CLOSED : Reason.RETIRED);
    }

    private enum Reason { BROKEN, RETIRED, POOL_CLOSED }

    private void destroy(Entry<C> e, Reason why) {
        lock.lock();
        try {
            inUse.remove(e);
            e.state = ConnState.CLOSED;
        } finally {
            lock.unlock();
        }
        closeAndFreeSlot(e, why);
    }

    /**
     * Close the physical connection FIRST, then free its slot. The other order would let a waiter create
     * a replacement while the old one is still open: briefly maxSize + 1 connections on the database.
     */
    private void closeAndFreeSlot(Entry<C> e, Reason why) {
        try { connector.close(e.connection); } catch (RuntimeException ignored) { }
        lock.lock();
        try {
            closedCount++;
            if (why == Reason.BROKEN) brokenReplaced++;
            if (why == Reason.RETIRED) retired++;
            freeSlot();
        } finally {
            lock.unlock();
        }
    }

    // must hold lock: hand a healthy connection to the first waiter, else put it back on the idle stack
    private void giveBack(Entry<C> e) {
        Waiter<C> w = waiters.pollFirst();
        if (w != null) {
            e.state = ConnState.VALIDATING;
            inUse.add(e);
            w.entry = e;
            w.wakeUp.signal();
        } else {
            e.state = ConnState.IDLE;
            idle.addFirst(e);
        }
    }

    // must hold lock: a connection died, so a slot is free. The first waiter may create a replacement.
    private void freeSlot() {
        total--;
        Waiter<C> w = closed ? null : waiters.pollFirst();
        if (w != null) {
            total++;
            w.mayCreate = true;
            w.wakeUp.signal();
        }
    }

    // ------------------------------------------------------------------ housekeeping

    /** Retire idle connections past maxLifetime; report leaks. Tests call it directly; production schedules it. */
    public void housekeep() {
        List<Entry<C>> toClose = new ArrayList<>();
        List<LeakReport> found = new ArrayList<>();
        lock.lock();
        try {
            Instant now = clock.instant();
            for (Iterator<Entry<C>> it = idle.iterator(); it.hasNext(); ) {
                Entry<C> e = it.next();
                if (isExpired(e)) { it.remove(); e.state = ConnState.CLOSED; toClose.add(e); }
            }
            if (!config.leakThreshold().isZero()) {
                for (Entry<C> e : inUse) {
                    if (e.state != ConnState.IN_USE || e.leakReported) continue;
                    Duration held = Duration.between(e.borrowedAt, now);
                    if (held.compareTo(config.leakThreshold()) > 0) {
                        e.leakReported = true;               // report once per borrow, not every run
                        leaks++;
                        found.add(new LeakReport(e.id, held, e.borrowStack));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        for (Entry<C> e : toClose) closeAndFreeSlot(e, Reason.RETIRED);
        for (LeakReport r : found) leakListener.accept(r);   // outside the lock: listeners may log slowly
    }

    /** Production style: run housekeep() on a background (daemon) thread. */
    public synchronized void startHousekeeping(Duration every) {
        if (housekeeper != null) return;
        housekeeper = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("pool-housekeeper").daemon(true).unstarted(r));
        long ms = every.toMillis();
        housekeeper.scheduleWithFixedDelay(this::housekeep, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Close idle connections now, in-use ones when they are returned; wake waiters so they fail fast. */
    @Override public void close() {
        List<Entry<C>> toClose;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            toClose = new ArrayList<>(idle);
            idle.clear();
            for (Entry<C> e : toClose) e.state = ConnState.CLOSED;
            for (Waiter<C> w : waiters) w.wakeUp.signal();
        } finally {
            lock.unlock();
        }
        synchronized (this) { if (housekeeper != null) housekeeper.shutdownNow(); }
        for (Entry<C> e : toClose) closeAndFreeSlot(e, Reason.POOL_CLOSED);
    }

    // ------------------------------------------------------------------ metrics

    public Stats stats() {
        lock.lock();
        try { return statsLocked(); } finally { lock.unlock(); }
    }

    private Stats statsLocked() {
        return new Stats(total, idle.size(), inUse.size(), waiters.size(), created, closedCount,
                brokenReplaced, retired, timeouts, leaks);
    }
}
