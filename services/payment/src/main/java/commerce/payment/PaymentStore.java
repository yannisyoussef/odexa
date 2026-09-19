package commerce.payment;

import commerce.runtime.ApiException;
import commerce.runtime.Event;
import commerce.runtime.Outbox;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final RetryPolicy retry;
    private final String provider;

    public PaymentStore(JdbcTemplate jdbc, Outbox outbox, RetryPolicy retry) {
        this(jdbc, outbox, retry, "simulator");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentStore(JdbcTemplate jdbc, Outbox outbox, RetryPolicy retry,
            @org.springframework.beans.factory.annotation.Value("${payment.provider:simulator}") String provider) {
        if (!java.util.Set.of("simulator", "stripe").contains(provider)) {
            throw new IllegalArgumentException("Unknown payment provider");
        }
        this.provider = provider;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.retry = retry;
    }

    @Transactional
    public void enqueue(Event event, ReservedPayment payment) {
        int inserted = jdbc.update("""
                INSERT INTO payment (id, tenant_id, order_id, customer_id, amount_minor, currency,
                    payment_method, reservation_event_id, correlation_id, provider)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, order_id) DO NOTHING
                """, UUID.randomUUID(), event.tenantId(), payment.orderId(), payment.customerId(),
                payment.totalMinor(), payment.currency(), payment.paymentMethod(), event.eventId(),
                event.correlationId(), provider);
        if (inserted == 0) {
            Boolean same = jdbc.queryForObject("""
                    SELECT customer_id = ? AND amount_minor = ? AND currency = ? AND payment_method = ?
                    FROM payment WHERE tenant_id = ? AND order_id = ?
                    """, Boolean.class, payment.customerId(), payment.totalMinor(), payment.currency(),
                    payment.paymentMethod(), event.tenantId(), payment.orderId());
            if (!Boolean.TRUE.equals(same)) throw new IllegalArgumentException("Conflicting reservation event");
        }
    }

    /** Each transaction claims at most one item; SKIP LOCKED coordinates independent JVMs. */
    @Transactional
    public Optional<Claim> claim() {
        // A process that dies on its final attempt must not leave an immortal pending job.
        jdbc.update("""
                WITH expired AS (
                    SELECT id FROM payment WHERE status = 'PENDING' AND (attempts >= ? OR created_at < CURRENT_TIMESTAMP - INTERVAL '23 hours')
                      AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at LIMIT 100 FOR UPDATE SKIP LOCKED
                ) UPDATE payment p SET status = 'REVIEW_REQUIRED', lease_token = NULL,
                    lease_until = NULL, next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP
                  FROM expired WHERE p.id = expired.id
                """, retry.maxAttempts());
        UUID token = UUID.randomUUID();
        List<Claim> claims = jdbc.query("""
                WITH due AS (
                    SELECT id FROM payment WHERE ((status = 'PENDING' AND attempts < ?
                        AND created_at >= CURRENT_TIMESTAMP - INTERVAL '23 hours') OR status = 'REVIEW_REQUIRED')
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                ) UPDATE payment p SET attempts = p.attempts + CASE WHEN p.status = 'PENDING' THEN 1 ELSE 0 END,
                    reconciliation_attempts = LEAST(p.reconciliation_attempts + 1, 1000000), lease_token = ?,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                  FROM due WHERE p.id = due.id RETURNING p.*
                """, (rs, row) -> new Claim(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("payment_method"),
                rs.getInt("attempts"), rs.getObject("lease_token", UUID.class),
                rs.getObject("reservation_event_id", UUID.class), rs.getString("correlation_id"),
                rs.getString("provider"), rs.getString("provider_id"), rs.getString("status"),
                rs.getInt("reconciliation_attempts")),
                retry.maxAttempts(), token, retry.leaseSeconds());
        return claims.stream().findFirst();
    }

    @Transactional
    public boolean complete(Claim claim, PaymentProvider.Result result) {
        int updated = jdbc.update("""
                UPDATE payment SET status = ?, provider_id = ?, lease_token = NULL, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('PENDING', 'REVIEW_REQUIRED') AND lease_token = ?
                    AND (provider_id IS NULL OR provider_id = ?)
                """, result.outcome().name(), result.providerId(), claim.id(), claim.leaseToken(), result.providerId());
        if (updated == 0) return false; // Late result from an expired/reassigned worker is fenced out.
        if (result.outcome() == PaymentProvider.Outcome.REVIEW_REQUIRED) return true;
        String type = result.outcome() == PaymentProvider.Outcome.AUTHORIZED
                ? "payment.authorized" : "payment.declined";
        outbox.append(type, claim.tenantId(), claim.orderId().toString(),
                Map.of("orderId", claim.orderId(), "paymentId", claim.id()), claim.reservationEventId());
        return true;
    }

    @Transactional
    public void uncertain(Claim claim) {
        jdbc.update("""
                UPDATE payment SET status = ?, lease_token = NULL, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('PENDING', 'REVIEW_REQUIRED') AND lease_token = ?
                """, claim.reconciling() || retry.exhausted(claim.attempts()) ? "REVIEW_REQUIRED" : "PENDING",
                retry.delay(claim.reconciling() ? claim.reconciliationAttempts() : claim.attempts()).toSeconds(), claim.id(), claim.leaseToken());
    }

    @Transactional(readOnly = true)
    public View get(UUID tenantId, String customerId, UUID orderId) {
        return jdbc.query("""
                SELECT id, order_id, amount_minor, currency, status, created_at, updated_at
                FROM payment WHERE tenant_id = ? AND customer_id = ? AND order_id = ?
                """, (rs, row) -> new View(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenantId, customerId, orderId).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "PAYMENT_NOT_FOUND", "Payment not found"));
    }

    public record Claim(UUID id, UUID tenantId, UUID orderId, long amountMinor, String currency,
                        String paymentMethod, int attempts, UUID leaseToken, UUID reservationEventId,
                        String correlationId, String provider, String providerId, String status, int reconciliationAttempts) {
        public Claim(UUID id, UUID tenantId, UUID orderId, long amountMinor, String currency,
                     String paymentMethod, int attempts, UUID leaseToken, UUID reservationEventId, String correlationId) {
            this(id, tenantId, orderId, amountMinor, currency, paymentMethod, attempts, leaseToken,
                 reservationEventId, correlationId, "simulator", null, "PENDING", 0);
        }
        boolean reconciling() { return "REVIEW_REQUIRED".equals(status); }
        PaymentProvider.Request request() {
            return new PaymentProvider.Request(orderId, amountMinor, currency, paymentMethod, provider);
        }
    }
    public record View(UUID id, UUID orderId, long amountMinor, String currency, String status,
                       Instant createdAt, Instant updatedAt) { }
}
