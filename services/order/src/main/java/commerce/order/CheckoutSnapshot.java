package commerce.order;

import java.util.UUID;

/** Authoritative, immutable catalog values plus the customer's checkout intent. */
public record CheckoutSnapshot(UUID productId, int quantity, String productName,
                               long unitPriceMinor, long totalMinor, String currency,
                               long catalogVersion, String paymentMethod) {
    public CheckoutSnapshot {
        if (productId == null || quantity < 1 || quantity > 100 || productName == null
                || productName.isBlank() || unitPriceMinor <= 0 || !"USD".equals(currency)
                || catalogVersion < 0 || (paymentMethod == null || !paymentMethod.matches("pm_[A-Za-z0-9_]{1,125}"))) {
            throw new IllegalArgumentException("Invalid checkout snapshot");
        }
        if (Math.multiplyExact(unitPriceMinor, quantity) != totalMinor) {
            throw new IllegalArgumentException("Invalid checkout total");
        }
    }
}
