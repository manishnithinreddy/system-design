package wallet;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ATM: holds the current state and the devices, and delegates every customer action to the state.
 * synchronized: one customer at a time, and an operator refill can't interleave with a withdrawal.
 */
final class Atm {
    enum Outcome { DISPENSED, DECLINED, DISPENSE_FAILED_REVERSED, TIMEOUT_REVERSED, REVERSAL_PENDING }

    /** One line of the ATM's electronic journal: what the machine believes happened. */
    record JournalEntry(String ref, String card, long amountPaise, Outcome outcome, Instant at) {}

    private final String id;
    private final CashDispenser dispenser;
    private final BankService bank;
    private final Clock clock;
    private final List<JournalEntry> journal = new ArrayList<>();
    private final List<String> retainedCards = new ArrayList<>();
    private final List<String> pendingReversals = new ArrayList<>();
    private AtmState state = new AtmState.Idle();
    private int seq;

    Atm(String id, CashDispenser dispenser, BankService bank, Clock clock) {
        this.id = id; this.dispenser = dispenser; this.bank = bank; this.clock = clock;
        if (dispenser.isEmpty()) state = new AtmState.OutOfService("out of cash");
    }

    synchronized String insertCard(String card) { return state.insertCard(this, card); }
    synchronized String enterPin(String pin) { return state.enterPin(this, pin); }
    synchronized String balance() { return state.balance(this); }
    synchronized String withdraw(long amountPaise) { return state.withdraw(this, amountPaise); }
    synchronized String cancel() { return state.cancel(this); }

    /** Operator visit: load notes, retry stuck reversals, and put the machine back in service. */
    synchronized void refill(long notePaise, int count) {
        dispenser.load(notePaise, count);
        pendingReversals.removeIf(this::tryReverseOnce);
        if (state instanceof AtmState.OutOfService) state = new AtmState.Idle();
    }

    synchronized AtmState state() { return state; }
    synchronized List<JournalEntry> journal() { return List.copyOf(journal); }
    synchronized List<String> retainedCards() { return List.copyOf(retainedCards); }
    synchronized List<String> pendingReversals() { return List.copyOf(pendingReversals); }

    // ---- used by the states (package-private) ----
    void moveTo(AtmState next) { state = next; }
    BankService bank() { return bank; }
    CashDispenser dispenser() { return dispenser; }
    String nextRef() { return id + "-" + String.format("%06d", ++seq); }
    void retainCard(String card) { retainedCards.add(card); }
    void record(String ref, String card, long amount, Outcome o) { journal.add(new JournalEntry(ref, card, amount, o, clock.instant())); }

    /** Try the reversal once now; if the bank doesn't answer, queue it so it is retried later (never forgotten). */
    boolean tryReverse(String ref) {
        if (tryReverseOnce(ref)) return true;
        pendingReversals.add(ref);
        return false;
    }

    private boolean tryReverseOnce(String ref) {
        try { bank.reverse(ref); return true; } catch (BankService.BankTimeoutException e) { return false; }
    }

    String receipt(String ref, String card, long amount) {
        return String.join("\n",
                "  ---------- " + id + " ----------",
                "  " + clock.instant().atZone(clock.getZone()).toLocalDateTime().withNano(0),
                "  CARD  XXXX" + card.substring(Math.max(0, card.length() - 4)),
                "  TXN   " + ref,
                "  WDL   " + Money.format(amount),
                "  BAL   " + Money.format(bank.balance(card)),
                "  ------------------------------");
    }
}
