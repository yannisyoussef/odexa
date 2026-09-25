package commerce.order;

import java.util.UUID;

/** Owned, immutable accepted catalog snapshot. */
public record OrderLine(UUID productId, int quantity, String productName, long unitPriceMinor,
                        long lineTotalMinor, long catalogVersion) {
    public OrderLine {
        if (productId == null || quantity < 1 || quantity > 100 || productName == null
                || productName.isBlank() || unitPriceMinor < 0 || catalogVersion < 0)
            throw new IllegalArgumentException("Invalid order line");
        if (Math.multiplyExact(unitPriceMinor, quantity) != lineTotalMinor)
            throw new IllegalArgumentException("Invalid line total");
    }
}
