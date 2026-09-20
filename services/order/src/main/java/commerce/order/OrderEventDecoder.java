package commerce.order;

import commerce.runtime.Event;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reject malformed/unknown events so the runtime's bounded retry and DLT policy can recover them. */
final class OrderEventDecoder {
    private static final Set<String> IGNORED = Set.of("order.created", "order.confirmed", "order.rejected", "order.cancelled", "order.expired", "payment.refunded", "refund.failed");

    private OrderEventDecoder() { }

    static Decoded decode(Event event) {
        if (event == null || event.eventId() == null || event.tenantId() == null
                || !Event.supports(event.eventType(), event.eventVersion()) || event.eventType() == null || event.occurredAt() == null
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
                var lines = new java.util.ArrayList<CheckoutRequest.Item>();
                if (event.eventVersion() == 1) {
                    if (payload.has("items")) throw invalid();
                    lines.add(line(payload));
                } else {
                    JsonNode items = payload.get("items");
                    if (items == null || !items.isArray() || items.isEmpty() || items.size() > 20
                            || payload.has("productId") || payload.has("quantity")) throw invalid();
                    for (JsonNode item : items) lines.add(line(item));
                }
                if (lines.stream().map(CheckoutRequest.Item::productId).distinct().count() != lines.size()) throw invalid();
                lines.sort(java.util.Comparator.comparing(i -> i.productId().toString()));
                String customer = text(payload, "customerId");
                String currency = text(payload, "currency");
                String method = text(payload, "paymentMethod");
                long total = number(payload, "totalMinor");
                if (customer.length() > 255 || total <= 0
                        || !"USD".equals(currency)
                        || !method.matches("pm_[A-Za-z0-9_]{1,125}")) {
                    throw invalid();
                }
                if (payload.has("reservationId") && !orderId.equals(uuid(text(payload, "reservationId")))) {
                    throw invalid();
                }
                yield new Decoded(event, orderId,
                        new Reservation(customer, java.util.List.copyOf(lines), total, currency, method), null);
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

    private static CheckoutRequest.Item line(JsonNode node) {
        long quantity = number(node, "quantity");
        if (quantity < 1 || quantity > 100) throw invalid();
        return new CheckoutRequest.Item(uuid(text(node, "productId")), (int) quantity);
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

    record Reservation(String customerId, java.util.List<CheckoutRequest.Item> items, long totalMinor,
                       String currency, String paymentMethod) {
        boolean matches(Order order) {
            CheckoutSnapshot snapshot = order.snapshot();
            return customerId.equals(order.customerId()) && items.equals(snapshot.items().stream()
                    .map(i -> new CheckoutRequest.Item(i.productId(), i.quantity())).toList()) && totalMinor == snapshot.totalMinor()
                    && currency.equals(snapshot.currency()) && paymentMethod.equals(snapshot.paymentMethod());
        }
    }
}
