package commerce.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckoutServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final CheckoutWriter writer = mock(CheckoutWriter.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final CheckoutService service = new CheckoutService(orders, writer, catalog, java.time.Clock.systemUTC());
    private final Order order = OrderStateMachineTest.created();
    private final Actor actor = new Actor(order.tenantId(), order.customerId(), Set.of("CUSTOMER"));
    private final CheckoutRequest request = new CheckoutRequest(order.snapshot().productId(), 2, "pm_approved");

    @BeforeEach
    void noExistingOrder() {
        when(orders.findByKey(any(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void exactRetryNeverTouchesCatalogOrWriter() {
        when(orders.findByKey(actor.tenantId(), actor.subject(), "retry"))
                .thenReturn(Optional.of(new OrderRepository.StoredOrder(order, request.fingerprint())));
        CheckoutWriter.Result result = service.checkout(actor, "unused-test-jwt", "retry", request);
        assertEquals(order, result.order());
        assertFalse(result.created());
        verifyNoInteractions(catalog, writer);
    }

    @Test
    void mismatchedRetryIs409WithoutCatalog() {
        when(orders.findByKey(actor.tenantId(), actor.subject(), "retry"))
                .thenReturn(Optional.of(new OrderRepository.StoredOrder(order, request.fingerprint())));
        CheckoutRequest changed = new CheckoutRequest(request.productId(), 3, request.paymentMethod());
        ApiException error = assertThrows(ApiException.class, () -> service.checkout(actor, "unused-test-jwt", "retry", changed));
        assertEquals(409, error.status());
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
        verifyNoInteractions(catalog, writer);
    }

    @Test
    void catalogFailureDoesNotPersist() {
        when(catalog.snapshot(eq(request), anyString(), anyString()))
                .thenThrow(new ApiException(503, "CATALOG_UNAVAILABLE", "Unavailable"));
        assertEquals(503, assertThrows(ApiException.class,
                () -> service.checkout(actor, "unused-test-jwt", "new", request)).status());
        verifyNoInteractions(writer);
    }

    @Test
    void concurrentWinnerIsReturnedEvenIfCatalogFailedDuringTheRace() {
        when(orders.findByKey(actor.tenantId(), actor.subject(), "race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new OrderRepository.StoredOrder(order, request.fingerprint())));
        when(catalog.snapshot(eq(request), anyString(), anyString()))
                .thenThrow(new ApiException(503, "CATALOG_UNAVAILABLE", "Unavailable"));
        assertEquals(order, service.checkout(actor, "unused-test-jwt", "race", request).order());
        verifyNoInteractions(writer);
    }

    @Test
    void newCheckoutUsesOnlyCatalogSnapshotAndScopedIdentity() {
        when(catalog.snapshot(eq(request), eq("unused-test-jwt"), anyString())).thenReturn(order.snapshot());
        when(writer.create(any(), eq("new"), eq(request.fingerprint())))
                .thenAnswer(call -> new CheckoutWriter.Result(call.getArgument(0), true));
        Order created = service.checkout(actor, "unused-test-jwt", "new", request).order();
        assertEquals(actor.tenantId(), created.tenantId());
        assertEquals(actor.subject(), created.customerId());
        assertEquals(order.snapshot(), created.snapshot());
    }

    @Test
    void roleAndOwnerAreRequired() {
        Actor merchant = new Actor(actor.tenantId(), actor.subject(), Set.of("MERCHANT_ADMIN"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.checkout(merchant, "unused-test-jwt", "new", request)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(merchant, order.id())).status());
        when(orders.findOwned(actor.tenantId(), actor.subject(), order.id())).thenReturn(Optional.of(order));
        assertEquals(order, service.get(actor, order.id()));
        Actor otherOwner = new Actor(actor.tenantId(), "other-customer", Set.of("CUSTOMER"));
        Actor otherTenant = new Actor(UUID.randomUUID(), actor.subject(), Set.of("CUSTOMER"));
        for (Actor outsider : new Actor[] {otherOwner, otherTenant}) {
            assertEquals(404, assertThrows(ApiException.class, () -> service.get(outsider, order.id())).status());
            verify(orders).findOwned(outsider.tenantId(), outsider.subject(), order.id());
        }
    }

    @Test
    void keyAndCheckoutValidationHappenBeforeNetwork() {
        for (String key : new String[] {"", " ", "bad\nkey", "é", "a".repeat(129)}) {
            assertEquals(400, assertThrows(ApiException.class,
                    () -> service.checkout(actor, "unused-test-jwt", key, request)).status());
        }
        assertThrows(ApiException.class, () -> service.checkout(actor, "unused-test-jwt", null, request));
        for (CheckoutRequest invalid : new CheckoutRequest[] {
                new CheckoutRequest(null, 1, "pm_approved"), new CheckoutRequest(request.productId(), 0, "pm_approved"),
                new CheckoutRequest(request.productId(), 101, "pm_approved"), new CheckoutRequest(request.productId(), 1, "card-data")}) {
            assertThrows(ApiException.class, () -> service.checkout(actor, "unused-test-jwt", "key", invalid));
        }
        verifyNoInteractions(catalog, writer);
    }

    @Test
    void semanticFingerprintIncludesEveryIntentField() {
        assertEquals(request.fingerprint(), new CheckoutRequest(UUID.fromString(request.productId().toString().toUpperCase(java.util.Locale.ROOT)), 2, "pm_approved").fingerprint());
        assertNotEquals(request.fingerprint(), new CheckoutRequest(UUID.randomUUID(), 2, "pm_approved").fingerprint());
        assertNotEquals(request.fingerprint(), new CheckoutRequest(request.productId(), 1, "pm_approved").fingerprint());
        assertNotEquals(request.fingerprint(), new CheckoutRequest(request.productId(), 2, "pm_declined").fingerprint());
    }
}
