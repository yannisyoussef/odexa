package commerce.order;

import commerce.runtime.Event;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reject malformed/unknown events so the runtime's bounded retry and DLT policy can recover them. */
final class OrderEventDecoder {
    private static final Set<String> IGNORED = Set.of("order.created", "order.confirmed", "order.rejected");

    private OrderEventDecoder() { }

    static Decoded decode(Event event) {
        if (event == null || event.eventId() == null || event.tenantId() == null
                || event.eventVersion() != 1 || event.eventType() == null || event.occurredAt() == null
                || event.payload() == null || !event.payload().isObject()) {
            throw invalid();
        }
        uuid(event.correlationId());
        if (IGNORED.contains(event.eventType())) {
            return new Decoded(event, null, null, null);
        }
        if (!Set.of("inventory.reserved", "inventory.rejected", "payment.authorized", "payment.declined")
                .contains(event.eventType()) || event.causationId() == null) {
            throw invalid();
        }
        JsonNode payload = event.payload();
        UUID orderId = uuid(text(payload, "orderId"));
        return switch (event.eventType()) {
            case "inventory.reserved" -> {
                UUID productId = uuid(text(payload, "productId"));
                String customer = text(payload, "customerId");
                String currency = text(payload, "currency");
                String method = text(payload, "paymentMethod");
                long quantity = number(payload, "quantity");
                long total = number(payload, "totalMinor");
                if (customer.length() > 255 || quantity < 1 || quantity > 100 || total <= 0
                        || !"USD".equals(currency)
                        || !("pm_approved".equals(method) || "pm_declined".equals(method))) {
                    throw invalid();
                }
                if (payload.has("reservationId") && !orderId.equals(uuid(text(payload, "reservationId")))) {
                    throw invalid();
                }
                yield new Decoded(event, orderId,
                        new Reservation(customer, productId, (int) quantity, total, currency, method), null);
            }
            case "inventory.rejected" -> {
                if (!"INSUFFICIENT_STOCK".equals(text(payload, "reason"))) {
                    throw invalid();
                }
                yield new Decoded(event, orderId, null, null);
            }
            case "payment.authorized", "payment.declined" -> {
                UUID paymentId = uuid(text(payload, "paymentId"));
                if (payload.has("reason") && text(payload, "reason").length() > 256) {
                    throw invalid();
                }
                yield new Decoded(event, orderId, null, paymentId);
            }
            default -> throw invalid();
        };
    }

    private static String text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw invalid();
        }
        return value.asString();
    }

    private static long number(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.asLong();
    }

    private static UUID uuid(String value) {
        if (value == null || value.length() != 36) {
            throw invalid();
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid commerce event");
    }

    record Decoded(Event event, UUID orderId, Reservation reservation, UUID paymentId) {
        boolean ignored() { return orderId == null; }
    }

    record Reservation(String customerId, UUID productId, int quantity, long totalMinor,
                       String currency, String paymentMethod) {
        boolean matches(Order order) {
            CheckoutSnapshot snapshot = order.snapshot();
            return customerId.equals(order.customerId()) && productId.equals(snapshot.productId())
                    && quantity == snapshot.quantity() && totalMinor == snapshot.totalMinor()
                    && currency.equals(snapshot.currency()) && paymentMethod.equals(snapshot.paymentMethod());
        }
    }
}
