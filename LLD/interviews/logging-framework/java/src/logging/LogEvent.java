package logging;

import java.time.Instant;
import java.util.Map;

/**
 * One log call, captured as an immutable value. Built only AFTER the level check passed.
 * The message is already formatted here (on the caller's thread), so an async appender never
 * reads argument objects that the caller may have changed in the meantime.
 */
public record LogEvent(
        Instant instant,
        Level level,
        String loggerName,
        String template,          // "user {} logged in": the call-site's identity (rate limiting uses it)
        String message,           // "user alice logged in"
        String threadName,
        Map<String, String> mdc,  // an immutable COPY of the thread's MDC at the time of the call
        Throwable throwable) {    // may be null
}
