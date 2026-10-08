package splitwise;

import java.time.Instant;
import java.util.Map;

/**
 * Everything that ever happened in a group, as immutable entries. Balances are DERIVED from these;
 * nothing is ever updated or deleted. A deleted expense gets a Reversal entry instead.
 */
public sealed interface LedgerEntry permits LedgerEntry.Expense, LedgerEntry.Settlement, LedgerEntry.Reversal {
    String id();
    Instant at();

    record Expense(String id, Instant at, String paidBy, long totalPaise,
                   Map<String, Long> owedPaise, String description) implements LedgerEntry {
        public Expense { owedPaise = Map.copyOf(owedPaise); }
    }

    /** {@code from} paid {@code to} back, outside the app (cash, UPI...). */
    record Settlement(String id, Instant at, String from, String to, long amountPaise) implements LedgerEntry {}

    /** Cancels the effect of an earlier expense (delete / the first half of an edit). */
    record Reversal(String id, Instant at, String reversedExpenseId) implements LedgerEntry {}
}
