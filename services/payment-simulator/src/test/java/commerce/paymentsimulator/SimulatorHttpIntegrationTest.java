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
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("simulator.provider-api-key", () -> KEY);
    }

    @BeforeEach void clean() { jdbc.execute("TRUNCATE provider_payment"); }

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
