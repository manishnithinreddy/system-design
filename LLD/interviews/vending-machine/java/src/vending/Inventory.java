package vending;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Slots ("A1", "B2") -> product, count, and whether the slot is jammed (out of use until a technician clears it). */
final class Inventory {
    private static final class Slot {
        Product product;
        int count;
        boolean jammed;
    }

    private final Map<String, Slot> slots = new TreeMap<>();

    /** Technician loads a slot. Restocking also clears a jam (the technician has the door open anyway). */
    void restock(String code, Product product, int count) {
        if (count < 0) throw new IllegalArgumentException("count must be >= 0");
        Slot s = slots.computeIfAbsent(code, c -> new Slot());
        if (s.product != null && !s.product.equals(product)) s.count = 0;   // new product replaces the old one
        s.product = product;
        s.count += count;
        s.jammed = false;
    }

    Product product(String code) {
        Slot s = slots.get(code);
        return s == null ? null : s.product;
    }

    int count(String code) {
        Slot s = slots.get(code);
        return s == null ? 0 : s.count;
    }

    boolean sellable(String code) {
        Slot s = slots.get(code);
        return s != null && s.count > 0 && !s.jammed;
    }

    void take(String code) {
        if (!sellable(code)) throw new IllegalStateException("slot " + code + " is not sellable");
        slots.get(code).count--;
    }

    void markJammed(String code) { slots.get(code).jammed = true; }

    boolean anySellable() { return slots.keySet().stream().anyMatch(this::sellable); }

    List<Product> sellableProducts() {
        List<Product> out = new ArrayList<>();
        slots.forEach((code, s) -> { if (sellable(code)) out.add(s.product); });
        return out;
    }
}
