package logging;

/** Where events go: console, file, network, memory. Must be safe to call from many threads. */
public interface Appender extends AutoCloseable {
    void append(LogEvent event);

    /** Flush and release resources. Default: nothing to do. */
    @Override
    default void close() {}
}
