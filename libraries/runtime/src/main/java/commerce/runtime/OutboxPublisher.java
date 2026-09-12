package commerce.runtime;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "runtime.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<Object, Object> kafka;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final long timeoutMillis;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<Object, Object> kafka,
            PlatformTransactionManager transactionManager,
            @Value("${runtime.outbox.batch-size:25}") int batchSize,
            @Value("${runtime.events.publish-timeout-ms:5000}") long timeoutMillis) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Outbox batch size is outside safe bounds");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.timeoutMillis = EventsConfig.boundedTimeout(timeoutMillis);
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${runtime.outbox.poll-delay-ms:1000}")
    public void poll() {
        try {
            publishBatch();
        } catch (RuntimeException exception) {
            LOG.warn("Outbox batch not committed; will retry; exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * Holds only local DB claims while doing bounded broker sends, never provider calls.
     * A send followed by a crash/commit failure is deliberately redelivered (at least once).
     * Only the oldest committed unpublished record per aggregate is eligible. Services must
     * serialize mutations of the same aggregate in their own business transactions.
     */
    public int publishBatch() {
        Integer count = transaction.execute(status -> {
            JdbcTransactions.requireWritable(jdbc);
            List<Pending> pending = jdbc.query("""
                    SELECT o.event_id, o.aggregate_id, o.topic, o.payload::text
                    FROM outbox o
                    WHERE o.published_at IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM outbox previous
                        WHERE previous.aggregate_id = o.aggregate_id
                          AND previous.sequence < o.sequence AND previous.published_at IS NULL
                      )
                    ORDER BY o.sequence
                    LIMIT ? FOR UPDATE OF o SKIP LOCKED
                    """, (row, index) -> new Pending(row.getObject("event_id", UUID.class),
                            row.getString("aggregate_id"), row.getString("topic"), row.getString("payload")), batchSize);
            for (Pending event : pending) {
                try {
                    kafka.send(event.topic(), event.aggregateId(), event.payload()).get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Outbox publish interrupted");
                } catch (ExecutionException | TimeoutException exception) {
                    throw new IllegalStateException("Outbox publish was not acknowledged");
                }
                int changed = jdbc.update("UPDATE outbox SET published_at = clock_timestamp() WHERE event_id = ? AND published_at IS NULL",
                        event.eventId());
                if (changed != 1) {
                    throw new IllegalStateException("Outbox claim was lost");
                }
            }
            return pending.size();
        });
        return count == null ? 0 : count;
    }

    private record Pending(UUID eventId, String aggregateId, String topic, String payload) {
    }
}
