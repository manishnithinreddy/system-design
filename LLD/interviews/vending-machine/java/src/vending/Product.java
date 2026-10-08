package vending;

/** A product type, e.g. "Masala chips" at ₹15. A value: two equal records are the same product. */
public record Product(String name, long pricePaise) {
    public Product {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (pricePaise <= 0) throw new IllegalArgumentException("price must be positive");
    }

    @Override public String toString() { return name + " " + Denomination.rupees(pricePaise); }
}
