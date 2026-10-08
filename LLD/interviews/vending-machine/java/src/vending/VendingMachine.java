package vending;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The controller. Every customer event goes through handle(): take the lock, let the current State decide,
 * write an audit entry. If the event started a sale, the slow motor runs AFTER the lock is released while
 * the machine sits in Dispensing (which refuses everything), then the sale is finished under the lock.
 * Money is long paise everywhere.
 */
public final class VendingMachine {
    public static final long CASH_TIMEOUT_MILLIS = 60_000;
    public static final long UPI_TIMEOUT_MILLIS = 120_000;

    public record AuditEntry(long atMillis, String from, String to, String event, String result) {
        @Override public String toString() {
            String states = from.equals(to) ? from : from + " -> " + to;
            return String.format("t=%4ds  %-26s %-28s %s", atMillis / 1000, states, event, result);
        }
    }

    enum UpiStatus { PENDING, PAID, FAILED, ABANDONED, REFUNDED }

    static final class UpiOrder {
        final String key;
        final String slot;
        final long amountPaise;
        UpiStatus status = UpiStatus.PENDING;

        UpiOrder(String key, String slot, long amountPaise) {
            this.key = key;
            this.slot = slot;
            this.amountPaise = amountPaise;
        }
    }

    /** Everything decided BEFORE the motor runs: what, how it was paid, and which coins go back as change. */
    record Sale(String slot, Product product, Payment payment, Map<Denomination, Integer> change) {}

    private final Object lock = new Object();
    private final String machineId;
    private final Dispenser dispenser;
    private final TimeSource clock;
    private final Inventory inventory = new Inventory();
    private final CoinBox box = new CoinBox();
    private final EnumMap<Denomination, Integer> escrow = new EnumMap<>(Denomination.class);
    private final Map<String, UpiOrder> upiOrders = new HashMap<>();
    private final List<Denomination> coinTray = new ArrayList<>();
    private final List<Product> productTray = new ArrayList<>();
    private final List<String> upiRefunds = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private State state = State.MAINTENANCE;       // a new machine starts with the door open
    private long lastActivityMillis;
    private UpiOrder currentUpi;
    private Sale saleForMotor;
    private int nextOrderNo = 1;
    private long cashRevenuePaise;
    private long upiRevenuePaise;

    public VendingMachine(String machineId, Dispenser dispenser, TimeSource clock) {
        this.machineId = machineId;
        this.dispenser = dispenser;
        this.clock = clock;
        this.lastActivityMillis = clock.nowMillis();
    }

    // ================= customer events (never throw; return what the display shows) =================

    public String insert(Denomination d) { return handle("insert " + d.label(), s -> s.insert(this, d)); }

    public String select(String slot) { return handle("select " + slot, s -> s.select(this, slot)); }

    public String payByUpi(String slot) { return handle("pay by UPI " + slot, s -> s.payByUpi(this, slot)); }

    public String cancel() { return handle("cancel", s -> s.cancel(this)); }

    public String tick() { return handle("tick", s -> s.tick(this)); }

    /** The bank's callback. May arrive twice, late, or after the customer left: all must be safe. */
    public String onUpiResult(String key, boolean success) {
        return handle("UPI " + (success ? "SUCCESS " : "FAILED ") + key, s -> {
            UpiOrder o = upiOrders.get(key);
            if (o == null) return "Unknown order: ignored";
            return switch (o.status) {
                case PENDING -> s.upiResult(this, o, success);
                case PAID, REFUNDED -> "Duplicate callback (" + o.status + "): ignored";
                case FAILED, ABANDONED -> success ? refundLateUpi(o) : "Duplicate callback (" + o.status + "): ignored";
            };
        });
    }

    // ================= technician operations (throw if the door isn't open) =================

    public String enterMaintenance() { return handle("enter maintenance", s -> s.enterMaintenance(this)); }

    public String exitMaintenance() { return handle("exit maintenance", s -> s.exitMaintenance(this)); }

    public void restock(String slot, Product product, int count) {
        synchronized (lock) {
            requireMaintenance();
            inventory.restock(slot, product, count);
            log(state, "restock " + slot, count + " x " + product);
        }
    }

    public void loadCoins(Denomination coin, int count) {
        synchronized (lock) {
            requireMaintenance();
            if (!coin.isCoin()) throw new IllegalArgumentException("only coins go in the change tubes");
            box.add(coin, count);
            log(state, "load coins", count + " x " + coin.label());
        }
    }

    /** Empty the note stacker. Coins stay: they are the change float. */
    public Map<Denomination, Integer> collectCash() {
        synchronized (lock) {
            requireMaintenance();
            EnumMap<Denomination, Integer> notes = new EnumMap<>(Denomination.class);
            box.snapshot().forEach((d, n) -> { if (!d.isCoin() && n > 0) notes.put(d, n); });
            box.removeAll(notes);
            log(state, "collect cash", Denomination.rupees(CoinBox.total(notes)) + " in notes");
            return notes;
        }
    }

