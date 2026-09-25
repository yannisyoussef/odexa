package commerce.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderView(UUID id, UUID productId, Integer quantity, List<OrderLine> items, long totalMinor,
                        String currency, OrderStatus status, long version, Instant createdAt) {
    public static OrderView of(Order order) {
        CheckoutSnapshot s = order.snapshot();
        boolean single = s.items().size() == 1;
        return new OrderView(order.id(), single ? s.productId() : null, single ? s.quantity() : null,
                s.items(), s.totalMinor(), s.currency(), order.status(), order.version(), order.createdAt());
    }
}
