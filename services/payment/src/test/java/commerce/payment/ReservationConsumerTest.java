package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReservationConsumerTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Inbox inbox = mock(Inbox.class);
    private final PaymentStore store = mock(PaymentStore.class);
    private final ReservationConsumer consumer = new ReservationConsumer(mapper, inbox, store);

    @Test void malformedUnknownAndUnsupportedVersionsFailWithoutInbox() {
        assertThrows(IllegalArgumentException.class, () -> consumer.receive("not-json"));
        Event event = event("inventory.reserved", Map.of("orderId", UUID.randomUUID()));
        String raw = mapper.writeValueAsString(event);
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(raw));
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(raw.replace("inventory.reserved", "unknown.event")));
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(raw.replace("\"eventVersion\":1", "\"eventVersion\":2")));
        verifyNoInteractions(inbox, store);
    }

    @Test void knownUnownedEventsAreIgnored() {
        consumer.receive(mapper.writeValueAsString(event("payment.authorized", Map.of("orderId", UUID.randomUUID()))));
        verifyNoInteractions(inbox, store);
    }

    @Test void validSnapshotIsValidatedBeforeInboxAndDuplicateDoesNotEnqueue() {
        Event event = event("inventory.reserved", Map.of("orderId", UUID.randomUUID(), "productId", UUID.randomUUID(),
                "customerId", "customer-a", "quantity", 1, "totalMinor", 2500, "currency", "USD", "paymentMethod", "pm_approved"));
        when(inbox.first(event.eventId(), "payment.inventory-reserved.v1")).thenReturn(true, false);
        consumer.receive(mapper.writeValueAsString(event));
        consumer.receive(mapper.writeValueAsString(event));
        verify(store).enqueue(event, ReservedPayment.parse(event.payload()));
    }

    private Event event(String type, Map<String, ?> payload) {
        return new Event(UUID.randomUUID(), type, 1, Instant.now(), UUID.randomUUID().toString(), null,
                UUID.randomUUID(), mapper.valueToTree(payload));
    }
}
