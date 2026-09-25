package commerce.inventory;

import java.util.*;

/** Inventory-owned transport model, normalized for both event versions. */
public record OrderCreated(UUID orderId, String customerId, List<Line> items,
                           long totalMinor, String currency, String paymentMethod) {
    public record Line(UUID productId, int quantity) { }
    public OrderCreated {
        if (orderId == null || customerId == null || customerId.isBlank() || customerId.length() > 255
                || totalMinor < 0 || !"USD".equals(currency) || paymentMethod == null || paymentMethod.isBlank()
                || paymentMethod.length() > 200 || items == null || items.isEmpty() || items.size() > 20)
            throw new IllegalArgumentException("Invalid order snapshot");
        Set<UUID> ids = new HashSet<>();
        for (Line line : items) {
            if (line == null || line.productId() == null || line.quantity() < 1 || line.quantity() > 100
                    || !ids.add(line.productId())) throw new IllegalArgumentException("Invalid reservation lines");
        }
        items = items.stream().sorted(Comparator.comparing(l -> l.productId().toString())).toList();
    }
    public OrderCreated(UUID orderId, String customerId, UUID productId, int quantity,
                        long totalMinor, String currency, String paymentMethod) {
        this(orderId, customerId, List.of(new Line(productId, quantity)), totalMinor, currency, paymentMethod);
    }
}
