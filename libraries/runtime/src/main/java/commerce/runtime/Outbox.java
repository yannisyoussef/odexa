package commerce.runtime;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "runtime.events.enabled", havingValue = "true", matchIfMissing = true)
public class Outbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Outbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Participates in the caller's domain transaction; never creates a separate transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, UUID tenantId, String aggregateId, Object payload, UUID causationId) {
        JdbcTransactions.requireWritable(jdbc);
        Objects.requireNonNull(tenantId, "tenantId");
        if (aggregateId == null || aggregateId.isBlank() || aggregateId.length() > 128
                || aggregateId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid aggregate identifier");
        }
        Event event = new Event(UUID.randomUUID(), eventType, 1, Instant.now(),
                Correlation.current(), causationId, tenantId, mapper.valueToTree(payload));
        jdbc.update("""
                INSERT INTO outbox (event_id, tenant_id, aggregate_id, topic, payload, occurred_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, event.eventId(), tenantId, aggregateId, EventsConfig.TOPIC,
                mapper.writeValueAsString(event), Timestamp.from(event.occurredAt()));
        return event.eventId();
    }
}
