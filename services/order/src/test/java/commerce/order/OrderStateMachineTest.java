package commerce.order;

import static org.junit.jupiter.api.Assertions.*;

import commerce.runtime.ApiException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class OrderStateMachineTest {
    private static final UUID RESERVATION = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();

    enum Signal { RESERVED, REJECTED, AUTHORIZED, DECLINED }

    @TestFactory
    Stream<DynamicTest> everyStateAndSignal() {
        return Arrays.stream(OrderStatus.values()).flatMap(status -> Arrays.stream(Signal.values())
                .map(signal -> DynamicTest.dynamicTest(status + " + " + signal, () -> {
                    Order before = at(status);
                    if (status == OrderStatus.PENDING_PAYMENT && signal == Signal.REJECTED) {
                        ApiException error = assertThrows(ApiException.class, () -> apply(before, signal));
                        assertEquals("ORDER_EVENT_CONFLICT", error.code());
                        return;
                    }
                    Order after = apply(before, signal);
                    OrderStatus expected = switch (status) {
                        case CREATED -> switch (signal) {
                            case RESERVED -> OrderStatus.PENDING_PAYMENT;
                            case REJECTED -> OrderStatus.STOCK_REJECTED;
                            case AUTHORIZED, DECLINED -> OrderStatus.CREATED;
                        };
                        case PENDING_PAYMENT -> signal == Signal.AUTHORIZED ? OrderStatus.CONFIRMED
                                : signal == Signal.DECLINED ? OrderStatus.PAYMENT_FAILED : OrderStatus.PENDING_PAYMENT;
                        case CONFIRMED, STOCK_REJECTED, PAYMENT_FAILED -> status;
                    };
                    assertEquals(expected, after.status());
                    assertEquals(before.version() + (expected == status ? 0 : 1), after.version());
                    assertEquals(status, before.status(), "Original aggregate remains immutable");
                    if (status.terminal()) {
                        assertSame(before, after);
                    }
                })));
    }

    @Test
    void earlyPaymentWaitsForItsCausalReservationForBothOutcomes() {
        for (boolean authorized : new boolean[] {true, false}) {
            Order created = created();
            DeferredPayment evidence = result(authorized);
            Order deferred = created.payment(evidence);
            assertEquals(OrderStatus.CREATED, deferred.status());
            assertEquals(0, deferred.version());
            assertEquals(evidence, deferred.deferredPayment());
            assertNull(created.deferredPayment());
            assertSame(deferred, deferred.payment(evidence));
            Order terminal = deferred.reserved(RESERVATION);
            assertEquals(authorized ? OrderStatus.CONFIRMED : OrderStatus.PAYMENT_FAILED, terminal.status());
            assertEquals(2, terminal.version());
            assertNull(terminal.deferredPayment());
            assertSame(terminal, terminal.reserved(RESERVATION));
        }
    }

    @Test
    void deferredAndPendingResultsMustMatchReservationCausation() {
        Order deferred = created().payment(result(true));
        assertThrows(ApiException.class, () -> deferred.reserved(UUID.randomUUID()));
        assertThrows(ApiException.class, deferred::stockRejected);
        assertThrows(ApiException.class, () -> deferred.payment(result(false)));
        assertThrows(ApiException.class, () -> deferred.payment(new DeferredPayment(
                UUID.randomUUID(), UUID.randomUUID(), PAYMENT, true)));
        assertThrows(ApiException.class, () -> deferred.payment(new DeferredPayment(
                UUID.randomUUID(), RESERVATION, UUID.randomUUID(), true)));
        Order pending = created().reserved(RESERVATION);
        assertThrows(ApiException.class, () -> pending.reserved(UUID.randomUUID()));
        assertThrows(ApiException.class, () -> pending.payment(new DeferredPayment(
                UUID.randomUUID(), UUID.randomUUID(), PAYMENT, true)));
    }

    @Test
    void lateConflictingOutcomesCannotReopenAnyTerminalState() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.CONFIRMED, OrderStatus.PAYMENT_FAILED, OrderStatus.STOCK_REJECTED}) {
            Order terminal = at(status);
            assertSame(terminal, terminal.payment(new DeferredPayment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true)));
            assertSame(terminal, terminal.payment(new DeferredPayment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false)));
            assertSame(terminal, terminal.reserved(UUID.randomUUID()));
            assertSame(terminal, terminal.stockRejected());
        }
    }

    @Test
    void checkoutDomainRequiresStrictlyPositiveAmounts() {
        UUID productId = UUID.randomUUID();
        for (int quantity : new int[] {1, 100}) {
            for (long price : new long[] {-1, 0}) {
                assertThrows(IllegalArgumentException.class, () -> new CheckoutSnapshot(
                        productId, quantity, "Tote", price, price * quantity, "USD", 1, "pm_approved"));
            }
            for (long total : new long[] {-1, 0}) {
                assertThrows(IllegalArgumentException.class, () -> new CheckoutSnapshot(
                        productId, quantity, "Tote", 1, total, "USD", 1, "pm_approved"));
            }
            CheckoutSnapshot positive = new CheckoutSnapshot(
                    productId, quantity, "Tote", 1, quantity, "USD", 1, "pm_approved");
            Order order = Order.create(UUID.randomUUID(), UUID.randomUUID(), "customer-a", positive, Instant.now());
            assertEquals(quantity, order.snapshot().totalMinor());
        }
    }

    @Test
    void invalidDomainSnapshotsAndStatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutSnapshot(UUID.randomUUID(), 0, "Tote", 2500, 0, "USD", 1, "pm_approved"));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutSnapshot(UUID.randomUUID(), 2, "Tote", 2500, 2500, "USD", 1, "pm_approved"));
        assertThrows(ArithmeticException.class, () -> new CheckoutSnapshot(UUID.randomUUID(), 2, "Tote", Long.MAX_VALUE, 0, "USD", 1, "pm_approved"));
        Order order = created();
        assertThrows(IllegalArgumentException.class, () -> new Order(order.id(), order.tenantId(), order.customerId(),
                order.snapshot(), OrderStatus.CONFIRMED, 1, order.createdAt(), null, null));
        assertThrows(NullPointerException.class, () -> new DeferredPayment(UUID.randomUUID(), null, PAYMENT, true));
    }

    private static Order apply(Order order, Signal signal) {
        return switch (signal) {
            case RESERVED -> order.reserved(RESERVATION);
            case REJECTED -> order.stockRejected();
            case AUTHORIZED -> order.payment(result(true));
            case DECLINED -> order.payment(result(false));
        };
    }

    private static Order at(OrderStatus status) {
        Order created = created();
        return switch (status) {
            case CREATED -> created;
            case PENDING_PAYMENT -> created.reserved(RESERVATION);
            case CONFIRMED -> created.reserved(RESERVATION).payment(result(true));
            case STOCK_REJECTED -> created.stockRejected();
            case PAYMENT_FAILED -> created.reserved(RESERVATION).payment(result(false));
        };
    }

    static Order created() {
        return Order.create(UUID.randomUUID(), UUID.randomUUID(), "customer-a",
                new CheckoutSnapshot(UUID.randomUUID(), 2, "Odexa tote", 2500, 5000, "USD", 1, "pm_approved"),
                Instant.parse("2026-09-12T12:00:00Z"));
    }

    private static DeferredPayment result(boolean authorized) {
        return new DeferredPayment(UUID.randomUUID(), RESERVATION, PAYMENT, authorized);
    }
}
