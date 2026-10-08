package kvstore;

/** Same three choices as Redis' appendfsync setting. */
public enum FsyncPolicy {
    /** fsync after every write: safest, slowest (each write waits for the disk). */
    ALWAYS,
    /** fsync at most once per second: lose up to ~1 s of writes on power loss. */
    EVERY_SECOND,
    /** let the OS decide when to flush: fastest, may lose many seconds on power loss. */
    NEVER
}
