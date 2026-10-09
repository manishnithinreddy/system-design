package orders;

import java.util.ArrayList;
import java.util.List;

/** Entry point used by the (imaginary) HTTP layer. */
public class OrderService {
    private final PriceCalculator calculator;
    private final InventoryService inventory;
    private final OrderRepository repository;

    public OrderService(PriceCalculator calculator, InventoryService inventory, OrderRepository repository) {
        this.calculator = calculator;
        this.inventory = inventory;
        this.repository = repository;
    }

    /** Reserves stock for every line, prices the order and stores it. All-or-nothing on stock. */
    public Order placeOrder(List<LineItem> items) {
        List<LineItem> reserved = new ArrayList<>();
        for (LineItem item : items) {
            if (!inventory.reserve(item.sku(), item.quantity())) {
                for (LineItem done : reserved) inventory.release(done.sku(), done.quantity());
                throw new IllegalStateException("Out of stock: " + item.sku());
            }
            reserved.add(item);
        }
        Order order = new Order(repository.nextId(), items, calculator.total(items));
        repository.save(order);
        return order;
    }

    public void cancel(String orderId) {
        Order order = repository.find(orderId).orElseThrow(() -> new IllegalArgumentException("No such order"));
        if (order.status() == Order.Status.CANCELLED) return;
        order.cancel();
        for (LineItem item : order.items()) inventory.release(item.sku(), item.quantity());
    }
}
