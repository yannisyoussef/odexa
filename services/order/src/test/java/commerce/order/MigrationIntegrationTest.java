package commerce.order;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void freshMigrationAndRestartValidateWithoutReapplying() {
        var source = isolated();
        var flyway = Flyway.configure().dataSource(source).load();
        assertTrue(flyway.migrate().migrationsExecuted > 0);
        flyway.validate();
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO customer_order (id,tenant_id,customer_id,idempotency_key,fingerprint,product_id,quantity,product_name,unit_price_minor,total_minor,currency,catalog_version,payment_method,status,version,created_at) VALUES ('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','legacy-owner','legacy-key',repeat('a',64),'22222222-2222-4222-8222-222222222222',2,'Legacy product',2500,5000,'USD',1,'pm_approved','CREATED',0,'2026-01-01T00:00:00Z')");
        assertEquals("legacy-owner:legacy-key:5000", jdbc.queryForObject("SELECT customer_id || ':' || idempotency_key || ':' || total_minor FROM customer_order", String.class));
    }

    @Test
    void explicitLegacyBaselinePreservesBusinessAndTransportData() {
        var source = isolated();
        new ResourceDatabasePopulator(new ClassPathResource("legacy/v0_1_0.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO customer_order (id,tenant_id,customer_id,idempotency_key,fingerprint,product_id,quantity,product_name,unit_price_minor,total_minor,currency,catalog_version,payment_method,status,version,created_at) VALUES ('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','legacy-owner','legacy-key',repeat('a',64),'22222222-2222-4222-8222-222222222222',2,'Legacy product',2500,5000,'USD',1,'pm_approved','CREATED',0,'2026-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO customer_order (id,tenant_id,customer_id,idempotency_key,fingerprint,product_id,quantity,product_name,unit_price_minor,total_minor,currency,catalog_version,payment_method,status,version,created_at,reservation_event_id) VALUES ('33333333-3333-4333-8333-333333333333','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','legacy-owner','confirmed-key',repeat('b',64),'22222222-2222-4222-8222-222222222222',1,'Legacy product',2500,2500,'USD',1,'pm_approved','CONFIRMED',2,'2026-01-01T00:00:00Z',gen_random_uuid())");
        // v0.1.0 cannot prove whether an unpublished checkout already reached the broker.
        jdbc.update("""
                INSERT INTO outbox(event_id,tenant_id,aggregate_id,topic,payload,occurred_at,published_at)
                SELECT gen_random_uuid(), tenant_id, id::text, 'commerce.events.v1',
                    jsonb_build_object('eventType','order.created','correlationId',gen_random_uuid()::text,'payload',jsonb_build_object('orderId',id::text)),
                    created_at, CASE WHEN status = 'CONFIRMED' THEN created_at END
                FROM customer_order
                """);
        jdbc.update("INSERT INTO inbox(consumer,event_id) VALUES ('legacy',gen_random_uuid())");
        // Default startup fails closed instead of guessing that an arbitrary schema is V1.
        assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> Flyway.configure().dataSource(source).load().migrate());
        var flyway = Flyway.configure().dataSource(source).baselineVersion("1").load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
        assertEquals("legacy-owner:legacy-key:5000", jdbc.queryForObject("SELECT customer_id || ':' || idempotency_key || ':' || total_minor FROM customer_order WHERE idempotency_key = 'legacy-key'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE dispatch_started_at IS NOT NULL AND published_at IS NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox", Integer.class));
        var orders = new OrderRepository(jdbc);
        var restored = orders.findByKey(java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "legacy-owner", "legacy-key").orElseThrow();
        assertEquals(5000, restored.order().snapshot().totalMinor());
        assertEquals(OrderStatus.CREATED, restored.order().status());
        var history = orders.history(restored.order().id());
        assertEquals(1, history.size());
        assertEquals("LEGACY_SNAPSHOT", history.getFirst().reason());
        assertEquals(restored.order().version(), history.getFirst().version());
        assertTrue(history.getFirst().occurredAt().isAfter(restored.order().createdAt()));
        var confirmed = orders.findByKey(restored.order().tenantId(), "legacy-owner", "confirmed-key").orElseThrow().order();
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(2, orders.history(confirmed.id()).getFirst().version());

        // The new application treats the legacy checkout as possibly dispatched: never cancellable or
        // expirable, and still delivered by the fenced publisher.
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var lifecycle = new OrderLifecycle(orders, new commerce.runtime.Outbox(jdbc, mapper),
                java.time.Clock.fixed(java.time.Instant.parse("2030-01-01T00:00:00Z"), java.time.ZoneOffset.UTC), java.time.Duration.ofMinutes(30));
        var owner = new commerce.runtime.Actor(restored.order().tenantId(), "legacy-owner", java.util.Set.of("CUSTOMER"));
        assertEquals(409, assertThrows(commerce.runtime.ApiException.class,
                () -> transaction.execute(status -> lifecycle.cancel(owner, restored.order().id()))).status());
        assertEquals(0, transaction.<Integer>execute(status -> lifecycle.expireBatch()));
        var sentKeys = new java.util.ArrayList<Object>();
        var publisher = new commerce.runtime.OutboxPublisher(jdbc, recording(sentKeys), manager, 25, 1000);
        assertEquals(1, publisher.publishBatch());
        assertEquals(java.util.List.of(restored.order().id().toString()), sentKeys);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class));
        assertEquals(OrderStatus.CREATED, orders.findByKey(restored.order().tenantId(), "legacy-owner", "legacy-key").orElseThrow().order().status());
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.kafka.core.KafkaTemplate<Object, Object> recording(java.util.List<Object> keys) {
        var kafka = (org.springframework.kafka.core.KafkaTemplate<Object, Object>) org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    keys.add(call.getArgument(1));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        return kafka;
    }

    @Test void releasedV020StoppedOrdersAndHistorySurviveOpaqueReferenceUpgrade() {
        var source = isolated();
        Flyway.configure().dataSource(source).target("3").load().migrate();
        var jdbc = new JdbcTemplate(source);
        for (String status : java.util.List.of("CANCELLED", "EXPIRED")) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO customer_order(id,tenant_id,customer_id,idempotency_key,fingerprint,product_id,
                        quantity,product_name,unit_price_minor,total_minor,currency,catalog_version,payment_method,status,version,created_at)
                    VALUES (?, ?, 'owner', ?, repeat('a',64), ?, 1, 'product', 2500, 2500, 'USD', 1, 'pm_approved', ?, 1, CURRENT_TIMESTAMP)
                    """, id, UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(), status);
            jdbc.update("INSERT INTO order_history(order_id,version,status,occurred_at,reason) VALUES (?,1,?,CURRENT_TIMESTAMP,?)",
                    id, status, status.equals("CANCELLED") ? "CUSTOMER_CANCELLED" : "DISPATCH_EXPIRED");
        }
        var before = jdbc.queryForList("SELECT * FROM customer_order ORDER BY id");
        var history = jdbc.queryForList("SELECT * FROM order_history ORDER BY order_id");
        var upgrade = Flyway.configure().dataSource(source).load(); upgrade.migrate(); upgrade.validate();
        assertEquals(before, jdbc.queryForList("SELECT * FROM customer_order ORDER BY id"));
        assertEquals(history, jdbc.queryForList("SELECT * FROM order_history ORDER BY order_id"));
        assertEquals(0, upgrade.migrate().migrationsExecuted);
    }

    private static DriverManagerDataSource isolated() {
        var root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(root).execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
