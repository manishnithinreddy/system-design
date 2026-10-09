package wallet;

/**
 * What the ATM needs from the card-issuing bank. In real life these are network messages
 * (via a "switch", L6), so any call can time out with an UNKNOWN outcome.
 * Every money call carries a unique reference so it can be retried or reversed safely.
 */
interface BankService {
    enum PinResult { OK, WRONG, CARD_BLOCKED }

    enum DebitResult { APPROVED, INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, CARD_BLOCKED, ALREADY_REVERSED }

    PinResult verifyPin(String card, String pin);

    long balance(String card);

    /** Idempotent per ref: the same ref twice debits once and returns the first answer. */
    DebitResult debit(String card, long amountPaise, String ref) throws BankTimeoutException;

    /** Undo the debit with this ref. Idempotent; a reversal for a debit the bank never saw is remembered. */
    void reverse(String ref) throws BankTimeoutException;

    /** "No answer in time": the bank may or may not have applied the request. */
    final class BankTimeoutException extends Exception {
        BankTimeoutException(String msg) { super(msg); }
    }
}
