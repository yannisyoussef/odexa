package commerce.order;

import commerce.runtime.ApiException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pure immutable state machine. Database locks serialize application of its decisions. */
public record Order(UUID id, UUID tenantId, String customerId, CheckoutSnapshot snapshot,
                    OrderStatus status, long version, Instant createdAt,
                    UUID reservationEventId, DeferredPayment deferredPayment) {
    public Order {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        if (customerId.isBlank() || version < 0) {
            throw new IllegalArgumentException("Invalid order");
        }
        if ((status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.CONFIRMED
                || status == OrderStatus.PAYMENT_FAILED) && reservationEventId == null) {
            throw new IllegalArgumentException("Reservation evidence required");
        }
        if ((status == OrderStatus.CREATED || status == OrderStatus.STOCK_REJECTED)
                && reservationEventId != null) {
            throw new IllegalArgumentException("Unexpected reservation evidence");
        }
        if (deferredPayment != null && status != OrderStatus.CREATED) {
            throw new IllegalArgumentException("Deferred result requires CREATED");
        }
    }

    public static Order create(UUID id, UUID tenantId, String customerId,
                               CheckoutSnapshot snapshot, Instant now) {
        return new Order(id, tenantId, customerId, snapshot, OrderStatus.CREATED, 0,
                now, null, null);
    }

    public Order reserved(UUID eventId) {
        Objects.requireNonNull(eventId);
        if (status.terminal()) {
            return this; // A late event must never reopen an order.
        }
        if (status == OrderStatus.PENDING_PAYMENT) {
            if (!eventId.equals(reservationEventId)) {
                throw conflict("Conflicting reservation evidence");
            }
            return this;
        }
        Order pending = new Order(id, tenantId, customerId, snapshot,
                OrderStatus.PENDING_PAYMENT, version + 1, createdAt, eventId, null);
        return deferredPayment == null ? pending : pending.payment(deferredPayment);
    }

    public Order stockRejected() {
        return switch (status) {
            case CREATED -> {
                if (deferredPayment != null) {
                    throw conflict("Stock rejection conflicts with payment evidence");
                }
                yield new Order(id, tenantId, customerId, snapshot, OrderStatus.STOCK_REJECTED,
                        version + 1, createdAt, null, null);
            }
            case PENDING_PAYMENT -> throw conflict("Reserved stock cannot be rejected");
            case CONFIRMED, STOCK_REJECTED, PAYMENT_FAILED -> this;
        };
    }

    public Order payment(DeferredPayment result) {
        Objects.requireNonNull(result);
        return switch (status) {
            case CREATED -> {
                if (deferredPayment != null) {
                    if (!deferredPayment.reservationEventId().equals(result.reservationEventId())
                            || !deferredPayment.paymentId().equals(result.paymentId())
                            || deferredPayment.authorized() != result.authorized()) {
                        throw conflict("Conflicting deferred payment result");
                    }
                    yield this;
                }
                // No public state transition before the causal reservation is known.
                yield new Order(id, tenantId, customerId, snapshot, status, version,
                        createdAt, null, result);
            }
            case PENDING_PAYMENT -> {
                if (!reservationEventId.equals(result.reservationEventId())) {
                    throw conflict("Payment does not follow this reservation");
                }
                yield new Order(id, tenantId, customerId, snapshot,
                        result.authorized() ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED,
                        version + 1, createdAt, reservationEventId, null);
            }
            // First terminal outcome wins; duplicates and contradictory late outcomes are inert.
            case CONFIRMED, STOCK_REJECTED, PAYMENT_FAILED -> this;
        };
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, "ORDER_EVENT_CONFLICT", message);
    }
}
