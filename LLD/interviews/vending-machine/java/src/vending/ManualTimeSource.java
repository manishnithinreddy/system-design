package vending;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when a test (or the demo) tells it to. */
public final class ManualTimeSource implements TimeSource {
    private final AtomicLong now;

    public ManualTimeSource(long startMillis) { this.now = new AtomicLong(startMillis); }

    @Override public long nowMillis() { return now.get(); }

    public void advance(long millis) { now.addAndGet(millis); }
}
