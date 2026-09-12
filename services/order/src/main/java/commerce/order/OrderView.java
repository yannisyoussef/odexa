package commerce.order;

import java.time.Instant;
import java.util.UUID;

public record OrderView(UUID id, UUID productId, int quantity, long totalMinor,
                        String currency, OrderStatus status, long version, Instant createdAt) {
    public static OrderView of(Order order) {
        CheckoutSnapshot snapshot = order.snapshot();
        return new OrderView(order.id(), snapshot.productId(), snapshot.quantity(),
                snapshot.totalMinor(), snapshot.currency(), order.status(), order.version(),
                order.createdAt());
    }
}
