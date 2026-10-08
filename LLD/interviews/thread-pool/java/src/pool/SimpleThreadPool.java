package pool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A readable version of java.util.concurrent.ThreadPoolExecutor:
 *   core / max threads, a BOUNDED queue, keep-alive for the extra threads, a rejection policy,
 *   graceful shutdown() and abrupt shutdownNow().
 *
 * execute() decides in this order (exactly like ThreadPoolExecutor, which surprises most people):
 *   1. fewer than core threads?  -> start a new thread with this task
 *   2. queue has room?           -> queue it (NO new thread, even if below max!)
 *   3. fewer than max threads?   -> start an extra thread with this task
 *   4. otherwise                 -> rejection policy
 * So threads above core only appear when the queue is FULL. With an unbounded queue, step 2 always
 * succeeds and maxPoolSize is never used.
 */
public final class SimpleThreadPool {
    private enum State { RUNNING, SHUTDOWN, STOP, TERMINATED }

    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveNanos;
    private final BlockingQueue<Runnable> queue;
    private final RejectionPolicy policy;
    private final String namePrefix;

    // mainLock guards: state, workers. Kept short: never held while a task runs.
    private final ReentrantLock mainLock = new ReentrantLock();
    private final Condition terminated = mainLock.newCondition();
    private final Set<Worker> workers = new HashSet<>();
    private volatile State state = State.RUNNING;

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private int largestPoolSize;
    private volatile Consumer<Throwable> taskErrorHandler =
            t -> System.err.println("[pool] task failed on " + Thread.currentThread().getName() + ": " + t);

    public SimpleThreadPool(int corePoolSize, int maxPoolSize, Duration keepAlive,
                            int queueCapacity, RejectionPolicy policy, String namePrefix) {
        if (corePoolSize < 0 || maxPoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1)
            throw new IllegalArgumentException("need 0 <= core <= max, max >= 1, queueCapacity >= 1");
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveNanos = keepAlive.toNanos();
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.policy = policy;
        this.namePrefix = namePrefix;
    }

    /** Fixed-size pool, the L4 answer: core == max, nothing ever times out. */
    public static SimpleThreadPool fixed(int threads, int queueCapacity) {
        return new SimpleThreadPool(threads, threads, Duration.ZERO, queueCapacity, RejectionPolicy.ABORT, "fixed");
    }

    // ------------------------------------------------------------------ submitting

