package splitwise;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A weekend trip to Goa with three friends. */
public final class Demo {
    public static void main(String[] args) {
        ExpenseService svc = new ExpenseService(Clock.systemUTC());
        svc.createGroup("goa", Set.of("Asha", "Bala", "Chitra"));

        svc.addExpense("goa", "req-1", "Asha", Money.parse("9000"),
                new SplitSpec.Equal(List.of("Asha", "Bala", "Chitra")), "Hotel");
        svc.addExpense("goa", "req-2", "Bala", Money.parse("1500"),
                new SplitSpec.Exact(Map.of("Bala", Money.parse("500"), "Chitra", Money.parse("1000"))), "Dinner");
        svc.addExpense("goa", "req-3", "Chitra", Money.parse("100"),
                new SplitSpec.Equal(List.of("Asha", "Bala", "Chitra")), "Ice cream");

        for (var e : svc.history("goa")) {
            if (e instanceof LedgerEntry.Expense x) {
                StringBuilder split = new StringBuilder();
                new java.util.TreeMap<>(x.owedPaise()).forEach((u, p) -> split.append(u).append(' ').append(Money.format(p)).append("  "));
                System.out.printf("%-10s paid by %-6s %10s   split: %s%n", x.description(), x.paidBy(),
                        Money.format(x.totalPaise()), split.toString().trim());
            }
        }
        System.out.println();
        svc.balances("goa").forEach((user, net) -> System.out.printf("%-6s %s%n", user,
                net > 0 ? "gets back " + Money.format(net) : net < 0 ? "owes " + Money.format(-net) : "settled"));
        System.out.println();
        System.out.println("Simplest way to settle:");
        svc.simplifiedDebts("goa").forEach(t -> System.out.println("  " + t));
    }
}
