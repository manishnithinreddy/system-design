package logging;

/** Strategy: turns an event into text. Appenders decide WHERE it goes, layouts decide HOW it looks. */
@FunctionalInterface
public interface Layout {
    String format(LogEvent event);
}
