package wallet;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Prints one ATM visit and one day in a wallet. Run: ./run.sh */
public final class Demo {
    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-09T04:30:00Z"), TransferService.IST);
        atm(clock);
        System.out.println();
        wallet(clock);
    }

    static void atm(MutableClock clock) {
        System.out.println("=== ATM ===");
        InMemoryBank bank = new InMemoryBank(clock, Money.rupees(25_000));
        bank.openCard("4111111111111234", "1234", Money.rupees(20_000));
        // a quiet evening: only one ₹500 note left, plenty of ₹200, no ₹100
        CashDispenser cash = new CashDispenser(Map.of(Money.rupees(500), 1, Money.rupees(200), 30, Money.rupees(100), 0));
        Atm atm = new Atm("ATM-PUNE-07", cash, bank, clock);
        say("insert card", atm.insertCard("4111111111111234"));
        say("PIN 9999", atm.enterPin("9999"));
        say("PIN 1234", atm.enterPin("1234"));
        say("balance", atm.balance());
        say("withdraw ₹550", atm.withdraw(Money.rupees(550)));
        say("withdraw ₹5,000", atm.withdraw(Money.rupees(5_000)));
        System.out.println("  (greedy would take the one ₹500 and get stuck at ₹4,500 = 22.5 x ₹200; exact search found 25 x ₹200)");

        say("insert card", atm.insertCard("4111111111111234"));
        say("PIN 1234", atm.enterPin("1234"));
        bank.failNextDebit(InMemoryBank.Fault.TIMEOUT_AFTER_APPLY);
        say("withdraw ₹1,000 (bank reply lost)", atm.withdraw(Money.rupees(1_000)));
        System.out.println("  balance at the bank afterwards: " + Money.format(bank.balance("4111111111111234")));
        System.out.println("  journal:");
        atm.journal().forEach(j -> System.out.println("    " + j.ref() + "  " + Money.format(j.amountPaise()) + "  " + j.outcome()));
    }

    static void wallet(MutableClock clock) {
        System.out.println("=== Wallet ===");
        TransferService w = new TransferService(clock, Duration.ofMinutes(15));
        w.open("priya", Account.Type.USER, KycTier.FULL_KYC);
        w.open("rahul", Account.Type.USER, KycTier.FULL_KYC);
        w.open("zomato-merchant", Account.Type.MERCHANT, KycTier.NONE);

        show("add ₹1,000 from bank", w.addMoney("add-1", "priya", Money.rupees(1_000)));
        clock.advance(Duration.ofMinutes(5));
        Txn sent = w.transfer("send-rahul-1", "priya", "rahul", Money.rupees(200));
        show("send ₹200 to rahul", sent);
        Txn again = w.transfer("send-rahul-1", "priya", "rahul", Money.rupees(200));
        show("app hung, tapped again (same key)", again);
        System.out.println("  same txn? " + (sent == again) + ", rahul has " + Money.format(w.balance("rahul")));
        show("send ₹5,000 to rahul", w.transfer("send-rahul-2", "priya", "rahul", Money.rupees(5_000)));

        clock.advance(Duration.ofMinutes(30));
        Txn hold = w.authorize("order-881-auth", "priya", "zomato-merchant", Money.rupees(450));
        show("food order: hold ₹450", hold);
        clock.advance(Duration.ofMinutes(2));
        Txn cap = w.capture("order-881-capture", hold.id(), Money.rupees(400));
        show("restaurant confirms ₹400", cap);
        clock.advance(Duration.ofHours(1));
        show("one item missing: refund ₹120", w.refund("order-881-refund-1", cap.id(), Money.rupees(120)));

        System.out.println("\n  Statement for priya:");
        w.statement("priya").forEach(line -> System.out.println("  " + line));
        System.out.println("  balance " + Money.format(w.balance("priya")) + " (sum of entries: "
                + Money.format(w.balanceFromLedger("priya")) + ")");
    }

    private static void say(String action, String screen) {
        System.out.println("> " + action);
        for (String line : screen.split("\n")) System.out.println("  " + line);
    }

    private static void show(String action, Txn t) {
        System.out.printf("> %-36s %s %s %s%n", action, t.id(), t.status(), t.ok() ? "" : "(" + t.reason() + ")");
    }
}
