package commerce.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
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
