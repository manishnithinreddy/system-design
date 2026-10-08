package logging;

import java.io.PrintStream;

/** Writes one formatted line per event to a PrintStream (System.out in production, a buffer in tests). */
public final class ConsoleAppender implements Appender {
    private final PrintStream out;
    private final Layout layout;

    public ConsoleAppender(PrintStream out, Layout layout) {
        this.out = out;
        this.layout = layout;
    }

    /**
     * Format outside the lock (pure CPU, no shared state), write inside it.
     * synchronized: two threads must never interleave characters of their lines.
     */
    @Override
    public void append(LogEvent event) {
        String line = layout.format(event);
        synchronized (this) {
            out.println(line);
            out.flush();
        }
    }
}
