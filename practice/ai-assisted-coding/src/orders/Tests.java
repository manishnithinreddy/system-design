package orders;

import java.util.List;

/** Plain-Java test runner (no JUnit). Run with ../run.sh. Exit code 1 if any test fails. */
public class Tests {
    private static int passed = 0, failed = 0;

    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "  ->  " + t.getMessage());
        }
    }

    static void assertEquals(long expected, long actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        PriceCalculator calc = new PriceCalculator();

        test("subtotal adds up lines", () ->
            assertEquals(2500, calc.subtotal(List.of(new LineItem("a", 500, 3), new LineItem("b", 1000, 1))), "subtotal"));

        test("no discount below threshold", () ->
            assertEquals(0, calc.discount(99_999), "discount"));

        test("discount applies above threshold", () ->
            assertEquals(20_000, calc.discount(200_000), "discount"));

        test("discount applies exactly at threshold (Rs 1000)", () ->
            assertEquals(10_000, calc.discount(100_000), "discount at threshold"));

        test("tax is rounded half-up on the whole order", () ->
            // 999 paise * 18% = 179.82 -> 180
            assertEquals(999 + 180, calc.total(List.of(new LineItem("a", 999, 1))), "total"));

        test("total with discount and tax", () ->
            // 2000.00 -> discount 200.00 -> 1800.00 + 18% = 2124.00
            assertEquals(212_400, calc.total(List.of(new LineItem("a", 200_000, 1))), "total"));

        test("reserve reduces stock", () -> {
            InventoryService inv = new InventoryService();
            inv.addStock("a", 5);
            assertTrue(inv.reserve("a", 3), "reserve should succeed");
            assertEquals(2, inv.available("a"), "remaining");
        });

        test("reserve fails when not enough stock", () -> {
            InventoryService inv = new InventoryService();
            inv.addStock("a", 2);
            assertTrue(!inv.reserve("a", 3), "reserve should fail");
            assertEquals(2, inv.available("a"), "remaining");
        });

        test("placeOrder rolls back stock when a later line is out of stock", () -> {
            InventoryService inv = new InventoryService();
            inv.addStock("a", 5);
            OrderService svc = new OrderService(calc, inv, new OrderRepository());
            try {
                svc.placeOrder(List.of(new LineItem("a", 100, 2), new LineItem("missing", 100, 1)));
                throw new AssertionError("expected failure");
            } catch (IllegalStateException expected) {
                assertEquals(5, inv.available("a"), "stock restored");
            }
        });

        test("cancel releases stock once", () -> {
            InventoryService inv = new InventoryService();
            inv.addStock("a", 5);
            OrderService svc = new OrderService(calc, inv, new OrderRepository());
            Order o = svc.placeOrder(List.of(new LineItem("a", 100, 2)));
            svc.cancel(o.id());
            svc.cancel(o.id());
            assertEquals(5, inv.available("a"), "stock after double cancel");
        });

        test("payment callback marks order paid", () -> {
            OrderRepository repo = new OrderRepository();
            Order o = new Order("O1", List.of(new LineItem("a", 1000, 1)), 1180);
            repo.save(o);
            PaymentCallbackHandler h = new PaymentCallbackHandler(repo);
            assertTrue(h.onPaymentSucceeded("P1", "O1", 1180) == PaymentCallbackHandler.Result.APPLIED, "applied");
            assertTrue(o.status() == Order.Status.PAID, "paid");
        });

        test("duplicate payment callback is applied once", () -> {
            OrderRepository repo = new OrderRepository();
            Order o = new Order("O2", List.of(new LineItem("a", 1000, 1)), 2000);
            repo.save(o);
            PaymentCallbackHandler h = new PaymentCallbackHandler(repo);
            h.onPaymentSucceeded("P1", "O2", 1000);
            h.onPaymentSucceeded("P1", "O2", 1000); // gateway retry of the same payment
            assertEquals(1000, o.paidPaise(), "paid amount");
            assertTrue(o.status() == Order.Status.CREATED, "still unpaid (only half received)");
        });

        test("callback for unknown order", () -> {
            PaymentCallbackHandler h = new PaymentCallbackHandler(new OrderRepository());
            assertTrue(h.onPaymentSucceeded("P9", "nope", 1) == PaymentCallbackHandler.Result.UNKNOWN_ORDER, "unknown");
        });

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
