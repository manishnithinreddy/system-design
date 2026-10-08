package pool;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * What execute() does when every worker is busy, the queue is full and the pool is at maxPoolSize.
 * Strategy pattern as an enum: each constant carries its own behaviour. Same four as ThreadPoolExecutor.
 */
public enum RejectionPolicy {
    /** Throw. The caller finds out immediately and decides (return 503, retry later...). JDK default. */
    ABORT {
        @Override void reject(Runnable task, SimpleThreadPool pool) {
            throw new RejectedExecutionException("pool saturated: " + pool.stats());
        }
    },
    /**
     * Run the task on the CALLER's thread. The caller is busy for the task's duration, so it can't submit
     * more: natural back-pressure that slows the producer down to the pool's speed.
     */
    CALLER_RUNS {
        @Override void reject(Runnable task, SimpleThreadPool pool) {
            if (!pool.isShutdown()) task.run();
        }
    },
    /** Silently drop the new task. */
    DISCARD {
        @Override void reject(Runnable task, SimpleThreadPool pool) {
            cancelIfFuture(task);
        }
    },
    /** Drop the oldest QUEUED task to make room for the new one ("newest data matters most"). */
    DISCARD_OLDEST {
        @Override void reject(Runnable task, SimpleThreadPool pool) {
            pool.discardOldestAndRetry(task);
        }
    };

    abstract void reject(Runnable task, SimpleThreadPool pool);

    /**
     * Difference from the JDK, on purpose: ThreadPoolExecutor's Discard policies just drop the FutureTask, so
     * anyone calling future.get() on it waits FOREVER. Cancelling it makes get() throw CancellationException.
     */
    static void cancelIfFuture(Runnable task) {
        if (task instanceof Future<?> f) f.cancel(false);
    }
}
