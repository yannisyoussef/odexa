package commerce.payment;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Trusted reservation snapshot, validated before inserting inbox or work. */
public record ReservedPayment(UUID orderId, String customerId, long totalMinor,
                              String currency, String paymentMethod) {
    static ReservedPayment parse(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        UUID orderId = uuid(node, "orderId");
        uuid(node, "productId");
        JsonNode quantity = node.get("quantity");
        JsonNode total = node.get("totalMinor");
        if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()
                || quantity.intValue() < 1 || quantity.intValue() > 100
                || total == null || !total.isIntegralNumber() || !total.canConvertToLong()
                || total.longValue() <= 0) throw invalid();
        if (node.has("reservationId") && !orderId.equals(uuid(node, "reservationId"))) throw invalid();
        String customer = text(node, "customerId", 255);
        String currency = text(node, "currency", 3);
        String method = text(node, "paymentMethod", 128);
        if (!"USD".equals(currency) || !("pm_approved".equals(method) || "pm_declined".equals(method))) {
            throw invalid();
        }
        return new ReservedPayment(orderId, customer, total.longValue(), currency, method);
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field, 36);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw invalid();
            return parsed;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()
                || value.stringValue().length() > limit) throw invalid();
        return value.stringValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid inventory reservation event");
    }
}
