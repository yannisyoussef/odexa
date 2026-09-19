package commerce.order;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import commerce.runtime.Correlation;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final OrderRepository orders;
    private final CheckoutWriter writer;
    private final CatalogClient catalog;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, CheckoutWriter writer, CatalogClient catalog, Clock clock) {
        this.orders = orders;
        this.writer = writer;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** The downstream call is deliberately outside the writer's database transaction. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CheckoutWriter.Result checkout(Actor actor, String bearer, String key, CheckoutRequest request) {
        authorize(actor);
        if (key == null || !key.matches("[\\x21-\\x7E]{1,128}")) {
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1 to 128 visible ASCII characters");
        }
        request.validate();
        String fingerprint = request.fingerprint();
        Optional<OrderRepository.StoredOrder> existing = orders.findByKey(actor.tenantId(), actor.subject(), key);
        if (existing.isPresent()) {
            return new CheckoutWriter.Result(CheckoutWriter.matching(existing.get(), fingerprint), false);
        }
        CheckoutSnapshot snapshot;
        try {
            snapshot = catalog.snapshot(request, bearer, Correlation.current());
        } catch (ApiException failure) {
            // Another request may have committed while this request was contacting catalog.
            Optional<OrderRepository.StoredOrder> winner = orders.findByKey(actor.tenantId(), actor.subject(), key);
            if (winner.isPresent()) {
                return new CheckoutWriter.Result(CheckoutWriter.matching(winner.get(), fingerprint), false);
            }
            throw failure;
        }
        Order candidate = Order.create(UUID.randomUUID(), actor.tenantId(), actor.subject(), snapshot, clock.instant());
        return writer.create(candidate, key, fingerprint);
    }

    public Order get(Actor actor, UUID id) {
        authorize(actor);
        return orders.findOwned(actor.tenantId(), actor.subject(), id)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Order not found"));
    }

    static void authorize(Actor actor) {
        actor.requireRole("CUSTOMER");
        if (actor.subject() == null || actor.subject().isBlank() || actor.subject().length() > 255) {
            throw new ApiException(403, "INVALID_CUSTOMER", "A customer identity is required");
        }
    }
}
