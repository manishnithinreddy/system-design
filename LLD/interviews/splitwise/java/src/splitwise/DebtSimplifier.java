package splitwise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * "Simplify debts": given each person's net balance (+ = is owed money, - = owes money),
 * produce a short list of payments that settles everyone.
 *
 * Greedy: repeatedly match the person owed the most with the person who owes the most,
 * transfer the smaller of the two amounts, and put the remainder back.
 * Every step zeroes at least one person, so there are at most (people - 1) transfers.
 * It's NOT always the true minimum (that problem is NP-hard), but it's fast and good in practice.
 */
public final class DebtSimplifier {
    private DebtSimplifier() {}

    private record Party(String user, long amount) {}

    public static List<Transfer> simplify(Map<String, Long> netBalances) {
        long sum = netBalances.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 0) throw new IllegalArgumentException("balances must sum to zero, got " + sum);

        Comparator<Party> biggestFirst = Comparator.comparingLong(Party::amount).reversed()
                .thenComparing(Party::user);                          // deterministic output
        PriorityQueue<Party> creditors = new PriorityQueue<>(biggestFirst);
        PriorityQueue<Party> debtors = new PriorityQueue<>(biggestFirst);
        netBalances.forEach((user, net) -> {
            if (net > 0) creditors.add(new Party(user, net));
            else if (net < 0) debtors.add(new Party(user, -net));
        });

        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty()) {
            Party c = creditors.poll(), d = debtors.poll();
            long x = Math.min(c.amount(), d.amount());
            transfers.add(new Transfer(d.user(), c.user(), x));
            if (c.amount() > x) creditors.add(new Party(c.user(), c.amount() - x));
            if (d.amount() > x) debtors.add(new Party(d.user(), d.amount() - x));
        }
        return transfers;
    }
}
