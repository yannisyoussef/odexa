package commerce.order;

import java.util.*;

/** Authoritative immutable lines and one payment total. */
public record CheckoutSnapshot(List<OrderLine> items, long totalMinor, String currency, String paymentMethod) {
    public CheckoutSnapshot {
        if (items == null || items.isEmpty() || items.size() > 20 || !"USD".equals(currency)
                || paymentMethod == null || !paymentMethod.matches("pm_[A-Za-z0-9_]{1,125}"))
            throw new IllegalArgumentException("Invalid checkout snapshot");
        items = items.stream().sorted(Comparator.comparing(i -> i.productId().toString())).toList();
        Set<UUID> ids = new HashSet<>();
        long total = 0;
        for (OrderLine item : items) {
            if (!ids.add(item.productId())) throw new IllegalArgumentException("Duplicate order line");
            total = Math.addExact(total, item.lineTotalMinor());
        }
        if (total <= 0 || total != totalMinor) throw new IllegalArgumentException("Invalid checkout total");
    }
    public CheckoutSnapshot(UUID productId, int quantity, String productName, long unitPriceMinor,
                            long totalMinor, String currency, long catalogVersion, String paymentMethod) {
        this(List.of(new OrderLine(productId, quantity, productName, unitPriceMinor, totalMinor, catalogVersion)),
                totalMinor, currency, paymentMethod);
    }
    // Single-line convenience for older internal callers; never selects the first of a basket.
    private OrderLine single() {
        if (items.size() != 1) throw new IllegalStateException("Expected one line");
        return items.getFirst();
    }
    public UUID productId() { return single().productId(); }
    public int quantity() { return single().quantity(); }
    public String productName() { return single().productName(); }
    public long unitPriceMinor() { return single().unitPriceMinor(); }
    public long catalogVersion() { return single().catalogVersion(); }
}
