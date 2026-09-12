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
                    (tenant_id, order_id, customer_id, product_id, quantity, total_minor, currency, payment_method, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REJECTED')
                ON CONFLICT (tenant_id, order_id) DO NOTHING
                """, tenantId, order.orderId(), order.customerId(), order.productId(), order.quantity(),
                order.totalMinor(), order.currency(), order.paymentMethod());
        if (inserted == 0) {
            Reservation existing = lockReservation(tenantId, order.orderId());
            if (!existing.order().equals(order)) {
                throw new IllegalArgumentException("Conflicting order snapshot");
            }
            return;
        }
        int reserved = jdbc.update("""
                UPDATE inventory_stock SET reserved = reserved + ?, version = version + 1
                WHERE tenant_id = ? AND product_id = ? AND on_hand - reserved >= ?
                """, order.quantity(), tenantId, order.productId(), order.quantity());
        if (reserved == 1) {
            UUID reservationEventId = outbox.append("inventory.reserved", tenantId, order.orderId().toString(), order, eventId);
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
        int changed = jdbc.update("""
                UPDATE inventory_stock SET on_hand = on_hand - ?, reserved = reserved - ?, version = version + 1
                WHERE tenant_id = ? AND product_id = ? AND reserved >= ?
                """, authorized ? order.quantity() : 0, order.quantity(), tenantId, order.productId(), order.quantity());
        if (changed != 1) {
            throw new IllegalStateException("Reservation stock invariant failed");
        }
        jdbc.update("UPDATE inventory_reservation SET state = ? WHERE tenant_id = ? AND order_id = ?",
                next.name(), tenantId, orderId);
        // No settlement event is defined by the contract; payment already publishes the terminal outcome.
    }

    private Reservation lockReservation(UUID tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM inventory_reservation WHERE tenant_id = ? AND order_id = ? FOR UPDATE
                """, (rs, row) -> new Reservation(new OrderCreated(rs.getObject("order_id", UUID.class),
                        rs.getString("customer_id"), rs.getObject("product_id", UUID.class), rs.getInt("quantity"),
                        rs.getLong("total_minor"), rs.getString("currency"), rs.getString("payment_method")),
                        ReservationState.valueOf(rs.getString("state")), rs.getObject("reservation_event_id", UUID.class)),
                tenantId, orderId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Payment outcome has no reservation"));
    }
}
