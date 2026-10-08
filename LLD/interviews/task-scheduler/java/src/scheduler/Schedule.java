package scheduler;

import java.time.ZoneId;

/**
 * WHEN a task runs. Each kind answers three questions; the scheduler does the rest.
 * "Nominal" time = when the run was planned (not when it actually started).
 */
public sealed interface Schedule {
    /** First planned time, given the moment the task was scheduled. */
    long first(long now);

    /** Planned time of the next run after a run finished. null = no more runs. */
    Long nextAfterRun(long nominal, long finishedAt);

    /** First planned time strictly after now (used when a misfired run is skipped). null = drop. */
    Long nextAfterSkip(long nominal, long now);

    /** Run once after a delay. */
    record Once(long delayMillis) implements Schedule {
        public long first(long now) { return now + delayMillis; }
        public Long nextAfterRun(long nominal, long finishedAt) { return null; }
        public Long nextAfterSkip(long nominal, long now) { return null; }
    }

    /** Start-to-start: runs are planned at first, first+P, first+2P... no matter how long each run takes. */
    record FixedRate(long initialDelayMillis, long periodMillis) implements Schedule {
        public FixedRate { if (periodMillis <= 0) throw new IllegalArgumentException("period must be > 0"); }
        public long first(long now) { return now + initialDelayMillis; }
        public Long nextAfterRun(long nominal, long finishedAt) { return nominal + periodMillis; }
        public Long nextAfterSkip(long nominal, long now) {           // next point on the same grid
            return nominal + ((now - nominal) / periodMillis + 1) * periodMillis;
        }
    }

    /** End-to-start: the next run is planned D after the previous run FINISHED. */
    record FixedDelay(long initialDelayMillis, long delayMillis) implements Schedule {
        public FixedDelay { if (delayMillis <= 0) throw new IllegalArgumentException("delay must be > 0"); }
        public long first(long now) { return now + initialDelayMillis; }
        public Long nextAfterRun(long nominal, long finishedAt) { return finishedAt + delayMillis; }
        public Long nextAfterSkip(long nominal, long now) { return now + delayMillis; }
    }

    /** Calendar-based: "30 2 * * *" in a time zone. */
    record Cron(CronExpression expression, ZoneId zone) implements Schedule {
        public long first(long now) { return expression.nextAfter(now, zone); }
        public Long nextAfterRun(long nominal, long finishedAt) { return expression.nextAfter(nominal, zone); }
        public Long nextAfterSkip(long nominal, long now) { return expression.nextAfter(now, zone); }
    }
}
