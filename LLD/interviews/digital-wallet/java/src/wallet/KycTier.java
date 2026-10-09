package wallet;

/**
 * Limits per KYC level (KYC = "know your customer": how much identity the user has proven).
 * The numbers are ILLUSTRATIVE, loosely shaped like RBI's prepaid-instrument rules (small-balance
 * minimum-KYC wallets, larger full-KYC ones). Check the current RBI Master Direction on PPIs before quoting them.
 */
enum KycTier {
    MIN_KYC(Money.rupees(5_000), Money.rupees(10_000), Money.rupees(10_000)),
    FULL_KYC(Money.rupees(1_00_000), Money.rupees(1_00_000), Money.rupees(2_00_000)),
    NONE(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);                   // merchants and system accounts

    final long perTxnPaise;      // biggest single payment or transfer out
    final long dailyOutPaise;    // total sent per calendar day (IST)
    final long maxBalancePaise;  // wallet can't hold more than this

    KycTier(long perTxn, long dailyOut, long maxBalance) {
        this.perTxnPaise = perTxn; this.dailyOutPaise = dailyOut; this.maxBalancePaise = maxBalance;
    }
}
