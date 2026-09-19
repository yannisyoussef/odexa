package commerce.payment;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import commerce.runtime.Correlation;
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
public class RefundStore {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final RetryPolicy retry;
    public RefundStore(JdbcTemplate jdbc, Outbox outbox, RetryPolicy retry) {
        this.jdbc = jdbc; this.outbox = outbox; this.retry = retry;
    }

    @Transactional
    public Created create(Actor actor, UUID orderId, String key, long amount, String currency) {
        actor.requireRole("MERCHANT_ADMIN", "MERCHANT_USER");
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}") || amount <= 0 || !"USD".equals(currency)) {
            throw new ApiException(400, "INVALID_REFUND", "Invalid refund request");
        }
        var payment = jdbc.query("SELECT * FROM payment WHERE tenant_id = ? AND order_id = ? FOR UPDATE",
                (rs, row) -> new Payment(rs.getObject("id", UUID.class), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("status")), actor.tenantId(), orderId)
                .stream().findFirst().orElseThrow(RefundStore::notFound);
        var existing = jdbc.query("SELECT * FROM refund WHERE payment_id = ? AND idempotency_key = ?",
                (rs, row) -> view(rs, orderId), payment.id(), key);
        if (!existing.isEmpty()) {
            var view = existing.getFirst();
            if (view.amountMinor() != amount || !view.currency().equals(currency)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Key was used for a different refund");
            }
            return new Created(view, false);
        }
        if (!payment.status().equals("AUTHORIZED")) throw conflict("PAYMENT_NOT_AUTHORIZED", "Payment is not authorized");
        if (amount != payment.amount() || !currency.equals(payment.currency())) {
            throw conflict("FULL_REFUND_REQUIRED", "Only a full payment refund is supported");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM refund WHERE payment_id = ? AND status <> 'FAILED')",
                Boolean.class, payment.id()))) {
            throw conflict("REFUND_ALREADY_EXISTS", "An active or successful refund already exists");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refund(id, payment_id, idempotency_key, amount_minor, currency, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, payment.id(), key, amount, currency, Correlation.current());
        return new Created(jdbc.queryForObject("SELECT * FROM refund WHERE id = ?",
                (rs, row) -> view(rs, orderId), id), true);
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, UUID orderId, boolean merchant, UUID after) {
        UUID paymentId = visiblePayment(actor, orderId, merchant);
        // UUID ordering supplies a bounded stable traversal without accepting arbitrary SQL/offsets.
        var rows = jdbc.query("""
                SELECT * FROM refund WHERE payment_id = ? AND (?::uuid IS NULL OR id > ?::uuid)
                ORDER BY id LIMIT 51
                """, (rs, row) -> view(rs, orderId), paymentId, after, after);
        boolean more = rows.size() > 50;
        List<View> items = more ? rows.subList(0, 50) : rows;
        return new Page(items, more ? items.getLast().id() : null);
    }
    @Transactional(readOnly = true)
    public View get(Actor actor, UUID orderId, UUID id, boolean merchant) {
        UUID paymentId = visiblePayment(actor, orderId, merchant);
        return jdbc.query("SELECT * FROM refund WHERE payment_id = ? AND id = ?",
                (rs, row) -> view(rs, orderId), paymentId, id).stream().findFirst().orElseThrow(RefundStore::notFound);
    }
    private UUID visiblePayment(Actor actor, UUID order, boolean merchant) {
        if (merchant) actor.requireRole("MERCHANT_ADMIN", "MERCHANT_USER");
        return jdbc.query("""
                SELECT id FROM payment WHERE tenant_id = ? AND order_id = ? AND (? OR customer_id = ?)
                """, (rs, row) -> rs.getObject("id", UUID.class), actor.tenantId(), order, merchant, actor.subject())
                .stream().findFirst().orElseThrow(RefundStore::notFound);
    }

    @Transactional
    public Optional<Claim> claim() {
        jdbc.update("""
                WITH expired AS (
                    SELECT id FROM refund WHERE status = 'PENDING'
                    AND (attempts >= ? OR created_at < CURRENT_TIMESTAMP - INTERVAL '23 hours')
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at LIMIT 100 FOR UPDATE SKIP LOCKED
                ) UPDATE refund r SET status = 'REVIEW_REQUIRED', lease_token = NULL, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP
                  FROM expired WHERE r.id = expired.id
                """, retry.maxAttempts());
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM refund WHERE status IN ('PENDING', 'REVIEW_REQUIRED')
                    AND next_attempt_at <= CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE refund r SET attempts = r.attempts + CASE WHEN r.status = 'PENDING' THEN 1 ELSE 0 END,
                    reconciliation_attempts = LEAST(r.reconciliation_attempts + 1, 1000000), lease_token = ?,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                    FROM due WHERE r.id = due.id RETURNING r.*
                ) SELECT r.*, p.tenant_id, p.order_id, p.provider, p.provider_id AS payment_provider_id
                  FROM claimed r JOIN payment p ON p.id = r.payment_id
                """, (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("provider"),
                rs.getString("payment_provider_id"), rs.getString("provider_id"), rs.getString("status"),
                rs.getInt("attempts"), rs.getInt("reconciliation_attempts"), rs.getObject("lease_token", UUID.class),
                rs.getString("correlation_id")), UUID.randomUUID(), retry.leaseSeconds()).stream().findFirst();
    }
    @Transactional
    public boolean complete(Claim claim, PaymentProvider.RefundResult result) {
        int updated = jdbc.update("""
                UPDATE refund SET status = ?, provider_id = ?, lease_token = NULL, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '60 seconds', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND lease_token = ? AND status IN ('PENDING', 'REVIEW_REQUIRED')
                    AND (provider_id IS NULL OR provider_id = ?)
                """, result.outcome().name(), result.providerId(), claim.id(), claim.leaseToken(), result.providerId());
        if (updated == 0) return false;
        if (result.outcome() != PaymentProvider.RefundOutcome.REVIEW_REQUIRED) {
            outbox.append(result.outcome() == PaymentProvider.RefundOutcome.SUCCEEDED ? "payment.refunded" : "refund.failed",
                    claim.tenantId(), claim.orderId().toString(), Map.of("orderId", claim.orderId(),
                    "paymentId", claim.paymentId(), "refundId", claim.id(), "amountMinor", claim.amountMinor(),
                    "currency", claim.currency()), claim.id());
        }
        return true;
    }
    @Transactional
    public void uncertain(Claim claim) {
        jdbc.update("""
                UPDATE refund SET status = ?, lease_token = NULL, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND lease_token = ? AND status IN ('PENDING', 'REVIEW_REQUIRED')
                """, claim.reconciling() || retry.exhausted(claim.attempts()) ? "REVIEW_REQUIRED" : "PENDING",
                retry.delay(claim.reconciling() ? claim.reconciliationAttempts() : claim.attempts()).toSeconds(),
                claim.id(), claim.leaseToken());
    }
    private static View view(java.sql.ResultSet rs, UUID order) throws java.sql.SQLException {
        return new View(rs.getObject("id", UUID.class), order, rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private static ApiException notFound() { return new ApiException(404, "REFUND_NOT_FOUND", "Refund or payment not found"); }
    private static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    private record Payment(UUID id, long amount, String currency, String status) { }
    public record Created(View view, boolean initial) { }
    public record View(UUID id, UUID orderId, long amountMinor, String currency, String status,
                       Instant createdAt, Instant updatedAt) { }
    public record Page(List<View> items, UUID nextCursor) { }
    public record Claim(UUID id, UUID paymentId, UUID tenantId, UUID orderId, long amountMinor, String currency,
                        String provider, String paymentProviderId, String providerId, String status,
                        int attempts, int reconciliationAttempts, UUID leaseToken, String correlationId) {
        boolean reconciling() { return "REVIEW_REQUIRED".equals(status); }
        PaymentProvider.RefundRequest request() {
            return new PaymentProvider.RefundRequest(id, orderId, paymentProviderId, amountMinor, currency, provider);
        }
    }
}
