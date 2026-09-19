package commerce.catalog;

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
        jdbc.update("INSERT INTO product VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111','Legacy product','',2500,'USD',true,7)");
        assertEquals("Legacy product:7", jdbc.queryForObject("SELECT name || ':' || version FROM product", String.class));
    }

    @Test
    void explicitLegacyBaselinePreservesBusinessAndTransportData() {
        var source = isolated();
        new ResourceDatabasePopulator(new ClassPathResource("legacy/v0_1_0.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO product VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111','Legacy product','',2500,'USD',true,7)");
        jdbc.update("INSERT INTO outbox(event_id,tenant_id,aggregate_id,topic,payload,occurred_at) VALUES (gen_random_uuid(),gen_random_uuid(),'legacy','commerce.events.v1','{}',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO inbox(consumer,event_id) VALUES ('legacy',gen_random_uuid())");
        // Default startup fails closed instead of guessing that an arbitrary schema is V1.
        assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> Flyway.configure().dataSource(source).load().migrate());
        var flyway = Flyway.configure().dataSource(source).baselineVersion("1").load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
        assertEquals("Legacy product:7", jdbc.queryForObject("SELECT name || ':' || version FROM product", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE dispatch_started_at IS NOT NULL AND published_at IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox", Integer.class));
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
