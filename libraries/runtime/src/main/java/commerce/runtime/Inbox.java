package commerce.runtime;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "runtime.events.enabled", havingValue = "true", matchIfMissing = true)
public class Inbox {
    private final JdbcTemplate jdbc;

    public Inbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Invoke inside the listener's business transaction BEFORE any domain changes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean first(UUID eventId, String consumer) {
        JdbcTransactions.requireWritable(jdbc);
        Objects.requireNonNull(eventId, "eventId");
        if (consumer == null || !consumer.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid consumer identifier");
        }
        return jdbc.update("""
                INSERT INTO inbox (consumer, event_id) VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId) == 1;
    }
}
