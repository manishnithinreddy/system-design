package wallet;

import java.time.Instant;

/**
 * The business record of one request: what was asked, and whether it went through.
 * A REJECTED txn writes no ledger entries but is still remembered under its idempotency key,
 * so a retry gets the same "no" instead of a surprise "yes" later.
 */
record Txn(String id, String idempotencyKey, Type type, String from, String to, long amountPaise,
           Status status, String reason, String relatedTxnId, Instant at) {

    enum Type { TOP_UP, TRANSFER, REFUND, HOLD, CAPTURE, RELEASE }

    enum Status { COMPLETED, REJECTED }

    boolean ok() { return status == Status.COMPLETED; }
}
