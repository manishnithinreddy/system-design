package wallet;

import java.time.Instant;

/**
 * Money reserved for a merchant but not yet paid (like a cab fare estimate or a hotel deposit).
 * The reserved amount sits in the SYS:HOLDS account until capture (pay the merchant) or void (give it back).
 * state is changed only while the HOLDS account lock is held.
 */
final class Hold {
    enum State { AUTHORIZED, CAPTURED, VOIDED, EXPIRED }

    final String holdTxnId;
    final String customer;
    final String merchant;
    final long amountPaise;
    final Instant expiresAt;
    volatile State state = State.AUTHORIZED;

    Hold(String holdTxnId, String customer, String merchant, long amountPaise, Instant expiresAt) {
        this.holdTxnId = holdTxnId; this.customer = customer; this.merchant = merchant;
        this.amountPaise = amountPaise; this.expiresAt = expiresAt;
    }
}
