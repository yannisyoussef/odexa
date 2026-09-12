package commerce.order;

import java.util.Objects;
import java.util.UUID;

/** Durable causal evidence: a result is not applied until its reservation event arrives. */
public record DeferredPayment(UUID eventId, UUID reservationEventId, UUID paymentId,
                              boolean authorized) {
    public DeferredPayment {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(reservationEventId);
        Objects.requireNonNull(paymentId);
    }
}
