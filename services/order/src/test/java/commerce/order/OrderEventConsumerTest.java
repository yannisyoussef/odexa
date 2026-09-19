package commerce.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import commerce.runtime.ApiException;
import commerce.runtime.Correlation;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import commerce.runtime.Outbox;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class OrderEventConsumerTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Inbox inbox = mock(Inbox.class);
    private final Outbox outbox = mock(Outbox.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderEventConsumer consumer = new OrderEventConsumer(mapper, inbox, outbox, orders, java.time.Clock.systemUTC());
    private final Order order = OrderStateMachineTest.created();
    private final UUID reservationId = UUID.randomUUID();

    @BeforeEach
    void ready() {
        when(inbox.first(any(), eq(OrderEventConsumer.CONSUMER))).thenReturn(true);
        when(orders.lock(order.tenantId(), order.id())).thenReturn(Optional.of(order));
    }

    @Test
    void duplicateEventDoesNotTouchBusinessRows() {
        Event event = reserved();
        when(inbox.first(event.eventId(), OrderEventConsumer.CONSUMER)).thenReturn(false);
        consumer.onMessage(mapper.writeValueAsString(event));
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void paymentBeforeReservationIsSavedButDoesNotConfirmUntilCausationMatches() {
        Event payment = payment(true);
        consumer.onMessage(mapper.writeValueAsString(payment));
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(saved.capture());
        Order deferred = saved.getValue();
        assertEquals(OrderStatus.CREATED, deferred.status());
        assertEquals(payment.eventId(), deferred.deferredPayment().eventId());
        verifyNoInteractions(outbox);
        when(orders.lock(order.tenantId(), order.id())).thenReturn(Optional.of(deferred));
        consumer.onMessage(mapper.writeValueAsString(reserved()));
        verify(orders, times(2)).save(saved.capture());
        assertEquals(OrderStatus.CONFIRMED, saved.getValue().status());
        verify(outbox).append(eq("order.confirmed"), eq(order.tenantId()), eq(order.id().toString()),
                eq(new OrderEventConsumer.OrderConfirmed(order.id())), eq(payment.eventId()));
    }

    @Test
    void confirmedEventCarriesIncomingCorrelationAndRestoresMdc() {
        Event event = payment(true);
        when(orders.lock(order.tenantId(), order.id())).thenReturn(Optional.of(order.reserved(reservationId)));
        String previous = UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, previous);
        doAnswer(call -> {
            assertEquals(event.correlationId(), Correlation.current());
            return null;
        }).when(outbox).append(anyString(), any(), anyString(), any(), any());
        try {
            consumer.onMessage(mapper.writeValueAsString(event));
            assertEquals(previous, MDC.get(Correlation.MDC_KEY));
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }

    @Test
    void terminalConflictingPaymentIsInert() {
        Order confirmed = order.reserved(reservationId).payment(new DeferredPayment(
                UUID.randomUUID(), reservationId, UUID.randomUUID(), true));
        when(orders.lock(order.tenantId(), order.id())).thenReturn(Optional.of(confirmed));
        consumer.onMessage(mapper.writeValueAsString(payment(false)));
        verify(orders, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void reservedSnapshotMustMatchAuthoritativeOrder() {
        Event invalid = event("inventory.reserved", reservationId, UUID.randomUUID(), Map.of(
                "orderId", order.id(), "customerId", order.customerId(), "productId", order.snapshot().productId(),
                "quantity", 2, "totalMinor", 1, "currency", "USD", "paymentMethod", "pm_approved"));
        assertEquals("ORDER_EVENT_CONFLICT", assertThrows(ApiException.class,
                () -> consumer.onMessage(mapper.writeValueAsString(invalid))).code());
        verify(orders, never()).save(any());
    }

    @Test
    void nonpositiveReservationTotalsAreRejectedBeforeAnyWrites() {
        for (long total : new long[] {-1, 0}) {
            Event invalid = event("inventory.reserved", reservationId, UUID.randomUUID(), Map.of(
                    "orderId", order.id(), "customerId", order.customerId(), "productId", order.snapshot().productId(),
                    "quantity", 2, "totalMinor", total, "currency", "USD", "paymentMethod", "pm_approved"));
            assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(mapper.writeValueAsString(invalid)));
        }
        verifyNoInteractions(inbox, orders, outbox);
    }

    @Test
    void unknownTenantOrderIsRejectedRatherThanAcknowledged() {
        when(orders.lock(order.tenantId(), order.id())).thenReturn(Optional.empty());
        assertEquals("ORDER_EVENT_NOT_FOUND", assertThrows(ApiException.class,
                () -> consumer.onMessage(mapper.writeValueAsString(reserved()))).code());
    }

    @Test
    void knownUnownedEventsAreIgnoredButUnknownOrMalformedGoToRecovery() {
        Event ignored = event("order.created", UUID.randomUUID(), null, Map.of("orderId", order.id()));
        consumer.onMessage(mapper.writeValueAsString(ignored));
        verifyNoInteractions(inbox, orders, outbox);
        String valid = mapper.writeValueAsString(reserved());
        for (String invalid : new String[] {"{", "null", valid.replace("inventory.reserved", "inventory.unknown"),
                valid.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                valid.replace("\"quantity\":2", "\"quantity\":2.5"),
                mapper.writeValueAsString(event("payment.authorized", UUID.randomUUID(), null,
                        Map.of("orderId", order.id(), "paymentId", UUID.randomUUID()))),
                mapper.writeValueAsString(event("inventory.rejected", UUID.randomUUID(), UUID.randomUUID(),
                        Map.of("orderId", order.id(), "reason", "OTHER")))}) {
            assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(invalid));
        }
        verifyNoInteractions(inbox, orders, outbox);
    }

    private Event reserved() {
        CheckoutSnapshot s = order.snapshot();
        return event("inventory.reserved", reservationId, UUID.randomUUID(), Map.of(
                "orderId", order.id(), "customerId", order.customerId(), "productId", s.productId(),
                "quantity", s.quantity(), "totalMinor", s.totalMinor(), "currency", s.currency(),
                "paymentMethod", s.paymentMethod(), "reservationId", order.id()));
    }

    private Event payment(boolean authorized) {
        return event(authorized ? "payment.authorized" : "payment.declined", UUID.randomUUID(), reservationId,
                Map.of("orderId", order.id(), "paymentId", UUID.randomUUID()));
    }

    private Event event(String type, UUID id, UUID cause, Object payload) {
        return new Event(id, type, 1, Instant.now(), UUID.randomUUID().toString(), cause,
                order.tenantId(), mapper.valueToTree(payload));
    }
}
