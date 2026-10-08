package vending;

import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * All cash inside the machine: coin tubes (used for change) and the note stacker (never paid out).
 * Counts can never go negative: removeAll checks everything first, then removes (all or nothing).
 */
final class CoinBox {
    private final EnumMap<Denomination, Integer> counts = new EnumMap<>(Denomination.class);

    void add(Denomination d, int n) {
        if (n < 0) throw new IllegalArgumentException("negative count");
        if (n > 0) counts.merge(d, n, Integer::sum);
    }

    void addAll(Map<Denomination, Integer> m) { m.forEach(this::add); }

    void removeAll(Map<Denomination, Integer> m) {
        m.forEach((d, n) -> {
            if (n < 0 || count(d) < n) throw new IllegalStateException("not enough " + d.label() + " in the box");
        });
        m.forEach((d, n) -> counts.merge(d, -n, Integer::sum));
    }

    int count(Denomination d) { return counts.getOrDefault(d, 0); }

    /** Only the coins: what change can be made from. */
    EnumMap<Denomination, Integer> coins() {
        EnumMap<Denomination, Integer> out = new EnumMap<>(Denomination.class);
        counts.forEach((d, n) -> { if (d.isCoin() && n > 0) out.put(d, n); });
        return out;
    }

    EnumMap<Denomination, Integer> snapshot() { return copy(counts); }

    long totalPaise() { return total(counts); }

    // ---- small helpers for "bag of coins" maps ----

    static long total(Map<Denomination, Integer> m) {
        long sum = 0;
        for (var e : m.entrySet()) sum += e.getKey().paise() * e.getValue();
        return sum;
    }

    static EnumMap<Denomination, Integer> copy(Map<Denomination, Integer> m) {
        EnumMap<Denomination, Integer> out = new EnumMap<>(Denomination.class);   // new EnumMap<>(emptyHashMap) would throw
        out.putAll(m);
        return out;
    }

    /** {COIN_2=3} -> "3 x ₹2 coin". */
    static String describe(Map<Denomination, Integer> m) {
        StringJoiner j = new StringJoiner(" + ");
        copy(m).forEach((d, n) -> { if (n > 0) j.add(n + " x " + d.label()); });
        return j.length() == 0 ? "nothing" : j.toString();
    }
}
