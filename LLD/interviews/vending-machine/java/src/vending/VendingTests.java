package vending;

import static vending.Denomination.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class VendingTests {
    private static int passed = 0;

    static final Product WATER = new Product("Water 1L", 2_000);
    static final Product CHIPS = new Product("Masala chips", 1_500);
    static final Product CHIKKI = new Product("Peanut chikki", 1_400);

    public static void main(String[] args) throws Exception {
        happyPathExactAmount();
        changeIsReturned();
        changeMakerGreedyVsExact();
        machineGivesChangeWhereGreedyFails();
        saleRefusedWhenChangeImpossibleThenCancelRefunds();
        deadEndNoteRejectedBeforeAccepting();
        cancelReturnsTheExactCoinsInserted();
        soldOutSlotAndSoldOutMachine();
        invalidEventsAreRefusedNotCrashed();
        jamRefundsAndKeepsStock();
        inactivityTimeoutRefunds();
        upiSuccess();
        upiDuplicateCallbackIsIdempotent();
        upiFailureAndLatePaymentRefund();
        technicianOperationsOnlyInMaintenance();
        dispensingStateRefusesInputWhileMotorRuns();
        concurrentPressesDispenseOnce();
        moneyIsConservedOverRandomSessions();
        System.out.println("All " + passed + " tests passed.");
    }

    // ---------------- helpers ----------------

    /** A machine with A1 water ₹20, A2 chips ₹15, B1 chikki ₹14 (5 each) and the given coins, in service. */
    static VendingMachine machine(Dispenser d, ManualTimeSource clock, Object... coinsAndCounts) {
        var m = new VendingMachine("VM-T", d, clock);
        m.restock("A1", WATER, 5);
        m.restock("A2", CHIPS, 5);
        m.restock("B1", CHIKKI, 5);
        for (int i = 0; i < coinsAndCounts.length; i += 2) m.loadCoins((Denomination) coinsAndCounts[i], (Integer) coinsAndCounts[i + 1]);
        m.exitMaintenance();
        return m;
    }

    static VendingMachine machine(Object... coinsAndCounts) { return machine((s, p) -> true, new ManualTimeSource(0), coinsAndCounts); }

    static Map<Denomination, Integer> bag(Object... pairs) {
        Map<Denomination, Integer> m = new EnumMap<>(Denomination.class);
        for (int i = 0; i < pairs.length; i += 2) m.put((Denomination) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    static long total(List<Denomination> coins) { return coins.stream().mapToLong(Denomination::paise).sum(); }

    // ---------------- tests ----------------

    static void happyPathExactAmount() {
        var m = machine();
        assertEquals("Balance ₹10", m.insert(NOTE_10), "display after first note");
        m.insert(COIN_10);
        assertEquals("Take your Water 1L", m.select("A1"), "sale completes");
        assertEquals(List.of(WATER), m.takeProducts(), "water delivered");
        assertEquals(List.of(), m.takeCoins(), "no change for exact money");
        assertEquals(4, m.stock("A1"), "stock decremented");
        assertEquals(2_000L, m.cashRevenuePaise(), "revenue");
        assertEquals("Idle", m.stateName(), "back to idle");
        pass("happyPathExactAmount");
    }

    static void changeIsReturned() {
        var m = machine(COIN_5, 2);
        m.insert(NOTE_20);
        m.select("A2");                                              // chips ₹15 from ₹20
        assertEquals(List.of(COIN_5), m.takeCoins(), "₹5 change");
        assertEquals(1, m.coinBox().get(COIN_5), "one ₹5 left in the tube");
        assertEquals(1, m.coinBox().get(NOTE_20), "note kept in the stacker");
        pass("changeIsReturned");
    }

    /** The counterexample: ₹6 from one ₹5 and three ₹2. Greedy takes the ₹5 and is stuck at ₹1. */
    static void changeMakerGreedyVsExact() {
        Map<Denomination, Integer> tubes = bag(COIN_5, 1, COIN_2, 3);
        assertEquals(Optional.empty(), ChangeMaker.greedy(600, tubes), "greedy fails");
        assertEquals(Optional.of(bag(COIN_2, 3)), ChangeMaker.exact(600, tubes), "exact finds 2+2+2");
        // fewest coins: ₹30 from {20 x1, 10 x3, 5 x4} -> 20 + 10, not 10+10+10 or 5 x 6
        assertEquals(Optional.of(bag(COIN_20, 1, COIN_10, 1)), ChangeMaker.exact(3_000, bag(COIN_20, 1, COIN_10, 3, COIN_5, 4)), "minimal coins");
        assertEquals(Optional.empty(), ChangeMaker.exact(300, bag(COIN_2, 5)), "₹3 from only ₹2 coins: impossible");
        assertEquals(Optional.empty(), ChangeMaker.exact(1_000, bag(COIN_5, 1, NOTE_10, 5)), "notes are never change");
        assertEquals(Optional.of(bag()), ChangeMaker.exact(0, bag()), "zero change needs no coins");
        pass("changeMakerGreedyVsExact");
    }

    static void machineGivesChangeWhereGreedyFails() {
        var m = machine(COIN_5, 1, COIN_2, 3);
        m.insert(COIN_20);
        m.select("B1");                                              // chikki ₹14, change ₹6
        assertEquals(List.of(CHIKKI), m.takeProducts(), "sold");
        assertEquals(List.of(COIN_2, COIN_2, COIN_2), m.takeCoins(), "2+2+2, not stuck after the ₹5");
        assertEquals(1, m.coinBox().get(COIN_5), "₹5 untouched");
        pass("machineGivesChangeWhereGreedyFails");
    }

    static void saleRefusedWhenChangeImpossibleThenCancelRefunds() {
        var m = machine();                                           // empty change tubes
        assertEquals(true, m.exactChangeOnly(), "light is on before anyone pays");
        m.insert(NOTE_20);                                           // accepted: water ₹20 needs no change
        String msg = m.select("A2");                                 // chips ₹15 would need ₹5 change
        assertTrue(msg.startsWith("Cannot return ₹5 change"), "refused: " + msg);
        assertEquals("HasMoney", m.stateName(), "money still in escrow, customer can choose again");
        assertEquals(5, m.stock("A2"), "nothing dispensed");
        m.cancel();
        assertEquals(List.of(NOTE_20), m.takeCoins(), "the same note comes back");
        assertEquals(0L, m.coinBox().values().stream().mapToLong(Integer::longValue).sum(), "box unchanged");
        pass("saleRefusedWhenChangeImpossibleThenCancelRefunds");
    }

    static void deadEndNoteRejectedBeforeAccepting() {
        var m = machine();                                           // no coins: cannot give ₹80/₹85/₹86 change
        String msg = m.insert(NOTE_100);
        assertTrue(msg.startsWith("EXACT CHANGE ONLY"), "rejected up front: " + msg);
        assertEquals(List.of(NOTE_100), m.takeCoins(), "note pushed straight back");
        assertEquals("Idle", m.stateName(), "nothing accepted");
        pass("deadEndNoteRejectedBeforeAccepting");
    }

    static void cancelReturnsTheExactCoinsInserted() {
        var m = machine(COIN_10, 5);
        m.insert(NOTE_10);
        m.insert(COIN_2);
        m.insert(COIN_2);
        assertEquals(1_400L, m.balancePaise(), "balance ₹14");
        m.cancel();
        assertEquals(List.of(COIN_2, COIN_2, NOTE_10), m.takeCoins(), "same pieces back (not a ₹10 coin for the note)");
        assertEquals(5, m.coinBox().get(COIN_10), "tubes untouched");
        assertEquals("Idle", m.stateName(), "idle again");
        pass("cancelReturnsTheExactCoinsInserted");
    }

    static void soldOutSlotAndSoldOutMachine() {
        var clock = new ManualTimeSource(0);
        var m = new VendingMachine("VM-T", (s, p) -> true, clock);
        m.restock("A1", WATER, 1);
        m.exitMaintenance();
        m.insert(NOTE_20);
        m.select("A1");
        assertEquals("SoldOut", m.stateName(), "last item sold -> whole machine sold out");
        assertEquals("SOLD OUT: ₹10 coin returned", m.insert(COIN_10), "money refused");
        assertEquals(List.of(COIN_10), m.takeCoins(), "coin back");
        m.enterMaintenance();
        m.restock("A1", WATER, 1);
        m.restock("A2", CHIPS, 0);
        m.exitMaintenance();
        m.insert(NOTE_20);
        assertEquals("Masala chips sold out: choose another or cancel", m.select("A2"), "one empty slot");
        assertEquals("HasMoney", m.stateName(), "still has the customer's money");
        pass("soldOutSlotAndSoldOutMachine");
    }

    /** Decision: select BEFORE money is allowed and just shows the price. Other out-of-place events are refused. */
    static void invalidEventsAreRefusedNotCrashed() {
        var m = machine();
        assertEquals("Masala chips ₹15: insert money or pay by UPI", m.select("A2"), "price check in Idle");
        assertEquals("Idle", m.stateName(), "no state change");
        assertEquals("Nothing to cancel", m.cancel(), "cancel in Idle");
        assertEquals("No slot Z9", m.select("Z9"), "unknown slot");
        m.insert(COIN_10);
        assertEquals("Insert ₹5 more", m.select("A2"), "not enough money");
        assertEquals("Cash inserted: buy with cash or cancel first", m.payByUpi("A2"), "no mixing cash and UPI");
        assertEquals("Finish the current transaction first", m.enterMaintenance(), "no maintenance mid-sale");
        pass("invalidEventsAreRefusedNotCrashed");
    }

    static void jamRefundsAndKeepsStock() {
        var m = machine((s, p) -> false, new ManualTimeSource(0), COIN_5, 2);   // sensor never sees a drop
        m.insert(NOTE_20);
        String msg = m.select("A2");
        assertTrue(msg.startsWith("Jam in A2: returned ₹20"), "refund message: " + msg);
        assertEquals(List.of(NOTE_20), m.takeCoins(), "the note comes back, no change paid");
        assertEquals(List.of(), m.takeProducts(), "nothing delivered");
        assertEquals(5, m.stock("A2"), "stock NOT decremented");
        assertEquals(2, m.coinBox().get(COIN_5), "change float untouched");
        assertEquals(0L, m.cashRevenuePaise(), "no revenue");
        m.insert(NOTE_20);
        assertEquals("Masala chips sold out: choose another or cancel", m.select("A2"), "jammed slot is out of use");
        pass("jamRefundsAndKeepsStock");
    }

    static void inactivityTimeoutRefunds() {
        var clock = new ManualTimeSource(0);
        var m = machine((s, p) -> true, clock);
        m.insert(COIN_5);
        clock.advance(40_000);
        m.insert(COIN_5);                                            // activity resets the timer
        clock.advance(59_000);
        assertEquals("", m.tick(), "59 s after the last coin: still waiting");
        clock.advance(1_000);
        assertTrue(m.tick().startsWith("No activity for 60 s: returned ₹10"), "refund at 60 s");
        assertEquals(List.of(COIN_5, COIN_5), m.takeCoins(), "coins back");
        assertEquals("Idle", m.stateName(), "idle");
        pass("inactivityTimeoutRefunds");
    }

    static void upiSuccess() {
        var m = machine();
        String qr = m.payByUpi("A1");
        String key = m.currentUpiKey().orElseThrow();
        assertEquals("Scan QR to pay ₹20 (order " + key + ")", qr, "QR shown");
        assertEquals("AwaitingUpi", m.stateName(), "waiting for the bank");
        assertEquals("Waiting for UPI payment (press cancel to stop): ₹10 coin returned", m.insert(COIN_10), "cash refused meanwhile");
        assertEquals("Take your Water 1L", m.onUpiResult(key, true), "paid -> dispensed");
        assertEquals(2_000L, m.upiRevenuePaise(), "UPI revenue");
        assertEquals(4, m.stock("A1"), "stock");
        pass("upiSuccess");
    }

    static void upiDuplicateCallbackIsIdempotent() {
        var m = machine();
        m.payByUpi("A1");
        String key = m.currentUpiKey().orElseThrow();
        m.onUpiResult(key, true);
        assertEquals("Duplicate callback (PAID): ignored", m.onUpiResult(key, true), "second callback");
        assertEquals("Duplicate callback (PAID): ignored", m.onUpiResult(key, false), "contradicting late callback");
        assertEquals(1, m.takeProducts().size(), "exactly one item");
        assertEquals(2_000L, m.upiRevenuePaise(), "charged once");
        assertEquals("Unknown order: ignored", m.onUpiResult("forged-key", true), "unknown key");
        pass("upiDuplicateCallbackIsIdempotent");
    }

    static void upiFailureAndLatePaymentRefund() {
        var clock = new ManualTimeSource(0);
        var m = machine((s, p) -> true, clock);
        m.payByUpi("A1");
        String first = m.currentUpiKey().orElseThrow();
        assertEquals("UPI payment failed: nothing charged", m.onUpiResult(first, false), "failure");
        assertEquals("Idle", m.stateName(), "idle after failure");
        m.payByUpi("A2");
        String second = m.currentUpiKey().orElseThrow();
        clock.advance(VendingMachine.UPI_TIMEOUT_MILLIS);
        assertTrue(m.tick().startsWith("UPI not confirmed in 120 s"), "order abandoned");
        assertTrue(m.onUpiResult(second, true).startsWith("Late payment for closed order: refunded ₹15"), "late success refunded");
        assertEquals("Duplicate callback (REFUNDED): ignored", m.onUpiResult(second, true), "refund only once");
        assertEquals(List.of(second), m.upiRefunds(), "one refund");
        assertEquals(List.of(), m.takeProducts(), "nothing delivered");
        pass("upiFailureAndLatePaymentRefund");
    }

    static void technicianOperationsOnlyInMaintenance() {
        var m = machine();
        m.insert(NOTE_20);
        m.select("A1");
        assertThrows(() -> m.restock("A1", WATER, 5), "restock while in service");
        assertThrows(() -> m.collectCash(), "collect while in service");
        assertEquals("Maintenance mode", m.enterMaintenance(), "door opened");
        assertEquals("Out of service: ₹10 coin returned", m.insert(COIN_10), "customers refused");
        assertEquals(bag(NOTE_20, 1), m.collectCash(), "notes collected");
        assertEquals(0, m.coinBox().getOrDefault(NOTE_20, 0), "stacker empty");
        m.restock("A1", WATER, 1);
        assertEquals(5, m.stock("A1"), "4 + 1");
        assertEquals("Back in service", m.exitMaintenance(), "door closed");
        pass("technicianOperationsOnlyInMaintenance");
    }

    /** The motor runs without the lock; meanwhile the machine is in Dispensing and refuses everything. */
    static void dispensingStateRefusesInputWhileMotorRuns() throws Exception {
        CountDownLatch motorStarted = new CountDownLatch(1), releaseMotor = new CountDownLatch(1);
        Dispenser slow = (slot, p) -> {
            motorStarted.countDown();
            try { return releaseMotor.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return false; }
        };
        var m = machine(slow, new ManualTimeSource(0));
        m.insert(NOTE_20);
        Thread buyer = new Thread(() -> m.select("A1"));
        buyer.start();
        assertTrue(motorStarted.await(5, TimeUnit.SECONDS), "motor started");
        assertEquals("Dispensing", m.stateName(), "observable while the motor runs");
        assertEquals("Busy: dispensing", m.select("A1"), "second press refused, not queued");
        assertEquals("Busy: dispensing: ₹10 coin returned", m.insert(COIN_10), "coin bounced");
        assertEquals("Nothing to cancel", m.cancel(), "cannot cancel a running motor");
        releaseMotor.countDown();
        buyer.join(5_000);
        assertEquals(List.of(WATER), m.takeProducts(), "one water");
        assertEquals(List.of(COIN_10), m.takeCoins(), "only the bounced coin");
        assertEquals("Idle", m.stateName(), "idle again");
        pass("dispensingStateRefusesInputWhileMotorRuns");
    }

    static void concurrentPressesDispenseOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            AtomicInteger motorRuns = new AtomicInteger();
            var m = machine((s, p) -> { motorRuns.incrementAndGet(); return true; }, new ManualTimeSource(0), COIN_5, 5);
            m.insert(NOTE_20);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> presses = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread t = new Thread(() -> {
                    try { go.await(); } catch (InterruptedException e) { return; }
                    m.select("A2");
                });
                t.start();
                presses.add(t);
            }
            go.countDown();                                          // all four press at once
            for (Thread t : presses) t.join(5_000);
            assertEquals(1, motorRuns.get(), "round " + round + ": motor ran once");
            assertEquals(4, m.stock("A2"), "round " + round + ": one item gone");
            assertEquals(List.of(COIN_5), m.takeCoins(), "round " + round + ": change paid once");
        }
        pass("concurrentPressesDispenseOnce");
    }

    /** Property-style check: whatever customers do, cash in = cash handed back + growth of the box, and box growth = revenue. */
    static void moneyIsConservedOverRandomSessions() {
        Denomination[] money = Denomination.values();
        String[] slots = {"A1", "A2", "B1"};
        long allRevenue = 0;
        for (long seed = 1; seed <= 50; seed++) {
            Random r = new Random(seed);
            var m = machine((s, p) -> r.nextInt(10) > 0, new ManualTimeSource(0), COIN_1, 3, COIN_2, 3, COIN_5, 2, COIN_10, 2);
            long boxBefore = CoinBox.total(m.coinBox());
            long inserted = 0, handedBack = 0;
            for (int step = 0; step < 60; step++) {
                switch (r.nextInt(5)) {
                    case 0, 1 -> { Denomination d = money[r.nextInt(money.length)]; inserted += d.paise(); m.insert(d); }
                    case 2, 3 -> m.select(slots[r.nextInt(slots.length)]);
                    default -> m.cancel();
                }
                handedBack += total(m.takeCoins());
                m.coinBox().values().forEach(n -> assertTrue(n >= 0, "never negative"));
            }
            m.cancel();
            handedBack += total(m.takeCoins());
            long boxGrowth = CoinBox.total(m.coinBox()) - boxBefore;
            assertEquals(inserted, handedBack + boxGrowth, "seed " + seed + ": cash conserved");
            assertEquals(m.cashRevenuePaise(), boxGrowth, "seed " + seed + ": box grew by exactly the revenue");
            allRevenue += m.cashRevenuePaise();
        }
        assertTrue(allRevenue > 0, "the random sessions really sold things");
        pass("moneyIsConservedOverRandomSessions");
    }

    // ---------------- tiny assert helpers ----------------

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void assertThrows(Runnable r, String what) {
        try { r.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(what + ": expected an exception");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
