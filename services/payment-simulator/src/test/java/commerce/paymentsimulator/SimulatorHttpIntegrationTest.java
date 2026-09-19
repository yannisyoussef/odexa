package commerce.paymentsimulator;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorHttpIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final String KEY = UUID.randomUUID().toString();
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryStore deliveries;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("simulator.provider-api-key", () -> KEY);
    }

    @BeforeEach void clean() { jdbc.execute("TRUNCATE provider_refund, provider_delivery, provider_payment"); }

    @Test void concurrentHttpDuplicatesHaveOneDurablePayloadAwareResult() throws Exception {
        UUID order = UUID.randomUUID();
        String payload = body(order, 2500, "pm_approved");
        var responses = new ArrayList<HttpResponse<String>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> post(order, payload, KEY)));
            for (var future : futures) responses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(19, responses.stream().filter(r -> r.statusCode() == 200).count());
        JsonNode first = mapper.readTree(responses.getFirst().body());
        for (var response : responses) assertEquals(first, mapper.readTree(response.body()));
        assertEquals("AUTHORIZED", first.get("status").stringValue());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_payment", Integer.class));
        String id = first.get("id").stringValue();
        assertEquals(200, get("/provider/v1/payments/" + id, KEY).statusCode());
        // A fresh service object reads the durable result, not a JVM idempotency cache.
        assertEquals(UUID.fromString(id), new SimulatorPayments(jdbc).get(UUID.fromString(id)).id());
        assertEquals(409, post(order, body(order, 2501, "pm_approved"), KEY).statusCode());
        assertEquals(409, post(order, body(order, 2500, "pm_declined"), KEY).statusCode());
        assertEquals(200, post(order, payload, KEY).statusCode());
    }

    @Test void declineIsAuthoritativeAndStable() throws Exception {
        UUID order = UUID.randomUUID();
        var first = post(order, body(order, 2500, "pm_declined"), KEY);
        var retry = post(order, body(order, 2500, "pm_declined"), KEY);
        assertEquals(201, first.statusCode());
        assertEquals(200, retry.statusCode());
        assertEquals(mapper.readTree(first.body()), mapper.readTree(retry.body()));
        assertEquals("DECLINED", mapper.readTree(first.body()).get("status").stringValue());
    }

    @Test void bothHttpOperationsRequireKeyAndNoExtraEndpointsExist() throws Exception {
        UUID order = UUID.randomUUID();
        String payload = body(order, 2500, "pm_approved");
        assertEquals(401, post(order, payload, null).statusCode());
        var rejected = post(order, payload, UUID.randomUUID().toString());
        assertEquals(401, rejected.statusCode());
        assertTrue(rejected.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertFalse(rejected.body().contains(KEY));
        assertEquals(401, get("/provider/v1/payments/" + UUID.randomUUID(), null).statusCode());
        assertEquals(404, get("/provider/v1/payments/" + UUID.randomUUID(), KEY).statusCode());
        assertEquals(404, get("/actuator/env", KEY).statusCode());
        assertEquals(404, get("/testing", KEY).statusCode());
        assertEquals(404, get("/provider/v1/testing", KEY).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_payment", Integer.class));
    }

    @Test void malformedPayloadAndKeyAreSanitizedAndCannotCreateRows() throws Exception {
        UUID order = UUID.randomUUID();
        assertEquals(400, post(UUID.randomUUID(), body(order, 2500, "pm_approved"), KEY).statusCode());
        assertEquals(400, post(order, body(order, 0, "pm_approved"), KEY).statusCode());
        assertEquals(400, post(order, body(order, 2500, "unsupported"), KEY).statusCode());
        assertEquals(400, post(order, body(order, 2500, "pm_approved")
                .replace("2500", "2500.5"), KEY).statusCode());
        assertEquals(400, post(order, body(order, 2500, "pm_approved")
                .replace("2500", "\"2500\""), KEY).statusCode());
        var malformed = post(order, "{malformed", KEY);
        assertEquals(400, malformed.statusCode());
        assertFalse(malformed.body().contains("{malformed"));
        String callbackPayload = mapper.writeValueAsString(Map.of("orderId", order, "amountMinor", 2500,
                "currency", "USD", "paymentMethod", "pm_approved", "callbackUrl", "http://localhost/unused"));
        assertEquals(400, post(order, callbackPayload, KEY).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_payment", Integer.class));
    }

    @Test void durableLostPaymentResponsesAreRecoverableWithoutNewCharges() throws Exception {
        for (String method : java.util.List.of("pm_lost_response", "pm_reconcile_declined")) {
            UUID order = UUID.randomUUID();
            assertEquals(503, post(order, body(order, 2500, method), KEY).statusCode());
            assertEquals(503, post(order, body(order, 2500, method), KEY).statusCode());
            var outcome = mapper.readTree(get("/provider/v1/payments/by-order/" + order, KEY).body());
            assertEquals(method.equals("pm_lost_response") ? "AUTHORIZED" : "DECLINED", outcome.path("status").stringValue());
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM provider_payment", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM provider_delivery", Integer.class));
    }

    @Test void refundScenariosHaveDurableIndependentIdempotencyAndNoSecondActiveRefund() throws Exception {
        for (String method : java.util.List.of("pm_approved", "pm_refund_declined", "pm_refund_unknown", "pm_refund_lost")) {
            UUID order = UUID.randomUUID(), refund = UUID.randomUUID();
            var payment = mapper.readTree(post(order, body(order, 2500, method), KEY).body());
            String payload = mapper.writeValueAsString(Map.of("refundId", refund, "paymentId", payment.path("id").stringValue(),
                    "amountMinor", 2500, "currency", "USD"));
            int first = method.equals("pm_refund_lost") ? 503 : 201;
            assertEquals(first, refundPost(refund, payload).statusCode());
            assertEquals(first == 503 ? 503 : 200, refundPost(refund, payload).statusCode());
            var found = mapper.readTree(get("/provider/v1/refunds/" + refund, KEY).body());
            String expected = method.equals("pm_refund_declined") ? "FAILED" : method.equals("pm_refund_unknown") ? "REVIEW_REQUIRED" : "SUCCEEDED";
            assertEquals(expected, found.path("status").stringValue());
            assertEquals(expected, new SimulatorRefunds(jdbc).get(refund).status());
            assertEquals(409, refundPost(refund, payload.replace("2500", "2501")).statusCode());
            UUID second = UUID.randomUUID();
            assertEquals(expected.equals("FAILED") ? 201 : 409,
                    refundPost(second, payload.replace(refund.toString(), second.toString())).statusCode());
        }
    }

    @Test void refundForUnknownProviderPaymentIsHiddenAsNotFound() throws Exception {
        UUID refund = UUID.randomUUID();
        String payload = mapper.writeValueAsString(Map.of("refundId", refund, "paymentId", UUID.randomUUID(),
                "amountMinor", 2500, "currency", "USD"));
        assertEquals(404, refundPost(refund, payload).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_refund", Integer.class));
    }

    @Test void callbackRetryUsesSameDurableEventAndFreshSignatureAndFencesExpiredWorkers() throws Exception {
        UUID order = UUID.randomUUID(); post(order, body(order, 2500, "pm_approved"), KEY);
        var first = deliveries.claim().orElseThrow();
        assertTrue(deliveries.claim().isEmpty());
        jdbc.execute("UPDATE provider_delivery SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var second = deliveries.claim().orElseThrow();
        deliveries.finish(first, true);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_delivery WHERE delivered_at IS NOT NULL", Integer.class));
        deliveries.finish(second, false);
        jdbc.execute("UPDATE provider_delivery SET next_attempt_at = CURRENT_TIMESTAMP");
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var identity = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/webhooks/simulator", exchange -> {
            try (exchange) {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                String signature = exchange.getRequestHeaders().getFirst("Simulator-Signature");
                long timestamp = Long.parseLong(signature.split(",")[0].substring(2));
                try { assertEquals(DeliveryWorker.signature(bytes, "fixture_signing", timestamp), signature); }
                catch (java.security.GeneralSecurityException error) { throw new java.io.IOException(error); }
                String id = mapper.readTree(bytes).path("id").stringValue();
                if (identity.get() == null) identity.set(id); else assertEquals(identity.get(), id);
                exchange.sendResponseHeaders(received.getAndIncrement() == 0 ? 503 : 204, -1);
            }
        }); server.start();
        try {
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/webhooks/simulator";
            new DeliveryWorker(deliveries, mapper, target, "fixture_signing").runOnce();
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_delivery WHERE delivered_at IS NOT NULL", Integer.class));
            jdbc.execute("UPDATE provider_delivery SET next_attempt_at = CURRENT_TIMESTAMP");
            new DeliveryWorker(deliveries, mapper, target, "fixture_signing").runOnce();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM provider_delivery WHERE delivered_at IS NOT NULL", Integer.class));
            assertEquals(2, received.get());
        } finally { server.stop(0); }
    }

    private HttpResponse<String> refundPost(UUID key, String payload) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/provider/v1/refunds")).timeout(Duration.ofSeconds(10))
                .header("X-Provider-Key", KEY).header("Idempotency-Key", key.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String body(UUID order, long amount, String method) {
        return mapper.writeValueAsString(Map.of("orderId", order, "amountMinor", amount,
                "currency", "USD", "paymentMethod", method));
    }
    private HttpResponse<String> post(UUID key, String body, String providerKey) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/provider/v1/payments"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString());
        if (providerKey != null) builder.header("X-Provider-Key", providerKey);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String providerKey) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (providerKey != null) builder.header("X-Provider-Key", providerKey);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
