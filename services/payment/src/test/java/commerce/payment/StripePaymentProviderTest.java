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
    @Test void cardFailureBecomesDeclineOnlyAfterValidatedIdempotentCancellation() throws Exception {
        String failure = "{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"message\":\"fixture\",\"payment_intent\":"
                + intent("requires_payment_method", 2500, "usd", order, false) + "}}";
        try (var fixture = new Fixture(402, exchange -> failure)) {
            var cancels = new java.util.concurrent.atomic.AtomicInteger();
            fixture.server.createContext("/v1/payment_intents/pi_fixture/cancel", exchange -> {
                try (exchange) {
                    assertEquals(order + ":cancel", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    cancels.incrementAndGet();
                    byte[] bytes = intent("canceled", 2500, "usd", order, false).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            });
            assertEquals(PaymentProvider.Outcome.DECLINED, fixture.provider.authorize(request).outcome());
            assertEquals(1, cancels.get());
        }
        // If cancellation itself fails, stock must remain protected.
        try (var fixture = new Fixture(402, exchange -> failure)) {
            assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.authorize(request));
        }
    }
    @Test void providerHttpErrorsMalformedAndOversizedBodiesNeverBecomeDeclines() throws Exception {
        for (int code : new int[]{400, 409, 429, 500, 503}) {
            try (var fixture = new Fixture(code, exchange -> "{\"error\":{\"type\":\"api_error\",\"message\":\"private provider payload\"}}")) {
                var error = assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.authorize(request));
                assertNull(error.getCause()); assertFalse(error.getMessage().contains("private"));
            }
        }
        for (String invalid : new String[]{"{", "x".repeat(16385)}) {
            try (var fixture = new Fixture(exchange -> invalid)) {
                assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookup(request, "pi_fixture"));
            }
        }
    }
    @Test void stripeTotalDeadlineIncludesAStalledResponseBody() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var headers = new java.util.concurrent.CountDownLatch(1);
        try (var fixture = new Fixture(exchange -> "{}")) {
            fixture.server.createContext("/v1/payment_intents/pi_fixture", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 1000);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); headers.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
            });
            try {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(7), () ->
                        assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookup(request, "pi_fixture")));
                assertEquals(0, headers.getCount());
            } finally { release.countDown(); }
        }
    }
    @Test void refundPendingFailureAndWrongLinkageAreDistinct() throws Exception {
        UUID refund = UUID.randomUUID();
        var command = new PaymentProvider.RefundRequest(refund, order, "pi_fixture", 2500, "USD", "stripe");
        String template = """
                {"id":"re_fixture","object":"refund","amount":2500,"currency":"usd",
                "payment_intent":"pi_fixture","status":"%s","metadata":{"odexa_refund_id":"%s"}}
                """;
        for (String status : new String[]{"pending", "requires_action", "failed", "canceled"}) {
            try (var fixture = new Fixture(exchange -> template.formatted(status, refund))) {
                var expected = status.equals("failed") || status.equals("canceled")
                        ? PaymentProvider.RefundOutcome.FAILED : PaymentProvider.RefundOutcome.REVIEW_REQUIRED;
                assertEquals(expected, fixture.provider.lookupRefund(command, "re_fixture").outcome());
            }
        }
        String valid = template.formatted("succeeded", refund);
        for (String invalid : new String[]{valid.replace("2500", "2499"), valid.replace("usd", "eur"),
                valid.replace("pi_fixture", "pi_other"), valid.replace(refund.toString(), UUID.randomUUID().toString()),
                valid.replace("re_fixture", "re_other")}) {
            try (var fixture = new Fixture(exchange -> invalid)) {
                assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookupRefund(command, "re_fixture"));
            }
        }
    }

    @Test void lostRefundResponseIsRecoveredOnlyByUniqueScopedReadOnlyLookup() throws Exception {
        UUID refund = UUID.randomUUID();
        var command = new PaymentProvider.RefundRequest(refund, order, "pi_fixture", 2500, "USD", "stripe");
        String result = """
                {"id":"re_fixture","object":"refund","amount":2500,"currency":"usd",
                "payment_intent":"pi_fixture","status":"succeeded","metadata":{"odexa_refund_id":"%s"}}
                """.formatted(refund);
        for (int scenario = 0; scenario < 4; scenario++) {
            String data = scenario == 1 ? "" : scenario == 2 ? result + "," + result : result;
            String response = "{\"object\":\"list\",\"data\":[" + data + "],\"has_more\":" + (scenario == 3) + "}";
            try (var fixture = new Fixture(exchange -> {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("/v1/refunds", exchange.getRequestURI().getPath());
                assertTrue(exchange.getRequestURI().getRawQuery().contains("payment_intent=pi_fixture"));
                return response;
            })) {
                if (scenario == 0) assertEquals(PaymentProvider.RefundOutcome.SUCCEEDED,
                        fixture.provider.lookupRefund(command, null).outcome());
                else assertThrows(PaymentProvider.UncertainOutcome.class, () -> fixture.provider.lookupRefund(command, null));
            }
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
        Fixture(Handler handler) throws Exception { this(200, handler); }
        Fixture(int status, Handler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] bytes = handler.respond(exchange).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            provider = new StripePaymentProvider(StripePaymentProvider.client("rk_test_fixture", "http://127.0.0.1:" + server.getAddress().getPort()));
        }
        public void close() { server.stop(0); }
    }
}
