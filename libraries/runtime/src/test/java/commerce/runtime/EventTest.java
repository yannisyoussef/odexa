package commerce.runtime;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsVersionOneEnvelopeWithOptionalCausation() {
        Event event = sample();
        Event decoded = mapper.readValue(mapper.writeValueAsString(event), Event.class);
        assertThat(decoded).isEqualTo(event);
        ObjectNode json = mapper.valueToTree(event);
        json.remove("causationId");
        assertThat(mapper.readValue(mapper.writeValueAsString(json), Event.class).causationId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "eventVersion", "occurredAt", "correlationId", "tenantId", "payload"})
    void rejectsMissingRequiredEnvelopeFields(String field) {
        ObjectNode json = mapper.valueToTree(sample());
        json.remove(field);
        assertInvalid(json);
    }

    @Test
    void rejectsUnknownVersionTypeFieldsAndCoercions() {
        ObjectNode json = mapper.valueToTree(sample());
        json.put("eventVersion", 2);
        assertInvalid(json);
        json.put("eventVersion", "1");
        assertInvalid(json);
        json.put("eventVersion", 1.0);
        assertInvalid(json);
        json.put("eventVersion", 1);
        json.put("eventType", "unknown.event");
        assertInvalid(json);
        json.put("eventType", "order.created");
        json.put("unexpected", true);
        assertInvalid(json);
    }

    @Test
    void rejectsShortUuidInvalidTimestampAndNonObjectPayload() {
        ObjectNode json = mapper.valueToTree(sample());
        json.put("tenantId", "1-1-1-1-1");
        assertInvalid(json);
        json = mapper.valueToTree(sample());
        json.put("occurredAt", "untrusted-value");
        assertInvalid(json);
        json = mapper.valueToTree(sample());
        json.put("payload", "untrusted-value");
        assertInvalid(json);
        json = mapper.valueToTree(sample());
        json.put("correlationId", "untrusted-value");
        assertInvalid(json);
    }

    @Test
    void allowsOnlyDocumentedNonOwnedTypesRatherThanSilentlyAcceptingUnknownEvents() {
        for (String type : new String[]{"order.created", "inventory.reserved", "inventory.rejected",
                "payment.authorized", "payment.declined", "order.confirmed", "order.rejected", "order.cancelled", "order.expired", "payment.refunded", "refund.failed"}) {
            ObjectNode json = mapper.valueToTree(sample());
            json.put("eventType", type);
            assertThat(mapper.readValue(mapper.writeValueAsString(json), Event.class).eventType()).isEqualTo(type);
        }
    }

    private void assertInvalid(ObjectNode json) {
        String raw = mapper.writeValueAsString(json);
        assertThatThrownBy(() -> mapper.readValue(raw, Event.class)).isInstanceOf(RuntimeException.class);
    }

    private Event sample() {
        return new Event(UUID.randomUUID(), "order.created", 1, Instant.parse("2026-09-12T12:00:00Z"),
                UUID.randomUUID().toString(), null, UUID.randomUUID(), mapper.createObjectNode().put("quantity", 1));
    }
}
