package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StripePaymentProviderTest {
    private final UUID order = UUID.randomUUID();
    private final PaymentProvider.Request request = new PaymentProvider.Request(order, 2500, "USD", "pm_card_visa", "stripe");

    @Test void sdkUsesStableKeyAutomaticMethodsAndMapsOnlyTerminalEvidence() throws Exception {
        var body = new AtomicReference<String>(); var key = new AtomicReference<String>();
        try (var fixture = new Fixture(exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return intent("succeeded", 2500, "usd", order, false);
        })) {
            var result = fixture.provider.authorize(request);
            assertEquals(PaymentProvider.Outcome.AUTHORIZED, result.outcome());
            assertEquals(order.toString(), key.get());
            assertTrue(body.get().contains("automatic_payment_methods"));
            assertFalse(body.get().contains("payment_method_types"));
            assertEquals(result, fixture.provider.authorize(request));
            assertEquals(order.toString(), key.get());
        }
    }
    @Test void requiresActionAndProcessingRemainUnknownWhileCancellationIsDeclined() throws Exception {
        for (String status : new String[]{"requires_action", "processing", "requires_payment_method", "canceled"}) {
            try (var fixture = new Fixture(e -> intent(status, 2500, "usd", order, false))) {
                assertEquals(status.equals("canceled") ? PaymentProvider.Outcome.DECLINED : PaymentProvider.Outcome.REVIEW_REQUIRED,
                        fixture.provider.lookup(request, "pi_fixture" ).outcome());
            }
        }
    }
    @Test void wrongLinkageAmountCurrencyAndLiveModeAreUncertain() throws Exception {
        for (String invalid : new String[]{intent("succeeded", 2499, "usd", order, false),
                intent("succeeded", 2500, "eur", order, false), intent("succeeded", 2500, "usd", UUID.randomUUID(), false),
                intent("succeeded", 2500, "usd", order, true)}) {
            try (var fixture = new Fixture(e -> invalid)) {
                assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookup(request, "pi_fixture"));
            }
        }
    }
    @Test void missingReferenceSearchNeverRecreatesPayment() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("/v1/payment_intents/search", exchange.getRequestURI().getPath());
            return "{\"object\":\"search_result\",\"data\":[],\"has_more\":false}";
        })) {
            assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookup(request, null));
        }
    }
    @Test void refundSdkKeyAndLinkageAreIndependentOfThePaymentCommand() throws Exception {
        UUID refund = UUID.randomUUID();
        var command = new PaymentProvider.RefundRequest(refund, order, "pi_fixture", 2500, "USD", "stripe");
        try (var fixture = new Fixture(exchange -> {
            assertEquals(refund.toString(), exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            assertEquals("/v1/refunds", exchange.getRequestURI().getPath());
            return """
                    {"id":"re_fixture","object":"refund","amount":2500,"currency":"usd",
                    "payment_intent":"pi_fixture","status":"succeeded","metadata":{"odexa_refund_id":"%s"}}
                    """.formatted(refund);
        })) {
            assertEquals(PaymentProvider.RefundOutcome.SUCCEEDED, fixture.provider.refund(command).outcome());
        }
    }
    @Test void liveKeysAreRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> StripePaymentProvider.client("sk_live_notallowed", "https://api.stripe.com"));
    }
    private String intent(String status, long amount, String currency, UUID linkedOrder, boolean live) {
        return """
                {"id":"pi_fixture","object":"payment_intent","amount":%d,"amount_received":%d,
                "currency":"%s","livemode":%s,"status":"%s","metadata":{"odexa_order_id":"%s"}}
                """.formatted(amount, amount, currency, live, status, linkedOrder);
    }
    interface Handler { String respond(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException; }
    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final StripePaymentProvider provider;
        Fixture(Handler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] bytes = handler.respond(exchange).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            provider = new StripePaymentProvider(StripePaymentProvider.client("rk_test_fixture", "http://127.0.0.1:" + server.getAddress().getPort()));
        }
        public void close() { server.stop(0); }
    }
}