    // ================= read-only views =================

    public String stateName() { synchronized (lock) { return state.name(); } }

    public long balancePaise() { synchronized (lock) { return CoinBox.total(escrow); } }

    public int stock(String slot) { synchronized (lock) { return inventory.count(slot); } }

    public Map<Denomination, Integer> coinBox() { synchronized (lock) { return box.snapshot(); } }

    public long cashRevenuePaise() { synchronized (lock) { return cashRevenuePaise; } }

    public long upiRevenuePaise() { synchronized (lock) { return upiRevenuePaise; } }

    public List<String> upiRefunds() { synchronized (lock) { return List.copyOf(upiRefunds); } }

    public Optional<String> currentUpiKey() { synchronized (lock) { return Optional.ofNullable(currentUpi).map(o -> o.key); } }

    public List<AuditEntry> auditLog() { synchronized (lock) { return List.copyOf(audit); } }

    /** What the customer picks up from the coin-return cup (and empties it). */
    public List<Denomination> takeCoins() { synchronized (lock) { var out = List.copyOf(coinTray); coinTray.clear(); return out; } }

    /** What the customer picks up from the delivery flap (and empties it). */
    public List<Product> takeProducts() { synchronized (lock) { var out = List.copyOf(productTray); productTray.clear(); return out; } }

    /** The "EXACT CHANGE ONLY" light, shown BEFORE anyone pays: can we return every amount from ₹1 to ₹10? */
    public boolean exactChangeOnly() {
        synchronized (lock) {
            for (int rupee = 1; rupee <= 10; rupee++) if (ChangeMaker.exact(rupee * 100L, box.coins()).isEmpty()) return true;
            return false;
        }
    }

    // ================= the one entry point for events =================

    private String handle(String event, Function<State, String> action) {
        Sale sale;
        String msg;
        synchronized (lock) {
            State from = state;
            msg = action.apply(state);
            if (!msg.isEmpty()) log(from, event, msg);
            sale = saleForMotor;
            saleForMotor = null;                    // only the caller that started the sale runs the motor
        }
        if (sale == null) return msg;
        boolean dropped;
        try {
            dropped = dispenser.dispense(sale.slot(), sale.product());   // slow: no lock held
        } catch (RuntimeException e) {
            dropped = false;                                              // a motor fault = "nothing fell"
        }
        synchronized (lock) {
            State from = state;
            String done = finishSale(sale, dropped);
            log(from, "drop sensor " + sale.slot() + (dropped ? ": item seen" : ": NOTHING seen"), done);
            return done;
        }
    }

    // ================= operations the states call (lock is held) =================

    String acceptCash(Denomination d) {
        long balance = CoinBox.total(escrow) + d.paise();
        EnumMap<Denomination, Integer> pool = changePool();
        if (d.isCoin()) pool.merge(d, 1, Integer::sum);
        boolean deadEnd = !inventory.sellableProducts().isEmpty();
        for (Product p : inventory.sellableProducts()) {
            if (p.pricePaise() > balance || ChangeMaker.exact(balance - p.pricePaise(), pool).isPresent()) {
                deadEnd = false;                    // the customer can still buy this one (now or after adding money)
                break;
            }
        }
        if (deadEnd) return bounce(d, "EXACT CHANGE ONLY: no change possible for " + Denomination.rupees(balance) + ", " + d.label() + " returned");
        escrow.merge(d, 1, Integer::sum);
        lastActivityMillis = clock.nowMillis();
        state = State.HAS_MONEY;
        return "Balance " + Denomination.rupees(balance);
    }

    String priceCheck(String slot) {
        String problem = unavailable(slot);
        return problem != null ? problem : inventory.product(slot) + ": insert money or pay by UPI";
    }

    String sellForCash(String slot) {
        String problem = unavailable(slot);
        if (problem != null) return problem + ": choose another or cancel";
        Product p = inventory.product(slot);
        long balance = CoinBox.total(escrow);
        if (balance < p.pricePaise()) return "Insert " + Denomination.rupees(p.pricePaise() - balance) + " more";
        Optional<Map<Denomination, Integer>> change = ChangeMaker.exact(balance - p.pricePaise(), changePool());
        if (change.isEmpty())
            return "Cannot return " + Denomination.rupees(balance - p.pricePaise()) + " change: choose another, add exact money, or cancel";
        return startSale(new Sale(slot, p, new Payment.Cash(escrow), change.get()), "Dispensing " + p.name());
    }

    String startUpi(String slot) {
        String problem = unavailable(slot);
        if (problem != null) return problem;
        Product p = inventory.product(slot);
        UpiOrder o = new UpiOrder(machineId + "-" + nextOrderNo++, slot, p.pricePaise());
        upiOrders.put(o.key, o);
        currentUpi = o;
        lastActivityMillis = clock.nowMillis();
        state = State.AWAITING_UPI;
        return "Scan QR to pay " + Denomination.rupees(o.amountPaise) + " (order " + o.key + ")";
    }

