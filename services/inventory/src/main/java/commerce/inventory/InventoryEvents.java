package commerce.inventory;

import commerce.runtime.Correlation;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class InventoryEvents {
    static final String CONSUMER = "odexa-inventory-v1";
    private static final Set<String> OTHER_EVENTS = Set.of(
            "inventory.reserved", "inventory.rejected", "order.confirmed", "order.rejected", "order.cancelled", "order.expired", "payment.refunded", "refund.failed");
    private final ObjectMapper mapper;
    private final Inbox inbox;
    private final ReservationService reservations;

    public InventoryEvents(ObjectMapper mapper, Inbox inbox, ReservationService reservations) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.reservations = reservations;
    }

    // One group and one handler consume all three owned types, preserving per-order partition order.
    // Listener transaction also contains inbox, reservation, stock, and the reservation outcome outbox.
    @Transactional
    @KafkaListener(topics = "commerce.events.v1", groupId = CONSUMER)
    public void onEvent(ConsumerRecord<String, String> record) {
        Event event;
        try {
            event = mapper.readValue(record.value(), Event.class);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("Malformed event envelope");
        }
        validateEnvelope(event);
        if (OTHER_EVENTS.contains(event.eventType())) {
            return;
        }
        OrderCreated order = null;
        UUID orderId;
        switch (event.eventType()) {
            case "order.created" -> {
                order = readOrder(event.payload());
                orderId = order.orderId();
            }
            case "payment.authorized", "payment.declined" -> {
                orderId = uuid(text(event.payload(), "orderId", 36));
                uuid(text(event.payload(), "paymentId", 36));
            }
            default -> throw new IllegalArgumentException("Unknown event type");
        }
        if (!orderId.toString().equals(record.key())) {
            throw new IllegalArgumentException("Event key must equal order ID");
        }
        String previousCorrelation = MDC.get(Correlation.MDC_KEY);
        MDC.put(Correlation.MDC_KEY, event.correlationId());
        try {
            if (!inbox.first(event.eventId(), CONSUMER)) {
                return;
            }
            if (order != null) {
                reservations.reserve(event.tenantId(), order, event.eventId());
            } else {
                reservations.settle(event.tenantId(), orderId, event.eventType().equals("payment.authorized"),
                        event.causationId());
            }
        } finally {
            if (previousCorrelation == null) {
                MDC.remove(Correlation.MDC_KEY);
            } else {
                MDC.put(Correlation.MDC_KEY, previousCorrelation);
            }
        }
    }

    private static void validateEnvelope(Event event) {
        if (event == null || event.eventVersion() != 1 || event.eventId() == null || event.tenantId() == null
                || event.occurredAt() == null || event.eventType() == null || event.payload() == null
                || !event.payload().isObject()) {
            throw new IllegalArgumentException("Invalid event envelope or unsupported version");
        }
        uuid(event.correlationId());
    }

    static OrderCreated readOrder(JsonNode payload) {
        UUID orderId = uuid(text(payload, "orderId", 36));
        UUID productId = uuid(text(payload, "productId", 36));
        long quantity = number(payload, "quantity");
        long total = number(payload, "totalMinor");
        String currency = text(payload, "currency", 3);
        if (quantity < 1 || quantity > 100 || total < 0 || !"USD".equals(currency)) {
            throw new IllegalArgumentException("Invalid order quantity, amount or currency");
        }
        return new OrderCreated(orderId, text(payload, "customerId", 255), productId, (int) quantity,
                total, currency, text(payload, "paymentMethod", 200));
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Missing or invalid numeric event field");
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > maxLength) {
            throw new IllegalArgumentException("Missing or invalid text event field");
        }
        return value.stringValue();
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Invalid event UUID");
        }
        return UUID.fromString(value);
    }
}
