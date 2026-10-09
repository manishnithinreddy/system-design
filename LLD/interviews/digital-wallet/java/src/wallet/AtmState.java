package wallet;

import java.util.Map;
import java.util.Optional;

/**
 * State pattern: one small immutable class per ATM state. Each handles the events that make sense there;
 * everything else falls through to the default "not now" answer, so an impossible step (withdraw before PIN)
 * is impossible by construction instead of by a forgotten if.
 */
sealed interface AtmState permits AtmState.Idle, AtmState.CardInserted, AtmState.Authenticated,
        AtmState.Dispensing, AtmState.OutOfService {

    default String insertCard(Atm atm, String card) { return reject("insert card"); }
    default String enterPin(Atm atm, String pin) { return reject("enter PIN"); }
    default String balance(Atm atm) { return reject("balance enquiry"); }
    default String withdraw(Atm atm, long amountPaise) { return reject("withdraw"); }
    default String cancel(Atm atm) { return reject("cancel"); }

    private String reject(String what) { return "Not available now (" + getClass().getSimpleName() + "): " + what; }

    record Idle() implements AtmState {
        @Override public String insertCard(Atm atm, String card) {
            atm.moveTo(new CardInserted(card));
            return "Enter PIN";
        }
    }

    record CardInserted(String card) implements AtmState {
        @Override public String enterPin(Atm atm, String pin) {
            return switch (atm.bank().verifyPin(card, pin)) {
                case OK -> { atm.moveTo(new Authenticated(card)); yield "PIN OK. Choose: balance or withdrawal"; }
                case WRONG -> "Wrong PIN. Try again";
                case CARD_BLOCKED -> {
                    atm.retainCard(card);
                    atm.moveTo(new Idle());
                    yield "Card blocked after " + InMemoryBank.MAX_PIN_TRIES + " wrong PINs. Card retained, contact your bank";
                }
            };
        }
        @Override public String cancel(Atm atm) { atm.moveTo(new Idle()); return "Card returned"; }
    }

    record Authenticated(String card) implements AtmState {
        @Override public String balance(Atm atm) {
            return "Available balance: " + Money.format(atm.bank().balance(card));
        }

        /** Order matters: plan notes -> debit at the bank -> dispense -> on any failure, reverse the debit. */
        @Override public String withdraw(Atm atm, long amount) {
            if (amount <= 0 || amount % Money.rupees(100) != 0) return "Enter a multiple of ₹100";
            Optional<Map<Long, Integer>> plan = atm.dispenser().plan(amount);
            if (plan.isEmpty()) return "Cannot dispense " + Money.format(amount) + " with the notes in this ATM. Try another amount";

            String ref = atm.nextRef();
            BankService.DebitResult result;
            try {
                result = atm.bank().debit(card, amount, ref);
            } catch (BankService.BankTimeoutException e) {
                // Unknown outcome: maybe debited, maybe not. Never dispense on "maybe"; send a reversal.
                boolean reversed = atm.tryReverse(ref);
                atm.record(ref, card, amount, reversed ? Atm.Outcome.TIMEOUT_REVERSED : Atm.Outcome.REVERSAL_PENDING);
                atm.moveTo(new Idle());
                return "Transaction failed (no reply from bank). Any amount debited will be reversed. Card returned";
            }
            if (result != BankService.DebitResult.APPROVED) {
                atm.record(ref, card, amount, Atm.Outcome.DECLINED);
                atm.moveTo(new Idle());
                return "Declined: " + result + ". Card returned";
            }

            atm.moveTo(new Dispensing(card, ref));
            if (!atm.dispenser().dispense(plan.get())) {
                boolean reversed = atm.tryReverse(ref);
                atm.record(ref, card, amount, reversed ? Atm.Outcome.DISPENSE_FAILED_REVERSED : Atm.Outcome.REVERSAL_PENDING);
                atm.moveTo(new OutOfService("dispenser fault on " + ref));
                return "Unable to dispense cash. Your account will be credited back. Card returned";
            }
            atm.record(ref, card, amount, Atm.Outcome.DISPENSED);
            atm.moveTo(atm.dispenser().isEmpty() ? new OutOfService("out of cash") : new Idle());
            return "Please take your cash: " + describe(plan.get()) + "\n" + atm.receipt(ref, card, amount);
        }

        @Override public String cancel(Atm atm) { atm.moveTo(new Idle()); return "Card returned"; }
    }

    /** Notes are moving. Every customer event is refused until the hardware answers. */
    record Dispensing(String card, String ref) implements AtmState {}

    record OutOfService(String reason) implements AtmState {
        @Override public String insertCard(Atm atm, String card) { return "Out of service: " + reason; }
    }

    static String describe(Map<Long, Integer> plan) {
        StringBuilder b = new StringBuilder();
        plan.forEach((note, n) -> b.append(b.isEmpty() ? "" : " + ").append(n).append(" x ").append(Money.format(note)));
        return b.toString();
    }
}
