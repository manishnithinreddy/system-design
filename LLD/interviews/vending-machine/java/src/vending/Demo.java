package vending;

import static vending.Denomination.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One morning at an office snack machine, on a fake clock. Prints what each customer sees, then the audit log. */
public final class Demo {
    static final ManualTimeSource clock = new ManualTimeSource(0);
    static VendingMachine m;

    public static void main(String[] args) {
        AtomicBoolean jamNext = new AtomicBoolean(false);
        m = new VendingMachine("VM-0042", (slot, p) -> !jamNext.getAndSet(false), clock);

        System.out.println("-- Technician loads the machine (low change float: one ₹5, three ₹2) --");
        m.restock("A1", new Product("Water 1L", 2_000), 2);
        m.restock("A2", new Product("Masala chips", 1_500), 3);
        m.restock("B1", new Product("Peanut chikki", 1_400), 3);
        m.restock("B2", new Product("Cold coffee", 3_500), 2);
        m.loadCoins(COIN_5, 1);
        m.loadCoins(COIN_2, 3);
        say("exit maintenance", m.exitMaintenance());
        System.out.println("   EXACT CHANGE ONLY light: " + (m.exactChangeOnly() ? "ON" : "off"));

        System.out.println("\n-- Asha: chikki ₹14 with ₹20 (change ₹6: greedy would pick ₹5 and get stuck) --");
        say("insert ₹10 note", m.insert(NOTE_10));
        say("insert ₹10 coin", m.insert(COIN_10));
        say("select B1", m.select("B1"));

        System.out.println("\n-- Meera: ₹100 note when the machine cannot make ₹65-₹86 change --");
        say("insert ₹100 note", m.insert(NOTE_100));
        say("insert ₹20 coin", m.insert(COIN_20));
        say("insert ₹10 coin", m.insert(COIN_10));
        say("insert ₹5 coin", m.insert(COIN_5));
        say("select B2", m.select("B2"));

        System.out.println("\n-- Ravi: chips ₹15 with a ₹20 note --");
        say("insert ₹20 note", m.insert(NOTE_20));
        say("select A2", m.select("A2"));

        System.out.println("\n-- Arjun inserts a coin and walks off to answer a call --");
        say("insert ₹10 coin", m.insert(COIN_10));
        clock.advance(60_000);
        say("(60 s later) tick", m.tick());

        System.out.println("\n-- Priya pays by UPI; the bank's callback arrives twice --");
        say("pay by UPI A2", m.payByUpi("A2"));
        String key = m.currentUpiKey().orElseThrow();
        clock.advance(4_000);
        say("bank callback", m.onUpiResult(key, true));
        say("bank callback (retry)", m.onUpiResult(key, true));

        System.out.println("\n-- Sam: the motor turns but nothing falls (jam) --");
        jamNext.set(true);
        say("insert ₹20 note", m.insert(NOTE_20));
        say("select A1", m.select("A1"));

        System.out.println("\n-- Dev starts UPI, leaves; his payment goes through 3 minutes later --");
        say("pay by UPI B1", m.payByUpi("B1"));
        String devKey = m.currentUpiKey().orElseThrow();
        clock.advance(120_000);
        say("(120 s later) tick", m.tick());
        clock.advance(60_000);
        say("late bank callback", m.onUpiResult(devKey, true));

        System.out.println("\n-- Evening: technician collects notes and clears the jam --");
        say("enter maintenance", m.enterMaintenance());
        System.out.println("   collected: " + CoinBox.describe(m.collectCash()));
        m.restock("A1", new Product("Water 1L", 2_000), 4);
        say("exit maintenance", m.exitMaintenance());

        System.out.println("\nCash revenue " + rupees(m.cashRevenuePaise()) + ", UPI revenue " + rupees(m.upiRevenuePaise())
                + ", UPI refunds " + m.upiRefunds() + ", coins left " + CoinBox.describe(m.coinBox()));
        System.out.println("\nAudit log:");
        m.auditLog().forEach(e -> System.out.println("  " + e));
    }

    static void say(String action, String display) {
        System.out.printf("   > %-20s | %s%n", action, display);
        List<Denomination> coins = m.takeCoins();
        List<Product> items = m.takeProducts();
        if (!items.isEmpty()) System.out.println("     (flap: " + items + ")");
        if (!coins.isEmpty()) System.out.println("     (coin cup: " + coins.stream().map(Denomination::label).toList() + ")");
    }
}
