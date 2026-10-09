package booking;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A small subset of the iCalendar RRULE: FREQ=DAILY|WEEKLY, INTERVAL=n, COUNT=n or UNTIL=yyyyMMdd.
 * (BYDAY, BYMONTHDAY, EXDATE... are left out on purpose.)
 * Occurrences keep the same LOCAL wall-clock time in the given zone, so "9:00 every Monday" stays 9:00
 * across a daylight-saving change even though the UTC instant moves by an hour.
 */
public record Recurrence(boolean weekly, int interval, int count, LocalDate until) {
    public static Recurrence parse(String rrule) {
        boolean weekly = false;
        int interval = 1, count = 0;
        LocalDate until = null;
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            switch (kv[0]) {
                case "FREQ" -> {
                    if (kv[1].equals("WEEKLY")) weekly = true;
                    else if (!kv[1].equals("DAILY")) throw new IllegalArgumentException("unsupported FREQ " + kv[1]);
                }
                case "INTERVAL" -> interval = Integer.parseInt(kv[1]);
                case "COUNT" -> count = Integer.parseInt(kv[1]);
                case "UNTIL" -> until = LocalDate.parse(kv[1], java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                default -> throw new IllegalArgumentException("unsupported part " + kv[0]);
            }
        }
        if (interval < 1) throw new IllegalArgumentException("INTERVAL must be >= 1");
        if (count == 0 && until == null) throw new IllegalArgumentException("need COUNT or UNTIL (never expand forever)");
        return new Recurrence(weekly, interval, count, until);
    }

    public List<TimeSlot> expand(TimeSlot first, ZoneId zone) {
        ZonedDateTime s0 = first.start().atZone(zone), e0 = first.end().atZone(zone);
        List<TimeSlot> out = new ArrayList<>();
        for (int i = 0; i < 1000 && (count == 0 || i < count); i++) {
            long step = (long) i * interval;
            ZonedDateTime s = weekly ? s0.plusWeeks(step) : s0.plusDays(step);
            ZonedDateTime e = weekly ? e0.plusWeeks(step) : e0.plusDays(step);
            if (until != null && s.toLocalDate().isAfter(until)) break;
            TimeSlot slot = new TimeSlot(s.toInstant(), e.toInstant());
            if (!out.isEmpty() && out.get(out.size() - 1).overlaps(slot))
                throw new IllegalArgumentException("occurrences overlap each other: meeting longer than the repeat period");
            out.add(slot);
        }
        return out;
    }
}
