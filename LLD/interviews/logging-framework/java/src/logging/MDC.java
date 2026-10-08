package logging;

import java.util.HashMap;
import java.util.Map;

/**
 * Mapped Diagnostic Context: per-thread key/values (requestId, userId) added to every event.
 * Stored in a ThreadLocal, so it does NOT follow work handed to another thread or pool:
 * copy it explicitly (see wrap) and always clear it in a finally block on pooled threads.
 */
public final class MDC {
    private static final ThreadLocal<Map<String, String>> CONTEXT = ThreadLocal.withInitial(HashMap::new);

    private MDC() {}

    public static void put(String key, String value) { CONTEXT.get().put(key, value); }
    public static String get(String key) { return CONTEXT.get().get(key); }
    public static void remove(String key) { CONTEXT.get().remove(key); }
    public static void clear() { CONTEXT.remove(); }

    /** Immutable copy, taken when an event is created. Later put/remove calls don't change old events. */
    public static Map<String, String> snapshot() {
        Map<String, String> m = CONTEXT.get();
        return m.isEmpty() ? Map.of() : Map.copyOf(m);
    }

    /** Propagation to another thread: capture now, install around the task, restore afterwards. */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = snapshot();
        return () -> {
            Map<String, String> previous = CONTEXT.get();
            CONTEXT.set(new HashMap<>(captured));
            try { task.run(); } finally { CONTEXT.set(previous); }
        };
    }
}
