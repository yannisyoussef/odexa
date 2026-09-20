package commerce.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProviderEvents {
    private final JdbcTemplate jdbc;
    public ProviderEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void accept(String provider, String id, String type, String objectId) {
        int inserted = jdbc.update("""
                INSERT INTO provider_event(provider, event_id, event_type, object_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (provider, event_id) DO NOTHING
                """, provider, id, type, objectId);
        if (inserted == 0 || objectId == null) return;
        // Verified notifications schedule authenticated lookups; snapshot state is never trusted.
        jdbc.update("""
                UPDATE payment SET next_attempt_at = CURRENT_TIMESTAMP WHERE provider = ? AND provider_id = ?
                AND status = 'REVIEW_REQUIRED'
                """, provider, objectId);
        jdbc.update("""
                UPDATE refund r SET next_attempt_at = CURRENT_TIMESTAMP FROM payment p
                WHERE r.payment_id = p.id AND p.provider = ? AND r.provider_id = ?
                AND r.status = 'REVIEW_REQUIRED'
                """, provider, objectId);
    }
}
