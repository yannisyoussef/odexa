package commerce.inventory;

import commerce.runtime.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = InventoryApplication.class, properties = {
        "runtime.outbox.poll-delay-ms=100", "runtime.events.retry-interval-ms=10", "runtime.events.retry-attempts=1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryKafkaIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired InventoryService inventory;

    @Test
    void kafkaDeliveryUsesRealTransactionalListenerAndOutboxThenReleasesExactlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_stock (tenant_id, product_id, on_hand) VALUES (?, ?, 10)", tenant, product);
        Event created = event("order.created", tenant,
                new OrderCreated(order, "customer-a", product, 2, 5000, "USD", "pm_declined"));
        send(order, created);
        send(order, created);
        await(() -> inventory.get(tenant, product).reserved() == 2);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE tenant_id = ? AND published_at IS NOT NULL",
                Integer.class, tenant) == 1);
        Event reserved = mapper.readValue(jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE tenant_id = ? AND aggregate_id = ?
                AND payload->>'eventType' = 'inventory.reserved'
                """, String.class, tenant, order.toString()), Event.class);
        assertEquals(created.eventId(), reserved.causationId());
        assertEquals(reserved.eventId(), jdbc.queryForObject("""
                SELECT reservation_event_id FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?
                """, UUID.class, tenant, order));
        Event declined = event("payment.declined", tenant, Map.of("orderId", order, "paymentId", UUID.randomUUID()),
                reserved.eventId());
        send(order, declined);
        send(order, declined);
        await(() -> inventory.get(tenant, product).reserved() == 0);
        Event conflicting = event("payment.authorized", tenant, Map.of("orderId", order, "paymentId", UUID.randomUUID()),
                reserved.eventId());
        send(order, conflicting);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM inbox WHERE consumer = ? AND event_id = ?",
                Integer.class, InventoryEvents.CONSUMER, conflicting.eventId()) == 1);
        Stock stock = inventory.get(tenant, product);
        assertEquals(10, stock.onHand());
        assertEquals(0, stock.reserved());
        assertEquals(3, stock.version());
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT state FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?", String.class, tenant, order));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE tenant_id = ?", Integer.class, tenant));
    }

    @Test
    void malformedOwnedPayloadReachesDltWithoutStockOrInboxMutation() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_stock (tenant_id, product_id, on_hand) VALUES (?, ?, 10)", tenant, product);
        Event malformed = event("order.created", tenant, Map.of("orderId", order));
        Map<String, Object> config = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "inventory-dlt-test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "default.api.timeout.ms", 5000, "request.timeout.ms", 5000);
        try (var consumer = new KafkaConsumer<String, String>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(java.util.List.of("commerce.events.v1.DLT"));
            send(order, malformed);
            boolean recovered = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!recovered && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (order.toString().equals(record.key())) {
                        Event deadLetter = mapper.readValue(record.value(), Event.class);
                        assertEquals(malformed.eventId(), deadLetter.eventId());
                        recovered = true;
                    }
                }
            }
            assertTrue(recovered, "Malformed owned payload must be published to the DLT");
        }
        assertEquals(0, inventory.get(tenant, product).reserved());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, malformed.eventId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE tenant_id = ?", Integer.class, tenant));
    }

    private Event event(String type, UUID tenant, Object payload) {
        return event(type, tenant, payload, null);
    }

    private Event event(String type, UUID tenant, Object payload, UUID causationId) {
        return new Event(UUID.randomUUID(), type, 1, Instant.now(), UUID.randomUUID().toString(), causationId,
                tenant, mapper.valueToTree(payload));
    }

    private void send(UUID order, Event event) throws Exception {
        kafka.send("commerce.events.v1", order.toString(), mapper.writeValueAsString(event))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for committed inventory processing");
    }
}
