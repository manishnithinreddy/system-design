package wallet;

import java.time.Instant;

/**
 * One immutable line in the ledger. amountPaise is signed from the account's point of view:
 * + money came in, - money went out. Every transaction writes entries that add up to exactly 0.
 * balanceAfterPaise is stored so statements never need to re-add history.
 */
record LedgerEntry(String id, String txnId, String accountId, long amountPaise, long balanceAfterPaise, Instant at) {}
