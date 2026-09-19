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
        jdbc.update("INSERT INTO outbox(event_id,tenant_id,aggregate_id,topic,payload,occurred_at) VALUES (gen_random_uuid(),gen_random_uuid(),'legacy','commerce.events.v1','{}',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO inbox(consumer,event_id) VALUES ('legacy',gen_random_uuid())");
        // Default startup fails closed instead of guessing that an arbitrary schema is V1.
        assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> Flyway.configure().dataSource(source).load().migrate());
        var flyway = Flyway.configure().dataSource(source).baselineVersion("1").load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
        assertEquals("legacy-owner:legacy-key:5000", jdbc.queryForObject("SELECT customer_id || ':' || idempotency_key || ':' || total_minor FROM customer_order", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE dispatch_started_at IS NOT NULL AND published_at IS NULL", Integer.class));
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
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
    }

    private static DriverManagerDataSource isolated() {
        var root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(root).execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
