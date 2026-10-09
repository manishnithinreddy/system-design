package wallet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which notes to dispense for an amount, from cassettes that hold a LIMITED number of each note.
 * Keys are note values in paise (50000 = ₹500), values are counts.
 */
final class NotePlanner {
    /** Most ATMs cap one withdrawal at a fixed number of notes (40 is common; it varies by machine). */
    static final int MAX_NOTES = 40;

    private NotePlanner() {}

    /** Greedy first (fast, usually right); exact search only when greedy fails. */
    static Optional<Map<Long, Integer>> plan(long amountPaise, Map<Long, Integer> available) {
        Optional<Map<Long, Integer>> g = greedy(amountPaise, available);
        if (g.isPresent() && notes(g.get()) <= MAX_NOTES) return g;
        return exact(amountPaise, available);
    }

    /** Biggest note first. Fails on ₹600 from {₹500 x1, ₹200 x3, ₹100 x0}: takes the 500, then is stuck on 100. */
    static Optional<Map<Long, Integer>> greedy(long amountPaise, Map<Long, Integer> available) {
        Map<Long, Integer> out = new TreeMap<>(Comparator.reverseOrder());
        long left = amountPaise;
        for (long note : largestFirst(available)) {
            int take = (int) Math.min(available.get(note), left / note);
            if (take > 0) { out.put(note, take); left -= take * note; }
        }
        return left == 0 ? Optional.of(out) : Optional.empty();
    }

    /**
     * Tries every count of each note (largest first) and keeps the plan with the fewest notes.
     * Bounded: per note at most min(count, amount / note) choices, and branches that already use
     * as many notes as the best plan (or MAX_NOTES) are cut. With 3 note types and an ATM-sized amount
     * that is at most a few thousand steps.
     */
    static Optional<Map<Long, Integer>> exact(long amountPaise, Map<Long, Integer> available) {
        List<Long> notes = largestFirst(available);
        int[][] best = {null};
        search(notes, available, 0, amountPaise, 0, new int[notes.size()], best);
        if (best[0] == null) return Optional.empty();
        Map<Long, Integer> out = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 0; i < notes.size(); i++) if (best[0][i] > 0) out.put(notes.get(i), best[0][i]);
        return Optional.of(out);
    }

    private static void search(List<Long> notes, Map<Long, Integer> avail, int i, long left, int used,
                               int[] counts, int[][] best) {
        if (used > MAX_NOTES) return;
        if (left == 0) {
            if (best[0] == null || used < sum(best[0])) best[0] = counts.clone();
            return;
        }
        if (i == notes.size()) return;
        if (best[0] != null && used >= sum(best[0])) return;           // can't beat what we have
        long note = notes.get(i);
        int max = (int) Math.min(avail.get(note), left / note);
        for (int k = max; k >= 0; k--) {
            counts[i] = k;
            search(notes, avail, i + 1, left - k * note, used + k, counts, best);
        }
        counts[i] = 0;
    }

    static int notes(Map<Long, Integer> plan) { return plan.values().stream().mapToInt(Integer::intValue).sum(); }

    private static int sum(int[] a) { int s = 0; for (int x : a) s += x; return s; }

    private static List<Long> largestFirst(Map<Long, Integer> available) {
        List<Long> list = new ArrayList<>();
        available.forEach((note, n) -> { if (n > 0) list.add(note); });
        list.sort(Comparator.reverseOrder());
        return list;
    }
}
