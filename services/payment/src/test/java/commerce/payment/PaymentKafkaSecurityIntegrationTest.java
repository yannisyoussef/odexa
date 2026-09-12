package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import commerce.runtime.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"payment.worker.enabled=false", "runtime.events.retry-interval-ms=10",
        "runtime.events.retry-attempts=1"})
@AutoConfigureMockMvc
class PaymentKafkaSecurityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");
    private static final String PROVIDER_KEY = UUID.randomUUID().toString();
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("payment.provider-api-key", () -> PROVIDER_KEY);
    }

    @Test void kafkaDuplicateDeliveryCreatesDurableWorkAndHttpEnforcesTenantAndOwner() throws Exception {
        UUID order = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String raw = mapper.writeValueAsString(new Event(eventId, "inventory.reserved", 1, Instant.now(),
                UUID.randomUUID().toString(), null, tenant, mapper.valueToTree(Map.of("orderId", order,
                "customerId", "customer-a", "productId", UUID.randomUUID(), "quantity", 1,
                "totalMinor", 2500, "currency", "USD", "paymentMethod", "pm_approved"))));
        kafka.send("commerce.events.v1", order.toString(), raw).get(10, java.util.concurrent.TimeUnit.SECONDS);
        kafka.send("commerce.events.v1", order.toString(), raw).get(10, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && countPayment(order) == 0) Thread.sleep(50);
        assertEquals(1, countPayment(order));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, eventId));
        String path = "/api/v1/payments/" + order;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject("customer-a")
                .claim("tenant_id", tenant.toString())))).andExpect(status().isOk());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject("customer-a")
                .claim("tenant_id", UUID.randomUUID().toString())))).andExpect(status().isNotFound());
        mvc.perform(get(path).header("X-Tenant-ID", tenant.toString()).with(jwt().jwt(token -> token
                .subject("customer-other-a").claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of("PLATFORM_ADMIN")))))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject("customer-a"))))
                .andExpect(status().isForbidden());
    }

    @Test void malformedOwnedEventReachesDltWithoutAcknowledgedBusinessWork() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var dlt = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "payment-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            var partitions = List.of(new TopicPartition("commerce.events.v1.DLT", 0),
                    new TopicPartition("commerce.events.v1.DLT", 1), new TopicPartition("commerce.events.v1.DLT", 2));
            dlt.assign(partitions);
            dlt.seekToBeginning(partitions);
            String invalid = mapper.writeValueAsString(new Event(UUID.randomUUID(), "inventory.reserved", 1,
                    Instant.now(), UUID.randomUUID().toString(), null, UUID.randomUUID(),
                    mapper.valueToTree(Map.of("orderId", key))));
            kafka.send("commerce.events.v1", key, invalid).get(10, java.util.concurrent.TimeUnit.SECONDS);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean found = false;
            while (System.nanoTime() < deadline && !found) {
                for (var record : dlt.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) found = true;
                }
            }
            assertTrue(found, "Invalid reservation must be published to DLT");
            assertEquals(0, countPayment(UUID.fromString(key)));
        }
    }

    private int countPayment(UUID order) {
        return jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Integer.class, order);
    }
}
