package ratelimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock: time only moves when the test says so. */
public final class FakeTimeSource implements TimeSource {
    private final AtomicLong now = new AtomicLong();

    @Override
    public long nanoTime() {
        return now.get();
    }

    public void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }
}
