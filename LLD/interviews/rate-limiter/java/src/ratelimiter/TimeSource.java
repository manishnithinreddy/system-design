package ratelimiter;

/**
 * Abstraction over "what time is it", so tests can control time.
 * Uses a monotonic clock (nanoTime): it never jumps backwards when NTP adjusts the wall clock.
 */
public interface TimeSource {
    long nanoTime();

    TimeSource SYSTEM = System::nanoTime;
}
