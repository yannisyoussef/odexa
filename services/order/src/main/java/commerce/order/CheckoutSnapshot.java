package commerce.order;

import java.util.UUID;

/** Authoritative, immutable catalog values plus the customer's checkout intent. */
public record CheckoutSnapshot(UUID productId, int quantity, String productName,
                               long unitPriceMinor, long totalMinor, String currency,
                               long catalogVersion, String paymentMethod) {
    public CheckoutSnapshot {
        if (productId == null || quantity < 1 || quantity > 100 || productName == null
                || productName.isBlank() || unitPriceMinor <= 0 || !"USD".equals(currency)
                || catalogVersion < 0 || !("pm_approved".equals(paymentMethod)
                || "pm_declined".equals(paymentMethod))) {
            throw new IllegalArgumentException("Invalid checkout snapshot");
        }
        if (Math.multiplyExact(unitPriceMinor, quantity) != totalMinor) {
            throw new IllegalArgumentException("Invalid checkout total");
        }
    }
}
