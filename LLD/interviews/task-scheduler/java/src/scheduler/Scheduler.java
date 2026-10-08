package scheduler;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;

/**
 * In-process task scheduler: a min-heap of tasks ordered by next run time.
 *
 * Two ways to drive it (same core logic):
 *  - runDue(): runs everything due "now" on the calling thread. Deterministic, used by tests.
 *  - start(workers): a dispatcher thread sleeps until the earliest task is due, then hands due
 *    tasks to a worker pool. Adding an earlier task wakes it up (Condition.signal).
 *
 * No overlap by construction: a recurring task is put back in the heap only AFTER its run finishes.
 */
public final class Scheduler {
    private static final Comparator<ScheduledTask> BY_TIME =
            Comparator.comparingLong((ScheduledTask t) -> t.runAt).thenComparingLong(t -> t.seq);
    /** Among tasks that are already due: higher priority first, then earlier time, then FIFO. */
    private static final Comparator<ScheduledTask> DUE_ORDER =
            Comparator.comparingInt((ScheduledTask t) -> -t.options.priority()).thenComparing(BY_TIME);

    private final TimeSource time;
    private final long misfireThresholdMillis;
    private final DoubleSupplier random;
    private final PriorityQueue<ScheduledTask> heap = new PriorityQueue<>(BY_TIME);
    private final List<DeadLetter> deadLetters = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();   // "the earliest task may have changed"
    private long nextId = 0, nextSeq = 0;
    private boolean accepting = true;
    private ExecutorService workers;
    private Thread dispatcher;