    /** Wraps the task in a FutureTask: exceptions are captured into the Future, not thrown at the worker. */
    public <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> f = new FutureTask<>(task);
        execute(f);
        return f;
    }

    public Future<?> submit(Runnable task) {
        FutureTask<Void> f = new FutureTask<>(task, null);
        execute(f);
        return f;
    }

    public void execute(Runnable task) {
        if (task == null) throw new NullPointerException();
        mainLock.lock();
        try {
            if (state != State.RUNNING)
                throw new RejectedExecutionException("pool is shut down");     // after shutdown: always reject
            if (workers.size() < corePoolSize) { addWorker(task); return; }    // step 1
            if (queue.offer(task)) {                                           // step 2
                if (workers.isEmpty()) addWorker(null);                        // core == 0: someone must drain it
                return;
            }
            if (workers.size() < maxPoolSize) { addWorker(task); return; }     // step 3
        } finally {
            mainLock.unlock();
        }
        rejected.incrementAndGet();                                            // step 4, OUTSIDE the lock:
        policy.reject(task, this);                                             // CALLER_RUNS may run for seconds
    }

    /** Used by DISCARD_OLDEST: drop the head of the queue, then try again with the new task. */
    void discardOldestAndRetry(Runnable task) {
        if (isShutdown()) return;
        Runnable oldest = queue.poll();
        if (oldest != null) RejectionPolicy.cancelIfFuture(oldest);
        execute(task);   // in a race it can be rejected again: rejected is then counted twice, which is honest
    }

    // must hold mainLock
    private void addWorker(Runnable firstTask) {
        Worker w = new Worker(firstTask);
        w.thread = new Thread(w, namePrefix + "-" + threadSeq.incrementAndGet());
        workers.add(w);
        largestPoolSize = Math.max(largestPoolSize, workers.size());
        w.thread.start();
    }

    // ------------------------------------------------------------------ the worker

    private final class Worker implements Runnable {
        Thread thread;
        Runnable firstTask;
        /**
         * Held while running a task, so shutdown() can tell idle workers (safe to interrupt) from busy ones.
         * A Semaphore(1), not a ReentrantLock, on purpose: if a task itself calls shutdown(), tryAcquire on
         * its own worker must FAIL (busy), not succeed re-entrantly and interrupt the running task.
         */
        final Semaphore runLock = new Semaphore(1);

        Worker(Runnable firstTask) { this.firstTask = firstTask; }

        @Override public void run() {
            Runnable task = firstTask;
            firstTask = null;
            try {
                while (task != null || (task = getTask(this)) != null) {
                    runLock.acquireUninterruptibly();
                    try {
                        // An interrupt meant for an IDLE worker (from shutdown) must not leak into the next task.
                        // Only shutdownNow (STOP) wants running tasks interrupted.
                        if (state == State.STOP) thread.interrupt(); else Thread.interrupted();
                        active.incrementAndGet();
                        try {
                            task.run();
                        } catch (Throwable t) {                 // the worker SURVIVES a failing task
                            failed.incrementAndGet();
                            taskErrorHandler.accept(t);
                        } finally {
                            active.decrementAndGet();
                            completed.incrementAndGet();
                        }
                    } finally {
                        runLock.release();
                    }
                    task = null;
                }
            } finally {
                workerExited(this);
            }
        }
    }

    /**
     * Blocks until there is a task, or returns null when this worker should exit:
     * pool stopping, pool shut down and queue empty, or (for threads above core) idle for keepAlive.
     */
    private Runnable getTask(Worker self) {
        boolean timedOut = false;
        while (true) {
            State s = state;
            if (s == State.STOP || (s == State.SHUTDOWN && queue.isEmpty())) return null;

            boolean extraThread;
            mainLock.lock();
            try {
                extraThread = workers.size() > corePoolSize;
                // Shrink back towards core. Decided under mainLock so two idle extras can't both leave and
                // drop below core; and never leave a non-empty queue with nobody to drain it (core == 0).
                if (extraThread && timedOut && (workers.size() > 1 || queue.isEmpty())) {
                    workers.remove(self);
                    return null;
                }
            } finally {
                mainLock.unlock();
            }
            try {
                Runnable r = extraThread ? queue.poll(keepAliveNanos, TimeUnit.NANOSECONDS) : queue.take();
                if (r != null) return r;
                timedOut = true;
            } catch (InterruptedException e) {
                timedOut = false;          // woken by shutdown: loop and re-check the state
            }
        }
    }

    private void workerExited(Worker w) {
        mainLock.lock();
        try {
            workers.remove(w);
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    // must hold mainLock
    private void tryTerminate() {
        if (state == State.RUNNING || state == State.TERMINATED) return;
        if (state == State.SHUTDOWN && !queue.isEmpty()) return;
        if (!workers.isEmpty()) { interruptIdleWorkers(); return; }   // wake anyone still parked in take()
        state = State.TERMINATED;
        terminated.signalAll();
    }

    // must hold mainLock
    private void interruptIdleWorkers() {
        for (Worker w : workers) {
            if (w.runLock.tryAcquire()) {        // got it = not running a task = idle, safe to interrupt
                try { w.thread.interrupt(); } finally { w.runLock.release(); }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Graceful: stop accepting, finish running AND queued tasks, then threads exit. Does not wait. */
    public void shutdown() {
        mainLock.lock();
        try {
            if (state == State.RUNNING) state = State.SHUTDOWN;
            interruptIdleWorkers();              // idle workers are parked in take(): wake them to notice
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /** Abrupt: stop accepting, interrupt running tasks, return queued tasks that never started. */
    public List<Runnable> shutdownNow() {
        List<Runnable> pending = new ArrayList<>();
        mainLock.lock();
        try {
            if (state != State.TERMINATED) state = State.STOP;
            for (Worker w : workers) w.thread.interrupt();   // a task that ignores interrupts keeps running
            queue.drainTo(pending);
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
        return pending;
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        mainLock.lock();
        try {
            while (state != State.TERMINATED) {
                if (nanos <= 0) return false;
                nanos = terminated.awaitNanos(nanos);
            }
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    public boolean isShutdown() { return state != State.RUNNING; }

    public boolean isTerminated() { return state == State.TERMINATED; }

    public void setTaskErrorHandler(Consumer<Throwable> handler) { this.taskErrorHandler = handler; }

    // ------------------------------------------------------------------ metrics

    public int poolSize() {
        mainLock.lock();
        try { return workers.size(); } finally { mainLock.unlock(); }
    }

    public record Stats(int poolSize, int largest, int active, int queued, long completed, long failed, long rejected) {
        @Override public String toString() {
            return "threads=" + poolSize + " (largest " + largest + ") active=" + active + " queued=" + queued
                    + " completed=" + completed + " failed=" + failed + " rejected=" + rejected;
        }
    }

    public Stats stats() {
        mainLock.lock();
        try {
            return new Stats(workers.size(), largestPoolSize, active.get(), queue.size(),
                    completed.get(), failed.get(), rejected.get());
        } finally {
            mainLock.unlock();
        }
    }
}
