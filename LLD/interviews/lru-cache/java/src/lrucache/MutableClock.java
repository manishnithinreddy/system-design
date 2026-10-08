package lrucache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock: time moves only when the test says so. */
public final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-08T00:00:00Z");

    public void advance(Duration d) { now = now.plus(d); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
}
