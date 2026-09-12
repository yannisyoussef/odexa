package commerce.order;

import commerce.runtime.ApiException;
import commerce.runtime.Outbox;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutWriter {
    private final OrderRepository orders;
    private final Outbox outbox;

    public CheckoutWriter(OrderRepository orders, Outbox outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result create(Order candidate, String key, String fingerprint) {
        boolean inserted = orders.insert(candidate, key, fingerprint);
        OrderRepository.StoredOrder persisted = orders.findByKey(candidate.tenantId(), candidate.customerId(), key)
                .orElseThrow(() -> new IllegalStateException("Idempotency winner missing"));
        Order order = matching(persisted, fingerprint);
        if (inserted) {
            CheckoutSnapshot s = order.snapshot();
            outbox.append("order.created", order.tenantId(), order.id().toString(),
                    new OrderCreated(order.id(), order.customerId(), s.productId(), s.quantity(),
                            s.totalMinor(), s.currency(), s.paymentMethod()), null);
        }
        return new Result(order, inserted);
    }

    static Order matching(OrderRepository.StoredOrder stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different checkout");
        }
        return stored.order();
    }

    public record Result(Order order, boolean created) { }
    public record OrderCreated(UUID orderId, String customerId, UUID productId, int quantity,
                               long totalMinor, String currency, String paymentMethod) { }
}
