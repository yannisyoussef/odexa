package commerce.inventory;

import commerce.inventory.InventoryPolicy.ReservationState;
import commerce.runtime.Outbox;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    private record Reservation(OrderCreated order, ReservationState state, UUID reservationEventId) {
    }

    private record Rejected(UUID orderId, String reason) {
    }

    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public ReservationService(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public void reserve(UUID tenantId, OrderCreated order, UUID eventId) {
        // The unique reservation claim serializes even distinct event IDs for the same order.
        // REJECTED is provisional until this transaction commits its final decision + outbox.
        int inserted = jdbc.update("""
                INSERT INTO inventory_reservation
                    (tenant_id, order_id, customer_id, total_minor, currency, payment_method, state)
                VALUES (?, ?, ?, ?, ?, ?, 'REJECTED')
                ON CONFLICT (tenant_id, order_id) DO NOTHING
                """, tenantId, order.orderId(), order.customerId(),
                order.totalMinor(), order.currency(), order.paymentMethod());
        if (inserted == 0) {
            Reservation existing = lockReservation(tenantId, order.orderId());
            if (!existing.order().equals(order)) {
                throw new IllegalArgumentException("Conflicting order snapshot");
            }
            return;
        }
        for (OrderCreated.Line line : order.items()) jdbc.update("""
                INSERT INTO reservation_line(tenant_id, order_id, product_id, quantity) VALUES (?, ?, ?, ?)
                """, tenantId, order.orderId(), line.productId(), line.quantity());
        // Lock every existing stock row in canonical UUID order before changing any row.
        // A missing stock row is a rejection at this statement; concurrent creation may retry via a new order.
        boolean available = true;
        for (OrderCreated.Line line : order.items()) {
            var stock = jdbc.query("SELECT on_hand - reserved FROM inventory_stock WHERE tenant_id = ? AND product_id = ? FOR UPDATE",
                    (rs, row) -> rs.getLong(1), tenantId, line.productId());
            if (stock.isEmpty() || stock.getFirst() < line.quantity()) available = false;
        }
        if (available) {
            for (OrderCreated.Line line : order.items()) jdbc.update("""
                    UPDATE inventory_stock SET reserved = reserved + ?, version = version + 1
                    WHERE tenant_id = ? AND product_id = ?
                    """, line.quantity(), tenantId, line.productId());
            UUID reservationEventId = outbox.append("inventory.reserved", 2, tenantId, order.orderId().toString(), order, eventId);
            jdbc.update("""
                    UPDATE inventory_reservation SET state = 'RESERVED', reservation_event_id = ?
                    WHERE tenant_id = ? AND order_id = ?
                    """, reservationEventId, tenantId, order.orderId());
        } else {
            outbox.append("inventory.rejected", tenantId, order.orderId().toString(),
                    new Rejected(order.orderId(), "INSUFFICIENT_STOCK"), eventId);
        }
    }

    @Transactional
    public void settle(UUID tenantId, UUID orderId, boolean authorized, UUID causationId) {
        Reservation reservation = lockReservation(tenantId, orderId);
        if (causationId == null || !causationId.equals(reservation.reservationEventId())) {
            throw new IllegalArgumentException("Payment causation must match the reservation event");
        }
        ReservationState next = InventoryPolicy.settle(reservation.state(), authorized);
        if (next == reservation.state()) {
            return;
        }
        OrderCreated order = reservation.order();
        // Same canonical order as reservation: settlement cannot invert stock lock ordering.
        for (OrderCreated.Line line : order.items()) {
            int changed = jdbc.update("""
                    UPDATE inventory_stock SET on_hand = on_hand - ?, reserved = reserved - ?, version = version + 1
                    WHERE tenant_id = ? AND product_id = ? AND reserved >= ?
                    """, authorized ? line.quantity() : 0, line.quantity(), tenantId, line.productId(), line.quantity());
            if (changed != 1) throw new IllegalStateException("Reservation stock invariant failed");
        }
        jdbc.update("UPDATE inventory_reservation SET state = ? WHERE tenant_id = ? AND order_id = ?",
                next.name(), tenantId, orderId);
        // No settlement event is defined by the contract; payment already publishes the terminal outcome.
    }

    private Reservation lockReservation(UUID tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM inventory_reservation WHERE tenant_id = ? AND order_id = ? FOR UPDATE
                """, (rs, row) -> new Reservation(new OrderCreated(rs.getObject("order_id", UUID.class),
                        rs.getString("customer_id"), jdbc.query(
                                "SELECT product_id, quantity FROM reservation_line WHERE tenant_id = ? AND order_id = ? ORDER BY product_id",
                                (line, n) -> new OrderCreated.Line(line.getObject("product_id", UUID.class), line.getInt("quantity")), tenantId, orderId),
                        rs.getLong("total_minor"), rs.getString("currency"), rs.getString("payment_method")),
                        ReservationState.valueOf(rs.getString("state")), rs.getObject("reservation_event_id", UUID.class)),
                tenantId, orderId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Payment outcome has no reservation"));
    }
}
