package orders;

/** One line of an order. All money is in paise (1 rupee = 100 paise) held in a long. */
public record LineItem(String sku, long unitPricePaise, int quantity) {
    public LineItem {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (unitPricePaise < 0) throw new IllegalArgumentException("price must not be negative");
    }

    public long totalPaise() {
        return unitPricePaise * quantity;
    }
}
