package splitwise;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One group's members and append-only ledger. All methods are synchronized on this group:
 * two people adding expenses to DIFFERENT groups never block each other.
 */
final class GroupLedger {
    private final String groupId;
    private final Set<String> members;
    private final Clock clock;
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Map<String, LedgerEntry.Expense> expensesById = new HashMap<>();
    private final Map<String, IdempotentRequest> requests = new HashMap<>();
    private long nextId = 1;

    private record IdempotentRequest(String paidBy, long total, SplitSpec spec, LedgerEntry.Expense result) {}

    GroupLedger(String groupId, Set<String> members, Clock clock) {
        if (members.size() < 2) throw new IllegalArgumentException("a group needs at least 2 members");
        this.groupId = groupId;
        this.members = Set.copyOf(members);
        this.clock = clock;
    }

    synchronized LedgerEntry.Expense addExpense(String requestId, String paidBy, long totalPaise,
                                                SplitSpec spec, String description) {
        Objects.requireNonNull(requestId, "requestId");
        IdempotentRequest previous = requests.get(requestId);
        if (previous != null) {
            // Same request retried (e.g. the app timed out and resent): return the original result.
            if (!previous.paidBy().equals(paidBy) || previous.total() != totalPaise || !previous.spec().equals(spec)) {
                throw new IllegalStateException("requestId " + requestId + " reused for a different expense");
            }
            return previous.result();
        }
        requireMember(paidBy);
        SortedMap<String, Long> owed = Splitter.split(totalPaise, spec);
        owed.keySet().forEach(this::requireMember);

        var expense = new LedgerEntry.Expense(groupId + "-e" + nextId++, clock.instant(),
                paidBy, totalPaise, owed, description);
        entries.add(expense);
        expensesById.put(expense.id(), expense);
        requests.put(requestId, new IdempotentRequest(paidBy, totalPaise, spec, expense));
        return expense;
    }

    synchronized LedgerEntry.Reversal deleteExpense(String expenseId) {
        if (!expensesById.containsKey(expenseId)) throw new IllegalArgumentException("unknown expense " + expenseId);
        boolean alreadyReversed = entries.stream().anyMatch(e ->
                e instanceof LedgerEntry.Reversal r && r.reversedExpenseId().equals(expenseId));
        if (alreadyReversed) throw new IllegalStateException("expense " + expenseId + " already deleted");
        var reversal = new LedgerEntry.Reversal(groupId + "-r" + nextId++, clock.instant(), expenseId);
        entries.add(reversal);
        return reversal;
    }

    synchronized LedgerEntry.Settlement settle(String from, String to, long amountPaise) {
        requireMember(from);
        requireMember(to);
        if (from.equals(to) || amountPaise <= 0) throw new IllegalArgumentException("invalid settlement");
        var s = new LedgerEntry.Settlement(groupId + "-s" + nextId++, clock.instant(), from, to, amountPaise);
        entries.add(s);
        return s;
    }

    /** net > 0: the group owes this person; net < 0: this person owes the group. Always sums to 0. */
    synchronized SortedMap<String, Long> balances() {
        SortedMap<String, Long> net = new TreeMap<>();
        members.forEach(m -> net.put(m, 0L));
        for (LedgerEntry e : entries) {
            switch (e) {
                case LedgerEntry.Expense x -> apply(net, x, +1);
                case LedgerEntry.Settlement s -> {
                    net.merge(s.from(), s.amountPaise(), Long::sum);   // paying back reduces what you owe
                    net.merge(s.to(), -s.amountPaise(), Long::sum);
                }
                case LedgerEntry.Reversal r -> apply(net, expensesById.get(r.reversedExpenseId()), -1);
            }
        }
        return net;
    }

    synchronized List<LedgerEntry> history() {
        return List.copyOf(entries);
    }

    private static void apply(Map<String, Long> net, LedgerEntry.Expense x, int sign) {
        net.merge(x.paidBy(), sign * x.totalPaise(), Long::sum);
        x.owedPaise().forEach((user, owed) -> net.merge(user, -sign * owed, Long::sum));
    }

    private void requireMember(String user) {
        if (!members.contains(user)) throw new IllegalArgumentException(user + " is not in group " + groupId);
    }
}
