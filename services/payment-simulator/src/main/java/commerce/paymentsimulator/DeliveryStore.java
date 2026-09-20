package commerce.paymentsimulator;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    public DeliveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional public Optional<Delivery> claim() {
        return jdbc.query("""
                WITH due AS (SELECT id FROM provider_delivery WHERE delivered_at IS NULL
                    AND next_attempt_at <= CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE provider_delivery d SET lease_token = ?, lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                    attempts = LEAST(d.attempts + 1, 1000000) FROM due WHERE d.id = due.id RETURNING d.*
                """, (rs, row) -> new Delivery(rs.getObject("id", UUID.class), rs.getObject("object_id", UUID.class),
                rs.getString("event_type"), rs.getObject("lease_token", UUID.class), rs.getInt("attempts")),
                UUID.randomUUID()).stream().findFirst();
    }
    @Transactional public void finish(Delivery delivery, boolean success) {
        jdbc.update("""
                UPDATE provider_delivery SET delivered_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), lease_token = NULL, lease_until = NULL
                WHERE id = ? AND lease_token = ?
                """, success, Math.min(60, 1L << Math.min(6, delivery.attempts())), delivery.id(), delivery.token());
    }
    public record Delivery(UUID id, UUID objectId, String type, UUID token, int attempts) { }
}
