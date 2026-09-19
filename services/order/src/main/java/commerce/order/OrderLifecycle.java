package commerce.order;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import commerce.runtime.Correlation;
import commerce.runtime.Outbox;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycle {
    private final OrderRepository orders;
    private final Outbox outbox;
    private final Clock clock;
    private final Duration staleAfter;

    public OrderLifecycle(OrderRepository orders, Outbox outbox, Clock clock,
            @Value("${order.lifecycle.stale-after:30m}") Duration staleAfter) {
        if (staleAfter.compareTo(Duration.ofMinutes(1)) < 0 || staleAfter.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Order staleness must be between one minute and seven days");
        }
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Transactional
    public Order cancel(Actor actor, UUID id) {
        CheckoutService.authorize(actor);
        Order before = orders.lock(actor.tenantId(), id).filter(order -> order.customerId().equals(actor.subject()))
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Order not found"));
        if (before.status() == OrderStatus.CANCELLED) return before;
        Order after = before.stopBeforeDispatch(OrderStatus.CANCELLED);
        UUID cause = outbox.withdrawUnattempted("order.created", before.tenantId(), id.toString())
                .orElseThrow(() -> new ApiException(409, "ORDER_NOT_CANCELLABLE", "Order can no longer be cancelled"));
        finish(after, "CUSTOMER_CANCELLED", "order.cancelled", cause);
        return after;
    }

    /** Database claims are held until state, history and outbox commit together. */
    @Transactional
    public int expireBatch() {
        int expired = 0;
        for (Order before : orders.lockStaleUndispatched(clock.instant().minus(staleAfter), 25)) {
            String correlation = orders.checkoutCorrelation(before.tenantId(), before.id());
            var cause = outbox.withdrawUnattempted("order.created", before.tenantId(), before.id().toString());
            if (cause.isEmpty()) continue; // Publisher committed its fence after candidate selection.
            String previous = MDC.get(Correlation.MDC_KEY);
            MDC.put(Correlation.MDC_KEY, correlation);
            try {
                finish(before.stopBeforeDispatch(OrderStatus.EXPIRED), "DISPATCH_EXPIRED", "order.expired", cause.get());
                expired++;
            } finally {
                if (previous == null) MDC.remove(Correlation.MDC_KEY);
                else MDC.put(Correlation.MDC_KEY, previous);
            }
        }
        return expired;
    }

    private void finish(Order after, String reason, String type, UUID cause) {
        orders.save(after);
        orders.recordHistory(after, clock.instant(), reason);
        outbox.append(type, after.tenantId(), after.id().toString(), new Stopped(after.id(), reason), cause);
    }

    public record Stopped(UUID orderId, String reason) { }
}
