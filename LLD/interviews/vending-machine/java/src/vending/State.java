package vending;

/**
 * State pattern: one class per machine state. Each class handles the events that make sense in that state;
 * everything else falls through to a default that refuses politely (and pushes a coin straight back out).
 * States hold no data (balance, current UPI order... live in VendingMachine), so each is a singleton.
 * The machine calls these while holding its lock: one event at a time.
 */
sealed interface State permits State.Idle, State.HasMoney, State.AwaitingUpi, State.Dispensing,
        State.SoldOut, State.Maintenance {

    State IDLE = new Idle(), HAS_MONEY = new HasMoney(), AWAITING_UPI = new AwaitingUpi(),
          DISPENSING = new Dispensing(), SOLD_OUT = new SoldOut(), MAINTENANCE = new Maintenance();

    default String name() { return getClass().getSimpleName(); }

    default String refusal() { return "Busy (" + name() + ")"; }

    default String insert(VendingMachine m, Denomination d) { return m.bounce(d, refusal() + ": " + d.label() + " returned"); }

    default String select(VendingMachine m, String slot) { return refusal(); }

    default String payByUpi(VendingMachine m, String slot) { return refusal(); }

    default String cancel(VendingMachine m) { return "Nothing to cancel"; }

    default String upiResult(VendingMachine m, VendingMachine.UpiOrder order, boolean success) {
        throw new IllegalStateException("a PENDING UPI order exists only in AwaitingUpi");   // a bug, not a user error
    }

    /** Called periodically (every second on real hardware). Returns "" when nothing happened. */
    default String tick(VendingMachine m) { return ""; }

    default String enterMaintenance(VendingMachine m) { return "Finish the current transaction first"; }

    default String exitMaintenance(VendingMachine m) { return "Not in maintenance"; }

    /** Waiting for a customer. Selecting first is allowed: it just shows the price (no state change). */
    final class Idle implements State {
        @Override public String insert(VendingMachine m, Denomination d) { return m.acceptCash(d); }
        @Override public String select(VendingMachine m, String slot) { return m.priceCheck(slot); }
        @Override public String payByUpi(VendingMachine m, String slot) { return m.startUpi(slot); }
        @Override public String enterMaintenance(VendingMachine m) { return m.moveTo(MAINTENANCE, "Maintenance mode"); }
    }

    /** Coins are in escrow (held, not yet ours). Every exit from here either sells or returns those exact coins. */
    final class HasMoney implements State {
        @Override public String insert(VendingMachine m, Denomination d) { return m.acceptCash(d); }
        @Override public String select(VendingMachine m, String slot) { return m.sellForCash(slot); }
        @Override public String payByUpi(VendingMachine m, String slot) { return "Cash inserted: buy with cash or cancel first"; }
        @Override public String cancel(VendingMachine m) { return m.refundEscrow("Cancelled"); }
        @Override public String tick(VendingMachine m) {
            return m.idleMillis() >= VendingMachine.CASH_TIMEOUT_MILLIS ? m.refundEscrow("No activity for 60 s") : "";
        }
    }

    /** A UPI QR is on screen for one order; the item is reserved. Waiting for the bank's callback. */
    final class AwaitingUpi implements State {
        @Override public String refusal() { return "Waiting for UPI payment (press cancel to stop)"; }
        @Override public String cancel(VendingMachine m) { return m.abandonUpi("Cancelled"); }
        @Override public String upiResult(VendingMachine m, VendingMachine.UpiOrder order, boolean success) {
            return success ? m.upiPaid(order) : m.upiFailed(order);
        }
        @Override public String tick(VendingMachine m) {
            return m.idleMillis() >= VendingMachine.UPI_TIMEOUT_MILLIS ? m.abandonUpi("UPI not confirmed in 120 s") : "";
        }
    }

    /** The motor is running (outside the lock). Every customer event is refused until the drop sensor reports. */
    final class Dispensing implements State {
        @Override public String refusal() { return "Busy: dispensing"; }
    }

    /** Nothing left to sell. Only a technician can change that. */
    final class SoldOut implements State {
        @Override public String refusal() { return "SOLD OUT"; }
        @Override public String enterMaintenance(VendingMachine m) { return m.moveTo(MAINTENANCE, "Maintenance mode"); }
    }

    /** Door open: restock, load coins, collect notes, clear jams. Customers are refused. */
    final class Maintenance implements State {
        @Override public String refusal() { return "Out of service"; }
        @Override public String exitMaintenance(VendingMachine m) { return m.moveTo(m.idleOrSoldOut(), "Back in service"); }
    }
}
