package scheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * A 5-field cron expression: minute hour day-of-month month day-of-week.
 * Each field supports: *  5  1,15  9-17  *&#47;15  9-17/2. Day-of-week is 0-6 (0 or 7 = Sunday).
 * No names (MON, JAN), no seconds, no L/W/# (Quartz extras).
 *
 * Classic cron rule: if BOTH day-of-month and day-of-week are restricted (not *), a day matches
 * if EITHER matches. Otherwise both must match.
 *
 * Time zones and daylight saving time (DST), the policy implemented here:
 *  - Gap (spring forward, e.g. 02:00-02:59 does not exist): fire at the instant the gap ends
 *    (03:00 new time), once, like Vixie cron running skipped jobs right after the change.
 *  - Overlap (fall back, e.g. 01:00-01:59 happens twice): fire once, at the FIRST occurrence.
 *    Limitation: an every-N-minutes job therefore also pauses during the repeated hour.
 */
public final class CronExpression {
    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32];   // index 1..31
    private final boolean[] months = new boolean[13];        // index 1..12
    private final boolean[] daysOfWeek = new boolean[7];     // 0 = Sunday
    private final boolean domRestricted, dowRestricted;
    private final String text;

    public CronExpression(String text) {
        this.text = text;
        String[] f = text.trim().split("\\s+");
        if (f.length != 5) throw new IllegalArgumentException("expected 5 fields: " + text);
        parse(f[0], minutes, 0, 59);
        parse(f[1], hours, 0, 23);
        parse(f[2], daysOfMonth, 1, 31);
        parse(f[3], months, 1, 12);
        boolean[] dow = new boolean[8];
        parse(f[4], dow, 0, 7);
        for (int d = 0; d < 7; d++) daysOfWeek[d] = dow[d] || (d == 0 && dow[7]);
        domRestricted = !f[2].equals("*");
        dowRestricted = !f[4].equals("*");
    }

    /** Parses "a", "a-b", "*", each optionally followed by "/step", separated by commas. */
    private static void parse(String field, boolean[] out, int min, int max) {
        for (String part : field.split(",")) {
            String[] rangeAndStep = part.split("/");
            int step = rangeAndStep.length == 2 ? Integer.parseInt(rangeAndStep[1]) : 1;
            String range = rangeAndStep[0];
            int lo, hi;
            if (range.equals("*")) { lo = min; hi = max; }
            else if (range.contains("-")) {
                String[] ab = range.split("-");
                lo = Integer.parseInt(ab[0]); hi = Integer.parseInt(ab[1]);
            } else {
                lo = Integer.parseInt(range);
                hi = rangeAndStep.length == 2 ? max : lo;          // "5/15" means 5, 20, 35, 50
            }
            if (lo < min || hi > max || lo > hi || step < 1) {
                throw new IllegalArgumentException("bad cron field '" + field + "' (allowed " + min + "-" + max + ")");
            }
            for (int v = lo; v <= hi; v += step) out[v] = true;
        }
    }

    /** First fire time strictly after {@code afterMillis}, in epoch millis. */
    public long nextAfter(long afterMillis, ZoneId zone) {
        Instant after = Instant.ofEpochMilli(afterMillis);
        LocalDateTime t = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDateTime giveUp = t.plusYears(5);                       // e.g. "0 0 30 2 *" never matches
        while (t.isBefore(giveUp)) {
            // Jump by the largest unit that doesn't match, so a yearly job takes ~100 steps, not 500,000.
            if (!months[t.getMonthValue()]) { t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(); continue; }
            if (!dayMatches(t)) { t = t.toLocalDate().plusDays(1).atStartOfDay(); continue; }
            if (!hours[t.getHour()]) { t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1); continue; }
            if (!minutes[t.getMinute()]) { t = t.plusMinutes(1); continue; }
            Instant candidate = toInstant(t, zone);
            if (candidate.isAfter(after)) return candidate.toEpochMilli();
            t = t.plusMinutes(1);                                    // repeated hour: already fired
        }
        throw new IllegalStateException("cron '" + text + "' never fires");
    }

    private boolean dayMatches(LocalDateTime t) {
        boolean dom = daysOfMonth[t.getDayOfMonth()];
        boolean dow = daysOfWeek[t.getDayOfWeek().getValue() % 7];     // java: Mon=1..Sun=7 -> Sun=0
        return domRestricted && dowRestricted ? dom || dow : dom && dow;
    }

    /** Local wall-clock time -> a real instant, applying the DST policy above. */
    private static Instant toInstant(LocalDateTime local, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) return rules.getTransition(local).getInstant();   // in a gap: when it ends
        return local.toInstant(offsets.get(0));                                  // overlap: earlier offset first
    }

    @Override public String toString() { return text; }
}
