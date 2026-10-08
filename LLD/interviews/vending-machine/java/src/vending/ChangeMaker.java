package vending;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which coins to pay out as change, given a LIMITED number of each coin.
 * greedy: biggest coin first. Fast, but can fail even when an answer exists (6 from {5 x1, 2 x3}).
 * exact:  bounded dynamic programming. Always finds an answer if one exists, using the fewest coins.
 */
final class ChangeMaker {
    private ChangeMaker() {}

    static Optional<Map<Denomination, Integer>> greedy(long amountPaise, Map<Denomination, Integer> available) {
        EnumMap<Denomination, Integer> out = new EnumMap<>(Denomination.class);
        long left = amountPaise;
        for (Denomination d : coinsLargestFirst(available)) {
            int take = (int) Math.min(available.get(d), left / d.paise());
            if (take > 0) { out.put(d, take); left -= take * d.paise(); }
        }
        return left == 0 ? Optional.of(out) : Optional.empty();
    }

    /**
     * best[a] = fewest coins that make amount a using the coin types seen so far, never more of a coin than
     * we have. One coin type at a time; take[i][a] remembers how many of type i we used to reach a.
     * Work in units of the smallest step (₹1 = 100 paise) so ₹100 change is a 101-entry table.
     * Time O(amount x total coins), memory O(types x amount).
     */
    static Optional<Map<Denomination, Integer>> exact(long amountPaise, Map<Denomination, Integer> available) {
        if (amountPaise < 0) throw new IllegalArgumentException("negative amount");
        List<Denomination> coins = coinsLargestFirst(available);
        EnumMap<Denomination, Integer> out = new EnumMap<>(Denomination.class);
        if (amountPaise == 0) return Optional.of(out);
        long unit = 0;
        for (Denomination d : coins) unit = gcd(unit, d.paise());
        if (unit == 0 || amountPaise % unit != 0) return Optional.empty();
        int target = (int) (amountPaise / unit);

        final int INF = Integer.MAX_VALUE;
        int[] best = new int[target + 1];
        Arrays.fill(best, INF);
        best[0] = 0;
        int[][] take = new int[coins.size()][target + 1];
        for (int i = 0; i < coins.size(); i++) {
            int value = (int) (coins.get(i).paise() / unit);
            int have = available.get(coins.get(i));
            int[] next = best.clone();                         // "use 0 of this coin" is always an option
            for (int a = 0; a <= target; a++) {
                if (best[a] == INF) continue;
                for (int k = 1; k <= have && a + k * value <= target; k++) {
                    if (best[a] + k < next[a + k * value]) {
                        next[a + k * value] = best[a] + k;
                        take[i][a + k * value] = k;
                    }
                }
            }
            best = next;
        }
        if (best[target] == INF) return Optional.empty();
        for (int i = coins.size() - 1, a = target; i >= 0; i--) {   // walk back: how many of each coin?
            int k = take[i][a];
            if (k > 0) out.put(coins.get(i), k);
            a -= k * (int) (coins.get(i).paise() / unit);
        }
        return Optional.of(out);
    }

    private static List<Denomination> coinsLargestFirst(Map<Denomination, Integer> available) {
        List<Denomination> list = new ArrayList<>();
        available.forEach((d, n) -> { if (d.isCoin() && n > 0) list.add(d); });
        list.sort((x, y) -> Long.compare(y.paise(), x.paise()));
        return list;
    }

    private static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }
}
