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
        server.expect(requestTo("http://catalog.test/api/v1/products/" + productId))
                .andExpect(method(HttpMethod.GET))
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
        for (String body : new String[] {product("9223372036854775807", "true", "USD"),
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

    private String product(String price, String active, String currency) {
        return """
                {"id":"%s","name":"Odexa tote","description":"Canvas tote",
                 "unitPriceMinor":%s,"currency":"%s","active":%s,"version":7}
                """.formatted(productId, price, currency, active);
    }
}
