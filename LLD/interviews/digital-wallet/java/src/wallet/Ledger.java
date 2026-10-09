package wallet;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only list of entries. No update, no delete: a mistake is fixed by a NEW reversing transaction.
 * The lock here is always taken last (after account locks), so it can't take part in a deadlock.
 */
final class Ledger {
    private final List<LedgerEntry> entries = new ArrayList<>();

    /** All entries of one transaction go in together, and they must balance (double entry). */
    synchronized void append(List<LedgerEntry> batch) {
        long sum = batch.stream().mapToLong(LedgerEntry::amountPaise).sum();
        if (sum != 0) throw new IllegalStateException("unbalanced transaction: entries sum to " + sum);
        entries.addAll(batch);
    }

    synchronized List<LedgerEntry> forAccount(String accountId) {
        return entries.stream().filter(e -> e.accountId().equals(accountId)).toList();
    }

    synchronized List<LedgerEntry> forTxn(String txnId) {
        return entries.stream().filter(e -> e.txnId().equals(txnId)).toList();
    }

    /** The source of truth for a balance: add up the history. */
    synchronized long sumFor(String accountId) {
        return entries.stream().filter(e -> e.accountId().equals(accountId)).mapToLong(LedgerEntry::amountPaise).sum();
    }

    synchronized int size() { return entries.size(); }
}
