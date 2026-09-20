package commerce.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import commerce.runtime.Outbox;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CatalogClientTest {
    private final UUID productId = UUID.randomUUID();
    private final CheckoutRequest request = new CheckoutRequest(productId, 2, "pm_approved");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CatalogClient catalog = new CatalogClient(builder.build());

    @Test
    void forwardsBearerAndCorrelationAndUsesAuthoritativeSnapshot() {
        String correlation = UUID.randomUUID().toString();
        server.expect(requestTo("http://catalog.test/internal/v1/products/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer unused-test-jwt"))
                .andExpect(header("X-Correlation-ID", correlation))
                .andRespond(withSuccess(product("2500", "true", "USD"), MediaType.APPLICATION_JSON));
        CheckoutSnapshot snapshot = catalog.snapshot(request, "unused-test-jwt", correlation);
        assertEquals(2500, snapshot.unitPriceMinor());
        assertEquals(5000, snapshot.totalMinor());
        assertEquals("Odexa tote", snapshot.productName());
        assertEquals(7, snapshot.catalogVersion());
        server.verify();
    }

    @Test
    void zeroAmountCheckoutIs422BeforePersistenceEvenOnRetry() {
        OrderRepository orders = mock(OrderRepository.class);
        Outbox outbox = mock(Outbox.class);
        CheckoutService service = new CheckoutService(orders, new CheckoutWriter(orders, outbox), catalog, java.time.Clock.systemUTC());
        Actor actor = new Actor(UUID.randomUUID(), "customer-a", Set.of("CUSTOMER"));
        when(orders.findByKey(actor.tenantId(), actor.subject(), "free-checkout")).thenReturn(Optional.empty());

        for (int attempt = 0; attempt < 2; attempt++) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(product("0", "true", "USD"), MediaType.APPLICATION_JSON));
            ApiException error = assertThrows(ApiException.class,
                    () -> service.checkout(actor, "unused-test-jwt", "free-checkout", request));
            assertEquals(422, error.status());
            assertEquals("PRODUCT_UNAVAILABLE", error.code());
            assertEquals("Zero-amount checkout is not supported", error.getMessage());
            server.verify();
        }
        // Only idempotency reads occur: no order insert or stock-triggering order.created event.
        verify(orders, times(4)).findByKey(actor.tenantId(), actor.subject(), "free-checkout");
        verifyNoMoreInteractions(orders);
        verifyNoInteractions(outbox);
    }

    @Test
    void responseTimeoutIs503() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("simulated timeout")));
        assertEquals(503, assertThrows(ApiException.class,
                () -> catalog.snapshot(request, "unused-test-jwt", UUID.randomUUID().toString())).status());
        server.verify();
    }

    @Test
    void invalidOrOverflowingCatalogMoneyIsUnavailable() {
        for (String body : new String[] {
                product("-1", "true", "USD"), product("2500", "true", "EUR"),
                product("null", "true", "USD"), "{}", "not-json"}) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertEquals(503, assertThrows(ApiException.class,
                    () -> catalog.snapshot(request, "unused-test-jwt", UUID.randomUUID().toString())).status());
            server.verify();
        }
    }

    @Test
    void inactiveProductIs409AndMissingOrForbiddenProductIs404() {
        server.expect(anything()).andRespond(withSuccess(product("2500", "false", "USD"), MediaType.APPLICATION_JSON));
        assertEquals(409, assertThrows(ApiException.class,
                () -> catalog.snapshot(request, "unused-test-jwt", UUID.randomUUID().toString())).status());
        for (HttpStatus status : new HttpStatus[] {HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE}) {
            server.reset();
            server.expect(anything()).andRespond(withStatus(status));
            int expected = status == HttpStatus.SERVICE_UNAVAILABLE ? 503 : 404;
            assertEquals(expected, assertThrows(ApiException.class,
                    () -> catalog.snapshot(request, "unused-test-jwt", UUID.randomUUID().toString())).status());
        }
    }

    @Test
    void timeoutsCannotBeUnboundedOrExcessive() {
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogClient("http://catalog.test", Duration.ZERO, Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogClient("http://catalog.test", Duration.ofSeconds(2), Duration.ofSeconds(11)));
    }

    @Test void multiplicationOverflowIsIntentional422() {
        server.expect(anything()).andRespond(withSuccess(product("9223372036854775807", "true", "USD"), MediaType.APPLICATION_JSON));
        var error = assertThrows(ApiException.class, () -> catalog.snapshot(request,"token",UUID.randomUUID().toString()));
        assertEquals(422,error.status()); assertEquals("ORDER_TOTAL_OVERFLOW",error.code());
    }

    @Test void batchSnapshotIsAuthoritativeBoundedAndUnaffectedByLaterPriceChanges() {
        UUID other = UUID.randomUUID();
        var basket = new CheckoutRequest(java.util.List.of(new CheckoutRequest.Item(productId,2),new CheckoutRequest.Item(other,1)),"pm_approved");
        String first = product("2500","true","USD");
        String free = product("0","true","USD").replace(productId.toString(),other.toString());
        server.expect(anything()).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(first.substring(0,first.lastIndexOf(']')) + "," + free.substring(free.indexOf('[')+1),MediaType.APPLICATION_JSON));
        var accepted = catalog.snapshot(basket,"token",UUID.randomUUID().toString());
        assertEquals(5000,accepted.totalMinor()); assertEquals(2,accepted.items().size());
        server.reset();
        server.expect(anything()).andRespond(withSuccess(product("3500","true","USD"),MediaType.APPLICATION_JSON));
        assertEquals(7000,catalog.snapshot(request,"token",UUID.randomUUID().toString()).totalMinor());
        assertEquals(5000,accepted.totalMinor());
    }

    @Test void maximumTwentyLineBasketSucceedsAndSumOverflowFailsIntentionally() {
        var items = java.util.stream.IntStream.range(0,20).mapToObj(i -> new CheckoutRequest.Item(UUID.randomUUID(),100)).toList();
        String body = items.stream().map(i -> product("1","true","USD").replace(productId.toString(),i.productId().toString()).trim())
                .map(text -> text.substring(1,text.length()-1)).collect(java.util.stream.Collectors.joining(",","[","]"));
        server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var accepted = catalog.snapshot(new CheckoutRequest(items,"pm_approved"),"token",UUID.randomUUID().toString());
        assertEquals(20,accepted.items().size()); assertEquals(2000,accepted.totalMinor());
        server.reset();
        var overflowItems = items.subList(0,2).stream().map(i -> new CheckoutRequest.Item(i.productId(),1)).toList();
        String large = overflowItems.stream().map(i -> product("9223372036854775807","true","USD").replace(productId.toString(),i.productId().toString()).trim())
                .map(text -> text.substring(1,text.length()-1)).collect(java.util.stream.Collectors.joining(",","[","]"));
        server.expect(anything()).andRespond(withSuccess(large,MediaType.APPLICATION_JSON));
        assertEquals("ORDER_TOTAL_OVERFLOW",assertThrows(ApiException.class,() -> catalog.snapshot(new CheckoutRequest(overflowItems,"pm_approved"),"token",UUID.randomUUID().toString())).code());
    }

    private String product(String price, String active, String currency) {
        return """
                [{"id":"%s","name":"Odexa tote","description":"Canvas tote",
                 "unitPriceMinor":%s,"currency":"%s","active":%s,"version":7}]
                """.formatted(productId, price, currency, active);
    }
}
