package scheduler;

import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with FULL jitter: after failure n, wait a random time in [0, cap],
 * where cap = min(maxDelay, baseDelay * 2^(n-1)). maxAttempts counts the first run too.
 */
public record RetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
    public static final RetryPolicy NONE = new RetryPolicy(1, 0, 0);

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
    }

    /** @param failures how many times it has failed so far (1 after the first failure)
     *  @param random   returns a number in [0, 1]; injected so tests are deterministic */
    public long backoffMillis(int failures, DoubleSupplier random) {
        long cap = Math.min(maxDelayMillis, baseDelayMillis << Math.min(failures - 1, 30));   // no overflow
        return Math.round(random.getAsDouble() * cap);
    }
}
