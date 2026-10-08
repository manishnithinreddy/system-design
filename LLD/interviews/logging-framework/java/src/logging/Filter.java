package logging;

/**
 * One link in a Chain of Responsibility. Each filter may decide (ACCEPT / DENY) or pass (NEUTRAL)
 * to the next one. If every filter is NEUTRAL, the event is logged. Same contract as Logback's FilterReply.
 */
@FunctionalInterface
public interface Filter {
    enum Decision { ACCEPT, DENY, NEUTRAL }

    Decision decide(LogEvent event);

    /** E.g. put first in the chain so ERRORs skip every later filter (rate limiting, sampling). */
    static Filter acceptAtOrAbove(Level level) {
        return e -> e.level().isAtLeast(level) ? Decision.ACCEPT : Decision.NEUTRAL;
    }
}
