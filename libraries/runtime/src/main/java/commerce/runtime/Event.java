package commerce.runtime;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/** Version-one transport envelope; business payload validation belongs to each owning listener. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Event(UUID eventId, String eventType, int eventVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        String correlationId, UUID causationId, UUID tenantId, JsonNode payload) {
    private static final Set<String> TYPES = Set.of("order.created", "inventory.reserved",
            "inventory.rejected", "payment.authorized", "payment.declined", "order.confirmed", "order.rejected", "order.cancelled", "order.expired", "payment.refunded", "refund.failed");

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public Event {
        // Fixed initial contract. Unknown types/versions fail closed and must reach the DLT.
        if (eventId == null || eventType == null || !TYPES.contains(eventType) || eventVersion != 1
                || occurredAt == null || !Correlation.isUuid(correlationId) || tenantId == null
                || payload == null || !payload.isObject()) {
            throw invalid();
        }
    }

    /** Prevents application-wide Jackson coercion/unknown-property defaults from weakening the envelope. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Event fromJson(JsonNode json) {
        if (json == null || !json.isObject() || json.size() != (json.has("causationId") ? 8 : 7)) {
            throw invalid();
        }
        JsonNode version = json.get("eventVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() != 1) {
            throw invalid();
        }
        try {
            return new Event(uuid(json, "eventId"), text(json, "eventType"), 1,
                    Instant.parse(text(json, "occurredAt")), text(json, "correlationId"),
                    json.hasNonNull("causationId") ? uuid(json, "causationId") : null,
                    uuid(json, "tenantId"), json.get("payload"));
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static String text(JsonNode json, String name) {
        JsonNode value = json.get(name);
        if (value == null || !value.isString()) {
            throw invalid();
        }
        return value.asString();
    }

    private static UUID uuid(JsonNode json, String name) {
        String value = text(json, name);
        if (!Correlation.isUuid(value)) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported event envelope");
    }
}
