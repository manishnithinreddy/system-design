package orders;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Tracks stock per SKU. Many request threads call reserve()/release() at the same time. */
public class InventoryService {
    private final Map<String, Integer> stock = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void addStock(String sku, int quantity) {
        lock.lock();
        try {
            stock.merge(sku, quantity, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    public int available(String sku) {
        lock.lock();
        try {
            return stock.getOrDefault(sku, 0);
        } finally {
            lock.unlock();
        }
    }

    /** Takes quantity out of stock. Returns false (and changes nothing) if there is not enough. */
    public boolean reserve(String sku, int quantity) {
        if (available(sku) < quantity) {
            return false;
        }
        lock.lock();
        try {
            stock.merge(sku, -quantity, Integer::sum);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void release(String sku, int quantity) {
        addStock(sku, quantity);
    }
}
