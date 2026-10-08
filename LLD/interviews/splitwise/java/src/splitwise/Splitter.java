package splitwise;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Turns (total, SplitSpec) into how much each person owes, in paise.
 * Invariant: the parts ALWAYS add up exactly to the total.
 *
 * Largest remainder method: give everyone the rounded-DOWN share, then hand the leftover paise,
 * one each, to the people with the biggest fractional parts (ties: alphabetical user id).
 *   ₹100.00 / 3 = 3333.33 paise each -> 3333, 3333, 3333 (= 9999) -> 1 paisa left -> first by id gets it.
 */
public final class Splitter {
    private Splitter() {}

    public static SortedMap<String, Long> split(long totalPaise, SplitSpec spec) {
        if (totalPaise <= 0) throw new IllegalArgumentException("total must be positive");
        return switch (spec) {
            case SplitSpec.Equal e -> {
                if (e.userIds().isEmpty()) throw new IllegalArgumentException("no participants");
                Map<String, BigDecimal> w = new TreeMap<>();
                for (String u : e.userIds()) w.merge(u, BigDecimal.ONE, BigDecimal::add);
                if (w.size() != e.userIds().size()) throw new IllegalArgumentException("duplicate participant");
                yield largestRemainder(totalPaise, w);
            }
            case SplitSpec.Exact ex -> {
                long sum = 0;
                for (long p : ex.paise().values()) {
                    if (p < 0) throw new IllegalArgumentException("negative amount");
                    sum += p;
                }
                if (sum != totalPaise) {
                    throw new IllegalArgumentException("exact amounts add up to " + Money.format(sum)
                            + ", not " + Money.format(totalPaise));
                }
                yield new TreeMap<>(ex.paise());
            }
            case SplitSpec.Percent p -> {
                BigDecimal sum = p.percents().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
                    throw new IllegalArgumentException("percentages add up to " + sum + ", not 100");
                }
                if (p.percents().values().stream().anyMatch(v -> v.signum() < 0)) {
                    throw new IllegalArgumentException("negative percentage");
                }
                yield largestRemainder(totalPaise, new TreeMap<>(p.percents()));
            }
            case SplitSpec.Shares s -> {
                Map<String, BigDecimal> w = new TreeMap<>();
                s.shares().forEach((u, n) -> {
                    if (n <= 0) throw new IllegalArgumentException("shares must be positive");
                    w.put(u, BigDecimal.valueOf(n));
                });
                yield largestRemainder(totalPaise, w);
            }
        };
    }

    static SortedMap<String, Long> largestRemainder(long total, Map<String, BigDecimal> weights) {
        if (weights.isEmpty()) throw new IllegalArgumentException("no participants");
        BigDecimal weightSum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        record Part(String user, long floor, BigDecimal fraction) {}

        List<Part> parts = new ArrayList<>();
        long assigned = 0;
        for (var e : new TreeMap<>(weights).entrySet()) {
            BigDecimal exact = BigDecimal.valueOf(total).multiply(e.getValue())
                    .divide(weightSum, 10, RoundingMode.HALF_EVEN);       // 10 decimals is plenty
            long floor = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            parts.add(new Part(e.getKey(), floor, exact.subtract(BigDecimal.valueOf(floor))));
            assigned += floor;
        }
        long leftover = total - assigned;                                  // always < number of people
        parts.sort(Comparator.comparing(Part::fraction).reversed().thenComparing(Part::user));

        SortedMap<String, Long> result = new TreeMap<>();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            result.put(p.user(), p.floor() + (i < leftover ? 1 : 0));
        }
        return result;
    }
}
