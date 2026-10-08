package logging;

import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry: one Logger per name, the hierarchy, global filters, the clock.
 * Not a singleton itself: tests create their own; LoggerFactory holds the app-wide one.
 */
public final class LoggerContext implements AutoCloseable {
    public static final String ROOT = "ROOT";

    private final Clock clock;
    private final Logger root;
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final List<Filter> filters = new CopyOnWriteArrayList<>();

    public LoggerContext() { this(Clock.systemUTC()); }

    public LoggerContext(Clock clock) {
        this.clock = clock;
        this.root = new Logger(ROOT, null, this);
        root.setLevel(Level.INFO);
        loggers.put(ROOT, root);
    }

    public Logger root() { return root; }
    Clock clock() { return clock; }

    /** Fast path: a lock-free map read. Creation (rare) is synchronized and creates missing parents too. */
    public Logger getLogger(String name) {
        Logger l = loggers.get(name);
        return l != null ? l : create(name);
    }

    private synchronized Logger create(String name) {
        Logger existing = loggers.get(name);
        if (existing != null) return existing;
        int dot = name.lastIndexOf('.');
        Logger parent = dot < 0 ? root : create(name.substring(0, dot));
        Logger l = new Logger(name, parent, this);
        loggers.put(name, l);
        return l;
    }

    /** Runtime change, no restart: what Spring's POST /actuator/loggers/{name} does. null = inherit. */
    public void setLevel(String loggerName, Level level) { getLogger(loggerName).setLevel(level); }

    public void addFilter(Filter f) { filters.add(f); }

    /** Chain of Responsibility: first non-NEUTRAL answer wins; all NEUTRAL means accept. */
    boolean filtersAccept(LogEvent e) {
        for (Filter f : filters) {
            switch (f.decide(e)) {
                case ACCEPT: return true;
                case DENY: return false;
                case NEUTRAL: break;
            }
        }
        return true;
    }

    /** Close every appender once (the same appender may be attached to several loggers). */
    @Override
    public void close() {
        Set<Appender> all = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Logger l : loggers.values()) all.addAll(l.appenders());
        for (Appender a : all) a.close();
    }

    /** On SIGTERM / normal exit, flush async queues before the JVM dies. */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "logging-shutdown"));
    }
}
