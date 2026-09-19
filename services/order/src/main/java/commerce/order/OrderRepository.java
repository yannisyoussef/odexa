package commerce.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredOrder> findByKey(UUID tenantId, String customerId, String key) {
        return jdbc.query("""
                SELECT * FROM customer_order
                WHERE tenant_id = ? AND customer_id = ? AND idempotency_key = ?
                """, (rs, row) -> new StoredOrder(map(rs), rs.getString("fingerprint")),
                tenantId, customerId, key).stream().findFirst();
    }

    public Optional<Order> findOwned(UUID tenantId, String customerId, UUID id) {
        return jdbc.query("""
                SELECT * FROM customer_order WHERE tenant_id = ? AND customer_id = ? AND id = ?
                """, (rs, row) -> map(rs), tenantId, customerId, id).stream().findFirst();
    }

    public Optional<Order> lock(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM customer_order WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, row) -> map(rs), tenantId, id).stream().findFirst();
    }

    public Optional<Order> findTenant(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM customer_order WHERE tenant_id = ? AND id = ?",
                (rs, row) -> map(rs), tenantId, id).stream().findFirst();
    }

    public List<Order> list(UUID tenantId, String owner, OrderQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM customer_order WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (owner != null) { sql.append(" AND customer_id = ?"); args.add(owner); }
        if (query.status() != null) { sql.append(" AND status = ?"); args.add(query.status().name()); }
        if (query.createdFrom() != null) { sql.append(" AND created_at >= ?"); args.add(Timestamp.from(query.createdFrom())); }
        if (query.createdBefore() != null) { sql.append(" AND created_at < ?"); args.add(Timestamp.from(query.createdBefore())); }
        if (query.cursor() != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(query.cursor().createdAt())); args.add(query.cursor().id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (rs, row) -> map(rs), args.toArray());
    }

    public void recordHistory(Order order, Instant time, String reason) {
        jdbc.update("""
                INSERT INTO order_history (order_id, version, status, occurred_at, reason)
                VALUES (?, ?, ?, GREATEST(?, COALESCE(
                    (SELECT max(occurred_at) FROM order_history WHERE order_id = ?), ?)), ?)
                """, order.id(), order.version(), order.status().name(), Timestamp.from(time),
                order.id(), Timestamp.from(order.createdAt()), reason);
    }

    public List<OrderHistoryEntry> history(UUID id) {
        // The acyclic state machine has at most three transitions including creation.
        return jdbc.query("SELECT * FROM order_history WHERE order_id = ? ORDER BY version LIMIT 4",
                (rs, row) -> new OrderHistoryEntry(rs.getLong("version"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("reason")), id);
    }

    public List<Order> lockStaleUndispatched(Instant before, int limit) {
        return jdbc.query("""
                SELECT c.* FROM customer_order c
                WHERE c.status = 'CREATED' AND c.created_at <= ? AND c.deferred_event_id IS NULL
                  AND EXISTS (SELECT 1 FROM outbox o WHERE o.tenant_id = c.tenant_id
                    AND o.aggregate_id = c.id::text AND o.payload->>'eventType' = 'order.created'
                    AND o.published_at IS NULL AND o.dispatch_started_at IS NULL)
                ORDER BY c.created_at, c.id LIMIT ? FOR UPDATE OF c SKIP LOCKED
                """, (rs, row) -> map(rs), Timestamp.from(before.truncatedTo(java.time.temporal.ChronoUnit.MICROS)), limit);
    }

    public String checkoutCorrelation(UUID tenant, UUID id) {
        return jdbc.queryForObject("""
                SELECT payload->>'correlationId' FROM outbox WHERE tenant_id = ? AND aggregate_id = ?
                    AND payload->>'eventType' = 'order.created'
                """, String.class, tenant, id.toString());
    }

    /** PostgreSQL waits for a concurrent winner without aborting this transaction on conflict. */
    public boolean insert(Order order, String key, String fingerprint) {
        CheckoutSnapshot s = order.snapshot();
        return jdbc.update("""
                INSERT INTO customer_order (id, tenant_id, customer_id, idempotency_key, fingerprint,
                    product_id, quantity, product_name, unit_price_minor, total_minor, currency,
                    catalog_version, payment_method, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, customer_id, idempotency_key) DO NOTHING
                """, order.id(), order.tenantId(), order.customerId(), key, fingerprint,
                s.productId(), s.quantity(), s.productName(), s.unitPriceMinor(), s.totalMinor(),
                s.currency(), s.catalogVersion(), s.paymentMethod(), order.status().name(),
                order.version(), Timestamp.from(order.createdAt())) == 1;
    }

    public void save(Order order) {
        DeferredPayment deferred = order.deferredPayment();
        int updated = jdbc.update("""
                UPDATE customer_order SET status = ?, version = ?, reservation_event_id = ?,
                    deferred_event_id = ?, deferred_causation_id = ?, deferred_payment_id = ?,
                    deferred_authorized = ? WHERE tenant_id = ? AND id = ?
                """, order.status().name(), order.version(), order.reservationEventId(),
                deferred == null ? null : deferred.eventId(),
                deferred == null ? null : deferred.reservationEventId(),
                deferred == null ? null : deferred.paymentId(),
                deferred == null ? null : deferred.authorized(), order.tenantId(), order.id());
        if (updated != 1) {
            throw new IllegalStateException("Order disappeared while locked");
        }
    }

    private static Order map(ResultSet rs) throws SQLException {
        UUID deferredEvent = rs.getObject("deferred_event_id", UUID.class);
        DeferredPayment deferred = deferredEvent == null ? null : new DeferredPayment(deferredEvent,
                rs.getObject("deferred_causation_id", UUID.class),
                rs.getObject("deferred_payment_id", UUID.class), rs.getBoolean("deferred_authorized"));
        CheckoutSnapshot snapshot = new CheckoutSnapshot(rs.getObject("product_id", UUID.class),
                rs.getInt("quantity"), rs.getString("product_name"), rs.getLong("unit_price_minor"),
                rs.getLong("total_minor"), rs.getString("currency"), rs.getLong("catalog_version"),
                rs.getString("payment_method"));
        return new Order(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("customer_id"), snapshot, OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("reservation_event_id", UUID.class), deferred);
    }

    public record StoredOrder(Order order, String fingerprint) { }
}
