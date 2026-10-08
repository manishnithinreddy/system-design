package logging;

/**
 * The static entry point application code uses: {@code LoggerFactory.getLogger(MyService.class)}.
 * A global (singleton) context is convenient but is shared state: tests that change levels or appenders
 * here affect each other, so tests should build their own LoggerContext instead.
 */
public final class LoggerFactory {
    private static final LoggerContext CONTEXT = createDefault();

    private LoggerFactory() {}

    private static LoggerContext createDefault() {
        LoggerContext ctx = new LoggerContext();
        ctx.root().addAppender(new ConsoleAppender(System.out, new PatternLayout()));
        ctx.registerShutdownHook();
        return ctx;
    }

    public static Logger getLogger(Class<?> type) { return CONTEXT.getLogger(type.getName()); }
    public static Logger getLogger(String name) { return CONTEXT.getLogger(name); }
    public static LoggerContext context() { return CONTEXT; }
}
