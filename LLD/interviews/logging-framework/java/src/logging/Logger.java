package logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A named logger in a dotted hierarchy ("com.shop.db" -> "com.shop" -> "com" -> ROOT).
 * Its level may be unset (null): then it inherits the nearest ancestor's level.
 */
public final class Logger {
    private final String name;
    private final Logger parent;                 // null only for ROOT
    private final LoggerContext context;
    private volatile Level level;                // null = inherit; volatile = runtime changes are seen by all threads
    private volatile boolean additive = true;    // also send events to the ancestors' appenders?
    private final List<Appender> appenders = new CopyOnWriteArrayList<>();   // read on every call, rarely changed

    Logger(String name, Logger parent, LoggerContext context) {
        this.name = name;
        this.parent = parent;
        this.context = context;
    }

    public String name() { return name; }
    public Logger parent() { return parent; }
    public Level level() { return level; }

    public void setLevel(Level level) {
        if (parent == null && level == null) throw new IllegalArgumentException("ROOT must have a level");
        this.level = level;
    }

    public void setAdditive(boolean additive) { this.additive = additive; }
    public void addAppender(Appender a) { appenders.add(a); }
    List<Appender> appenders() { return appenders; }

    /** Walk up until someone has a level. Depth is small (3-6), so this is a few pointer reads. */
    public Level effectiveLevel() {
        for (Logger l = this; l != null; l = l.parent) {
            Level lv = l.level;
            if (lv != null) return lv;
        }
        throw new IllegalStateException("unreachable: ROOT always has a level");
    }

    public boolean isEnabled(Level lv) { return lv != Level.OFF && lv.isAtLeast(effectiveLevel()); }

    public void trace(String template, Object... args) { log(Level.TRACE, template, args); }
    public void debug(String template, Object... args) { log(Level.DEBUG, template, args); }
    public void info(String template, Object... args)  { log(Level.INFO, template, args); }
    public void warn(String template, Object... args)  { log(Level.WARN, template, args); }
    public void error(String template, Object... args) { log(Level.ERROR, template, args); }

    public void log(Level lv, String template, Object... args) {
        if (!isEnabled(lv)) return;   // FIRST and cheapest: disabled calls never format or touch arguments

        int n = args == null ? 0 : args.length;
        Throwable thrown = null;      // SLF4J rule: a Throwable as the LAST arg with no "{}" left for it = the exception
        if (n > 0 && args[n - 1] instanceof Throwable t && MessageFormatter.countPlaceholders(template) < n) {
            thrown = t;
            n--;
        }
        LogEvent event = new LogEvent(context.clock().instant(), lv, name, template,
                MessageFormatter.format(template, args, n),
                Thread.currentThread().getName(), MDC.snapshot(), thrown);

        if (!context.filtersAccept(event)) return;
        for (Logger l = this; l != null; l = l.parent) {   // own appenders, then ancestors' while additive
            for (Appender a : l.appenders) {
                try {
                    a.append(event);
                } catch (RuntimeException ex) {             // logging must never throw into business code
                    System.err.println("[logging] appender failed: " + ex);
                }
            }
            if (!l.additive) break;
        }
    }
}
