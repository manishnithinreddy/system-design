package orders;

import java.util.List;

/**
 * Pricing rules:
 *  - 10% discount when the subtotal is at least Rs 1000 (100_000 paise).
 *  - 18% GST (tax) on the discounted amount, rounded half-up to the nearest paise ON THE WHOLE ORDER.
 */
public class PriceCalculator {
    static final long DISCOUNT_THRESHOLD_PAISE = 100_000;
    static final int DISCOUNT_PERCENT = 10;
    static final int TAX_PERCENT = 18;

    public long subtotal(List<LineItem> items) {
        long sum = 0;
        for (LineItem item : items) sum += item.totalPaise();
        return sum;
    }

    public long discount(long subtotalPaise) {
        if (subtotalPaise > DISCOUNT_THRESHOLD_PAISE) {
            return subtotalPaise * DISCOUNT_PERCENT / 100;
        }
        return 0;
    }

    public long tax(List<LineItem> items, long discountPaise) {
        long subtotal = subtotal(items);
        long tax = 0;
        for (LineItem item : items) {
            // share of the discount that belongs to this line, then tax on it
            long lineNet = item.totalPaise() - (subtotal == 0 ? 0 : discountPaise * item.totalPaise() / subtotal);
            tax += lineNet * TAX_PERCENT / 100;
        }
        return tax;
    }

    public long total(List<LineItem> items) {
        long subtotal = subtotal(items);
        long discount = discount(subtotal);
        return subtotal - discount + tax(items, discount);
    }
}