    public Scheduler(TimeSource time) {
        this(time, 1_000, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param misfireThresholdMillis a run starting later than this is a "misfire" (see MisfirePolicy)
     *  @param random                 jitter source returning [0, 1]; pass a constant in tests */
    public Scheduler(TimeSource time, long misfireThresholdMillis, DoubleSupplier random) {
        this.time = time;
        this.misfireThresholdMillis = misfireThresholdMillis;
        this.random = random;
    }

    // ---------------- API ----------------

    public ScheduledTask schedule(String name, Task body, long delayMillis) {
        return schedule(name, body, new Schedule.Once(delayMillis), TaskOptions.DEFAULT);
    }

    public ScheduledTask scheduleAtFixedRate(String name, Task body, long initialDelayMillis, long periodMillis) {
        return schedule(name, body, new Schedule.FixedRate(initialDelayMillis, periodMillis), TaskOptions.DEFAULT);
    }

    public ScheduledTask scheduleWithFixedDelay(String name, Task body, long initialDelayMillis, long delayMillis) {
        return schedule(name, body, new Schedule.FixedDelay(initialDelayMillis, delayMillis), TaskOptions.DEFAULT);
    }

    public ScheduledTask scheduleCron(String name, Task body, String cron, ZoneId zone) {
        return schedule(name, body, new Schedule.Cron(new CronExpression(cron), zone), TaskOptions.DEFAULT);
    }

    public ScheduledTask schedule(String name, Task body, Schedule schedule, TaskOptions options) {
        lock.lock();
        try {
            if (!accepting) throw new IllegalStateException("scheduler is shutting down");
            ScheduledTask t = new ScheduledTask(this, ++nextId, name, body, schedule, options);
            t.nominalAt = schedule.first(time.nowMillis());
            enqueue(t, t.nominalAt);
            return t;
        } finally {
            lock.unlock();
        }
    }

    boolean cancel(ScheduledTask t) {
        lock.lock();
        try {
            if (t.state == ScheduledTask.State.SCHEDULED || t.state == ScheduledTask.State.RUNNING) {
                t.state = ScheduledTask.State.CANCELLED;      // stays in the heap; skipped when polled
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public List<DeadLetter> deadLetters() {
        lock.lock();
        try { return List.copyOf(deadLetters); } finally { lock.unlock(); }
    }

    /** Runs every task due at this moment on the calling thread, in DUE_ORDER. Returns how many ran. */
    public int runDue() {
        List<ScheduledTask> due;
        lock.lock();
        try { due = takeDue(time.nowMillis()); } finally { lock.unlock(); }
        due.forEach(this::runOnce);
        return due.size();
    }

    // ---------------- core (lock held) ----------------

    private void enqueue(ScheduledTask t, long runAt) {
        t.runAt = runAt;
        t.seq = nextSeq++;
        t.state = ScheduledTask.State.SCHEDULED;
        heap.add(t);
        if (heap.peek() == t) changed.signal();   // new earliest task: wake the dispatcher, or it oversleeps
    }

    /** Removes due tasks from the heap, applying lazy cancel and the misfire policy. */
    private List<ScheduledTask> takeDue(long now) {
        List<ScheduledTask> due = new ArrayList<>();
        while (!heap.isEmpty() && heap.peek().runAt <= now) {
            ScheduledTask t = heap.poll();
            if (t.state == ScheduledTask.State.CANCELLED) continue;           // lazy deletion
            boolean misfired = t.failures == 0 && now - t.runAt > misfireThresholdMillis;   // retries never misfire
            if (misfired && t.options.misfire() == MisfirePolicy.SKIP) {
                Long next = t.schedule.nextAfterSkip(t.nominalAt, now);
                if (next == null) { t.state = ScheduledTask.State.DONE; continue; }
                t.nominalAt = next;
                enqueue(t, next);                                              // next > now: not taken again
                continue;
            }
            if (misfired) t.nominalAt = now;                                   // FIRE_ONCE_NOW: continue from now
            t.lastLagMillis = now - t.runAt;
            t.state = ScheduledTask.State.RUNNING;
            due.add(t);
        }
        due.sort(DUE_ORDER);
        return due;
    }

    /** Runs the body WITHOUT the lock (it may be slow), then decides what happens next. */
    private void runOnce(ScheduledTask t) {
        Exception error = null;
        try {
            t.body.run();
        } catch (Exception e) {
            error = e;
        }
        long finishedAt = time.nowMillis();
        lock.lock();
        try { afterRun(t, error, finishedAt); } finally { lock.unlock(); }
    }

    private void afterRun(ScheduledTask t, Exception error, long finishedAt) {
        if (t.state == ScheduledTask.State.CANCELLED || !accepting) return;
        if (error != null) {
            t.failures++;
            RetryPolicy retry = t.options.retry();
            if (t.failures < retry.maxAttempts()) {
                enqueue(t, finishedAt + retry.backoffMillis(t.failures, random));   // same occurrence, later
                return;
            }
            deadLetters.add(new DeadLetter(t.name, t.failures, String.valueOf(error), finishedAt));
        }
        t.failures = 0;
        Long next = t.schedule.nextAfterRun(t.nominalAt, finishedAt);
        if (next == null) {
            t.state = error == null ? ScheduledTask.State.DONE : ScheduledTask.State.DEAD;
            return;
        }
        t.nominalAt = next;                       // a recurring task keeps going even after a dead letter
        enqueue(t, next);
    }

    // ---------------- background mode ----------------

    public void start(ExecutorService workerPool) {
        lock.lock();
        try {
            if (dispatcher != null) throw new IllegalStateException("already started");
            workers = workerPool;
            dispatcher = new Thread(this::dispatchLoop, "scheduler-dispatcher");
            dispatcher.setDaemon(true);
            dispatcher.start();
        } finally {
            lock.unlock();
        }
    }

    private void dispatchLoop() {
        lock.lock();
        try {
            while (accepting) {
                ScheduledTask head = heap.peek();
                if (head == null) { changed.await(); continue; }               // nothing to do: sleep until signalled
                long waitMillis = head.runAt - time.nowMillis();
                if (waitMillis > 0) {                                          // sleep until due OR an earlier task arrives
                    changed.awaitNanos(TimeUnit.MILLISECONDS.toNanos(waitMillis));
                    continue;                                                  // always re-check: wake-ups can be early
                }
                for (ScheduledTask t : takeDue(time.nowMillis())) workers.execute(() -> runOnce(t));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Graceful shutdown: stop accepting and dispatching, let running tasks finish (up to the timeout),
     * then interrupt whatever is still running. Tasks still waiting in the heap are not run.
     * @return true if every running task finished in time
     */
    public boolean shutdown(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            accepting = false;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (dispatcher != null) dispatcher.join();
        if (workers == null) return true;
        workers.shutdown();                                   // no new work; queued + running work continues
        if (workers.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) return true;
        workers.shutdownNow();                                // interrupt the stragglers
        return false;
    }
}
