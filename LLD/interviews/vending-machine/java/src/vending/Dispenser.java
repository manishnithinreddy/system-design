package vending;

/**
 * The hardware: spin the spiral motor for one slot and report whether the drop sensor (an infrared beam
 * across the delivery chute) saw an item fall. Slow on real hardware (1-3 s), so the machine calls it
 * WITHOUT holding its lock. An interface so tests can simulate a jam.
 */
@FunctionalInterface
public interface Dispenser {
    boolean dispense(String slot, Product product);
}