    String upiPaid(UpiOrder o) {
        o.status = UpiStatus.PAID;
        currentUpi = null;
        return startSale(new Sale(o.slot, inventory.product(o.slot), new Payment.Upi(o.key, o.amountPaise), Map.of()),
                "Paid, dispensing " + inventory.product(o.slot).name());
    }

    String upiFailed(UpiOrder o) {
        o.status = UpiStatus.FAILED;
        currentUpi = null;
        state = idleOrSoldOut();
        return "UPI payment failed: nothing charged";
    }

    String abandonUpi(String reason) {
        currentUpi.status = UpiStatus.ABANDONED;
        String key = currentUpi.key;
        currentUpi = null;
        state = idleOrSoldOut();
        return reason + ": order " + key + " closed; a late payment will be refunded automatically";
    }

    String refundEscrow(String reason) {
        String what = Denomination.rupees(CoinBox.total(escrow)) + " (" + CoinBox.describe(escrow) + ")";
        returnEscrow();
        state = idleOrSoldOut();
        return reason + ": returned " + what;
    }

    String bounce(Denomination d, String msg) {
        coinTray.add(d);
        return msg;
    }

    String moveTo(State next, String msg) {
        state = next;
        return msg;
    }

    State idleOrSoldOut() { return inventory.anySellable() ? State.IDLE : State.SOLD_OUT; }

    long idleMillis() { return clock.nowMillis() - lastActivityMillis; }

    // ================= internals =================

    private String startSale(Sale sale, String msg) {
        saleForMotor = sale;
        state = State.DISPENSING;
        return msg;
    }

    /** After the motor: commit on a drop, refund on a jam. Money moves only now, once we know. */
    private String finishSale(Sale sale, boolean dropped) {
        String result;
        if (dropped) {
            inventory.take(sale.slot());
            productTray.add(sale.product());
            switch (sale.payment()) {
                case Payment.Cash cash -> {
                    box.addAll(cash.inserted());           // escrow -> box
                    box.removeAll(sale.change());          // change out of the box (planned before the motor ran)
                    sale.change().forEach((d, n) -> { for (int i = 0; i < n; i++) coinTray.add(d); });
                    escrow.clear();
                    cashRevenuePaise += sale.product().pricePaise();
                }
                case Payment.Upi upi -> upiRevenuePaise += upi.amountPaise();
            }
            result = "Take your " + sale.product().name()
                    + (sale.change().isEmpty() ? "" : ", change " + Denomination.rupees(CoinBox.total(sale.change()))
                    + " (" + CoinBox.describe(sale.change()) + ")");
        } else {
            inventory.markJammed(sale.slot());             // item still physically there: stop selling from this slot
            result = "Jam in " + sale.slot() + ": " + refund(sale.payment()) + "; slot out of use";
        }
        state = idleOrSoldOut();
        return result;
    }

    private String refund(Payment payment) {
        return switch (payment) {
            case Payment.Cash cash -> {
                returnEscrow();
                yield "returned " + Denomination.rupees(cash.amountPaise()) + " (" + CoinBox.describe(cash.inserted()) + ")";
            }
            case Payment.Upi upi -> {
                upiOrders.get(upi.idempotencyKey()).status = UpiStatus.REFUNDED;
                upiRefunds.add(upi.idempotencyKey());
                yield "refunded " + Denomination.rupees(upi.amountPaise()) + " to UPI";
            }
        };
    }

    private String refundLateUpi(UpiOrder o) {
        o.status = UpiStatus.REFUNDED;
        upiRefunds.add(o.key);
        return "Late payment for closed order: refunded " + Denomination.rupees(o.amountPaise) + " to UPI";
    }

    private void returnEscrow() {
        escrow.forEach((d, n) -> { for (int i = 0; i < n; i++) coinTray.add(d); });
        escrow.clear();
    }

    /** Coins we may pay change from: the tubes plus coins inserted in this session (still in escrow). */
    private EnumMap<Denomination, Integer> changePool() {
        EnumMap<Denomination, Integer> pool = box.coins();
        escrow.forEach((d, n) -> { if (d.isCoin()) pool.merge(d, n, Integer::sum); });
        return pool;
    }

    private String unavailable(String slot) {
        Product p = inventory.product(slot);
        if (p == null) return "No slot " + slot;
        return inventory.sellable(slot) ? null : p.name() + " sold out";
    }

    private void requireMaintenance() {
        if (state != State.MAINTENANCE) throw new IllegalStateException("technician operations need maintenance mode, state is " + state.name());
    }

    private void log(State from, String event, String result) {
        audit.add(new AuditEntry(clock.nowMillis(), from.name(), state.name(), event, result));
    }
}
