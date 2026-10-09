package wallet;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The cash module: cassettes of notes (₹500, ₹200, ₹100) and the motor that pushes them out. */
final class CashDispenser {
    /** The physical mechanism. Returns false on a jam or sensor fault (tests inject this). */
    interface Hardware { boolean pushOut(Map<Long, Integer> notes); }

    private final TreeMap<Long, Integer> cassettes = new TreeMap<>(Comparator.reverseOrder());
    private Hardware hardware = notes -> true;

    CashDispenser(Map<Long, Integer> initial) { cassettes.putAll(initial); }

    void setHardware(Hardware h) { this.hardware = h; }

    Optional<Map<Long, Integer>> plan(long amountPaise) { return NotePlanner.plan(amountPaise, cassettes); }

    /** Counts drop only after the hardware reports success. On a fault we assume notes stayed inside. */
    boolean dispense(Map<Long, Integer> plan) {
        plan.forEach((note, n) -> {
            if (cassettes.getOrDefault(note, 0) < n) throw new IllegalStateException("plan needs more notes than loaded");
        });
        if (!hardware.pushOut(plan)) return false;
        plan.forEach((note, n) -> cassettes.merge(note, -n, Integer::sum));
        return true;
    }

    void load(long notePaise, int count) { cassettes.merge(notePaise, count, Integer::sum); }

    boolean isEmpty() { return cassettes.values().stream().allMatch(n -> n == 0); }

    long totalPaise() {
        return cassettes.entrySet().stream().mapToLong(e -> e.getKey() * e.getValue()).sum();
    }

    Map<Long, Integer> counts() { return Map.copyOf(cassettes); }
}
