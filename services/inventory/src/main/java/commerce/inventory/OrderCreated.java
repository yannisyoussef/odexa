package commerce.inventory;

import java.util.UUID;

// Private to inventory's transport boundary; no cross-service business-model dependency.
public record OrderCreated(UUID orderId, String customerId, UUID productId, int quantity,
                           long totalMinor, String currency, String paymentMethod) {
}
