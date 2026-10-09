package wallet;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * A fake issuing bank for the ATM. One card = one account (a simplification).
 * Tests can make the next debit "time out" before or after it is applied, the two cases that matter.
 */
final class InMemoryBank implements BankService {
    enum Fault { NONE, TIMEOUT_BEFORE_APPLY, TIMEOUT_AFTER_APPLY }

    static final int MAX_PIN_TRIES = 3;

    private static final class Card {
        final String pin;
        long balancePaise;
        int wrongTries;
        boolean blocked;
        LocalDate day;
        long withdrawnTodayPaise;
        Card(String pin, long balance) { this.pin = pin; this.balancePaise = balance; }
    }

    /** What happened to one ref. reversed=true with result=null means "reversal arrived first". */
    private static final class Debit {
        final String card; final long amount; final DebitResult result; final LocalDate day;
        boolean reversed;
        Debit(String card, long amount, DebitResult result, LocalDate day) {
            this.card = card; this.amount = amount; this.result = result; this.day = day;
        }
    }

    private final Map<String, Card> cards = new HashMap<>();
    private final Map<String, Debit> debits = new HashMap<>();
    private final Clock clock;
    private final long dailyLimitPaise;
    private Fault nextDebitFault = Fault.NONE;
    private boolean failNextReverse;

    InMemoryBank(Clock clock, long dailyLimitPaise) { this.clock = clock; this.dailyLimitPaise = dailyLimitPaise; }

    synchronized void openCard(String card, String pin, long balancePaise) { cards.put(card, new Card(pin, balancePaise)); }

    synchronized void failNextDebit(Fault f) { nextDebitFault = f; }

    synchronized void failNextReverse() { failNextReverse = true; }

    @Override public synchronized PinResult verifyPin(String cardNo, String pin) {
        Card c = card(cardNo);
        if (c.blocked) return PinResult.CARD_BLOCKED;
        if (c.pin.equals(pin)) { c.wrongTries = 0; return PinResult.OK; }
        if (++c.wrongTries >= MAX_PIN_TRIES) { c.blocked = true; return PinResult.CARD_BLOCKED; }
        return PinResult.WRONG;
    }

    @Override public synchronized long balance(String cardNo) { return card(cardNo).balancePaise; }

    @Override public synchronized DebitResult debit(String cardNo, long amount, String ref) throws BankTimeoutException {
        Fault fault = nextDebitFault;
        nextDebitFault = Fault.NONE;
        if (fault == Fault.TIMEOUT_BEFORE_APPLY) throw new BankTimeoutException("request lost on the way to the bank");
        Debit seen = debits.get(ref);
        if (seen != null) return seen.result == null ? DebitResult.ALREADY_REVERSED : seen.result;  // retry: same answer
        Card c = card(cardNo);
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(c.day)) { c.day = today; c.withdrawnTodayPaise = 0; }
        DebitResult r;
        if (c.blocked) r = DebitResult.CARD_BLOCKED;
        else if (c.withdrawnTodayPaise + amount > dailyLimitPaise) r = DebitResult.DAILY_LIMIT_EXCEEDED;
        else if (c.balancePaise < amount) r = DebitResult.INSUFFICIENT_FUNDS;
        else r = DebitResult.APPROVED;
        if (r == DebitResult.APPROVED) { c.balancePaise -= amount; c.withdrawnTodayPaise += amount; }
        debits.put(ref, new Debit(cardNo, amount, r, today));
        if (fault == Fault.TIMEOUT_AFTER_APPLY) throw new BankTimeoutException("bank applied it, but the reply was lost");
        return r;
    }

    @Override public synchronized void reverse(String ref) throws BankTimeoutException {
        if (failNextReverse) { failNextReverse = false; throw new BankTimeoutException("reversal not acknowledged"); }
        Debit d = debits.get(ref);
        if (d == null) {                                  // the debit never arrived: remember, so a late one is refused
            Debit marker = new Debit(null, 0, null, null);
            marker.reversed = true;
            debits.put(ref, marker);
            return;
        }
        if (d.reversed || d.result != DebitResult.APPROVED) return;
        Card c = card(d.card);
        c.balancePaise += d.amount;
        if (d.day.equals(c.day)) c.withdrawnTodayPaise -= d.amount;
        d.reversed = true;
    }

    private Card card(String cardNo) {
        Card c = cards.get(cardNo);
        if (c == null) throw new IllegalArgumentException("unknown card " + cardNo);
        return c;
    }
}
