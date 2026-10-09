package orders;

import java.util.List;

public class Order {
    public enum Status { CREATED, PAID, CANCELLED }

    private final String id;
    private final List<LineItem> items;
    private final long totalPaise;   // what the customer must pay, after discount and tax
    private Status status = Status.CREATED;
    private long paidPaise;

    public Order(String id, List<LineItem> items, long totalPaise) {
        this.id = id;
        this.items = List.copyOf(items);
        this.totalPaise = totalPaise;
    }

    public String id() { return id; }
    public List<LineItem> items() { return items; }
    public long totalPaise() { return totalPaise; }
    public synchronized Status status() { return status; }
    public synchronized long paidPaise() { return paidPaise; }

    /** Adds a payment; the order becomes PAID once the total is covered. */
    synchronized void applyPayment(long amountPaise) {
        paidPaise += amountPaise;
        if (paidPaise >= totalPaise) status = Status.PAID;
    }

    synchronized void cancel() { status = Status.CANCELLED; }
}
