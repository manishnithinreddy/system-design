package logging;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * At most N events per call site per time window (fixed window). The call site is identified by
 * logger name + message TEMPLATE, so "timeout for user {}" counts as one source however many users.
 * The map holds one entry per call site: bounded by the number of log statements in the code.
 */
public final class RateLimitFilter implements Filter {
    private static final class Window { long start; int count; Window(long s) { start = s; } }

    private final int maxPerWindow;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong suppressed = new AtomicLong();

    public RateLimitFilter(int maxPerWindow, long windowMillis, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    @Override
    public Decision decide(LogEvent e) {
        Window w = windows.computeIfAbsent(e.loggerName() + '|' + e.template(), k -> new Window(clock.millis()));
        synchronized (w) {
            long now = clock.millis();
            if (now - w.start >= windowMillis) { w.start = now; w.count = 0; }
            if (w.count < maxPerWindow) { w.count++; return Decision.NEUTRAL; }
        }
        suppressed.incrementAndGet();
        return Decision.DENY;
    }

    /** Export this as a metric: "how much did we hide?" */
    public long suppressed() { return suppressed.get(); }
}
