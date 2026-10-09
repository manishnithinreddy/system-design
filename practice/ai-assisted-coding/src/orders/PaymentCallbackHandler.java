package orders;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives "payment succeeded" webhooks from the payment gateway.
 * Gateways retry callbacks (timeouts, 5xx), so the same paymentId can arrive several times.
 * Each paymentId must be applied to the order exactly once.
 */
public class PaymentCallbackHandler {
    public enum Result { APPLIED, DUPLICATE, UNKNOWN_ORDER }

    private final OrderRepository repository;
    private final Set<String> processedPaymentIds = ConcurrentHashMap.newKeySet();

    public PaymentCallbackHandler(OrderRepository repository) {
        this.repository = repository;
    }

    public Result onPaymentSucceeded(String paymentId, String orderId, long amountPaise) {
        if (processedPaymentIds.contains(orderId)) {
            return Result.DUPLICATE;
        }
        Order order = repository.find(orderId).orElse(null);
        if (order == null) {
            return Result.UNKNOWN_ORDER;
        }
        order.applyPayment(amountPaise);
        processedPaymentIds.add(paymentId);
        return Result.APPLIED;
    }
}
