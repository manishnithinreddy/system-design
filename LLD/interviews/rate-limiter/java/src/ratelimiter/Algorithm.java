package ratelimiter;

public enum Algorithm {
    TOKEN_BUCKET,
    TOKEN_BUCKET_LOCK_FREE,
    FIXED_WINDOW,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER
}
