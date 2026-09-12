package commerce.inventory;

import commerce.runtime.Correlation;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryEventsTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Inbox inbox = mock(Inbox.class);
    private final ReservationService reservations = mock(ReservationService.class);
    private final InventoryEvents listener = new InventoryEvents(mapper, inbox, reservations);
    private final UUID tenant = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final OrderCreated order = new OrderCreated(orderId, "customer-a", UUID.randomUUID(), 2, 5000, "USD", "pm_approved");

    @AfterEach
    void cleanMdc() {
        MDC.remove(Correlation.MDC_KEY);
    }

    @Test
    void dispatchesAllThreeOwnedTypesWithOneConsumerAndDeduplicates() {
        when(inbox.first(any(), eq(InventoryEvents.CONSUMER))).thenReturn(true);
        Event created = event("order.created", order);
        listener.onEvent(record(created));
        verify(reservations).reserve(tenant, order, created.eventId());
        Event authorized = event("payment.authorized", payment(), UUID.randomUUID());
        Event declined = event("payment.declined", payment(), UUID.randomUUID());
        listener.onEvent(record(authorized));
        listener.onEvent(record(declined));
        verify(reservations).settle(tenant, orderId, true, authorized.causationId());
        verify(reservations).settle(tenant, orderId, false, declined.causationId());
        when(inbox.first(created.eventId(), InventoryEvents.CONSUMER)).thenReturn(false);
        listener.onEvent(record(created));
        verify(reservations, times(1)).reserve(tenant, order, created.eventId());
    }

    @Test
    void passesMissingPaymentCausationToSettlementValidation() {
        when(inbox.first(any(), eq(InventoryEvents.CONSUMER))).thenReturn(true);
        doThrow(new IllegalArgumentException("Payment causation must match the reservation event"))
                .when(reservations).settle(eq(tenant), eq(orderId), anyBoolean(), isNull());
        for (String type : new String[]{"payment.authorized", "payment.declined"}) {
            assertThrows(IllegalArgumentException.class, () -> listener.onEvent(record(event(type, payment()))));
        }
        verify(reservations).settle(tenant, orderId, true, null);
        verify(reservations).settle(tenant, orderId, false, null);
    }

    @Test
    void ignoresKnownNonOwnedEventsWithoutWritingInbox() {
        for (String type : new String[]{"inventory.reserved", "inventory.rejected", "order.confirmed", "order.rejected"}) {
            listener.onEvent(record(event(type, Map.of("orderId", orderId))));
        }
        verifyNoInteractions(inbox, reservations);
    }

    @Test
    void invalidEnvelopeVersionTypePayloadAndKeyFailBeforeInbox() {
        var valid = record(event("order.created", order));
        assertThrows(IllegalArgumentException.class,
                () -> listener.onEvent(new ConsumerRecord<>("commerce.events.v1", 0, 0, orderId.toString(), "not-json")));
        for (String field : new String[]{"eventVersion", "eventType", "payload"}) {
            ObjectNode node = (ObjectNode) mapper.readTree(valid.value());
            switch (field) {
                case "eventVersion" -> node.put(field, 2);
                case "eventType" -> node.put(field, "unknown.event");
                default -> node.putNull(field);
            }
            assertThrows(IllegalArgumentException.class, () -> listener.onEvent(raw(mapper.writeValueAsString(node))));
        }
        assertThrows(IllegalArgumentException.class, () -> listener.onEvent(
                new ConsumerRecord<>("commerce.events.v1", 0, 0, UUID.randomUUID().toString(), valid.value())));
        verifyNoInteractions(inbox, reservations);
    }

    @Test
    void rejectsCoercedAndOutOfRangeOwnedPayloads() {
        for (Object quantity : new Object[]{0, 101, "2", 1.5, Long.MAX_VALUE}) {
            ObjectNode payload = (ObjectNode) mapper.valueToTree(order);
            payload.set("quantity", mapper.valueToTree(quantity));
            assertThrows(IllegalArgumentException.class, () -> listener.onEvent(record(event("order.created", payload))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> listener.onEvent(record(event("payment.authorized", Map.of("orderId", orderId)))));
        verifyNoInteractions(inbox, reservations);
    }

    @Test
    void acceptsCustomerIdsThrough255CharactersAndRejectsLongerIdsBeforeInbox() {
        ObjectNode payload = (ObjectNode) mapper.valueToTree(order);
        for (int length : new int[]{200, 201, 255}) {
            String customerId = "c".repeat(length);
            payload.put("customerId", customerId);
            assertEquals(customerId, InventoryEvents.readOrder(payload).customerId());
        }
        payload.put("customerId", "c".repeat(256));
        assertThrows(IllegalArgumentException.class, () -> listener.onEvent(record(event("order.created", payload))));
        verifyNoInteractions(inbox, reservations);
    }

    @Test
    void restoresCorrelationEvenWhenBusinessProcessingFails() {
        String previous = UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, previous);
        Event created = event("order.created", order);
        when(inbox.first(created.eventId(), InventoryEvents.CONSUMER)).thenReturn(true);
        doAnswer(invocation -> {
            assertEquals(created.correlationId(), Correlation.current());
            throw new IllegalStateException("injected failure");
        }).when(reservations).reserve(tenant, order, created.eventId());
        assertThrows(IllegalStateException.class, () -> listener.onEvent(record(created)));
        assertEquals(previous, MDC.get(Correlation.MDC_KEY));
    }

    private Map<String, Object> payment() {
        return Map.of("orderId", orderId, "paymentId", UUID.randomUUID());
    }

    private Event event(String type, Object payload) {
        return event(type, payload, null);
    }

    private Event event(String type, Object payload, UUID causationId) {
        return new Event(UUID.randomUUID(), type, 1, Instant.now(), UUID.randomUUID().toString(),
                causationId, tenant, mapper.valueToTree(payload));
    }

    private ConsumerRecord<String, String> record(Event event) {
        return raw(mapper.writeValueAsString(event));
    }

    private ConsumerRecord<String, String> raw(String value) {
        return new ConsumerRecord<>("commerce.events.v1", 0, 0, orderId.toString(), value);
    }
}
