package splitwise;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;

/** Facade: what the app's API layer calls. Thread-safe. */
public final class ExpenseService {
    private final Map<String, GroupLedger> groups = new ConcurrentHashMap<>();
    private final Clock clock;

    public ExpenseService(Clock clock) {
        this.clock = clock;
    }

    public void createGroup(String groupId, Set<String> members) {
        if (groups.putIfAbsent(groupId, new GroupLedger(groupId, members, clock)) != null) {
            throw new IllegalStateException("group " + groupId + " already exists");
        }
    }

    /** {@code requestId} is chosen by the client; retries with the same id are safe. */
    public LedgerEntry.Expense addExpense(String groupId, String requestId, String paidBy, long totalPaise,
                                          SplitSpec spec, String description) {
        return group(groupId).addExpense(requestId, paidBy, totalPaise, spec, description);
    }

    public LedgerEntry.Reversal deleteExpense(String groupId, String expenseId) {
        return group(groupId).deleteExpense(expenseId);
    }

    public LedgerEntry.Settlement settleUp(String groupId, String from, String to, long amountPaise) {
        return group(groupId).settle(from, to, amountPaise);
    }

    public SortedMap<String, Long> balances(String groupId) {
        return group(groupId).balances();
    }

    public List<Transfer> simplifiedDebts(String groupId) {
        return DebtSimplifier.simplify(group(groupId).balances());
    }

    public List<LedgerEntry> history(String groupId) {
        return group(groupId).history();
    }

    private GroupLedger group(String id) {
        GroupLedger g = groups.get(id);
        if (g == null) throw new IllegalArgumentException("unknown group " + id);
        return g;
    }
}
