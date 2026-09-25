package commerce.inventory;

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
        jdbc.update("INSERT INTO inventory_stock VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111',10,3,7)");
        assertEquals("10:3:7", jdbc.queryForObject("SELECT on_hand || ':' || reserved || ':' || version FROM inventory_stock", String.class));
    }

    @Test
    void explicitLegacyBaselinePreservesBusinessAndTransportData() {
        var source = isolated();
        new ResourceDatabasePopulator(new ClassPathResource("legacy/v0_1_0.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO inventory_stock VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111',10,3,7)");
        jdbc.update("INSERT INTO outbox(event_id,tenant_id,aggregate_id,topic,payload,occurred_at) VALUES (gen_random_uuid(),gen_random_uuid(),'legacy','commerce.events.v1','{}',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO inbox(consumer,event_id) VALUES ('legacy',gen_random_uuid())");
        // Default startup fails closed instead of guessing that an arbitrary schema is V1.
        assertThrows(org.flywaydb.core.api.FlywayException.class,
                () -> Flyway.configure().dataSource(source).load().migrate());
        var flyway = Flyway.configure().dataSource(source).baselineVersion("1").load();
        flyway.baseline();
        flyway.migrate();
        flyway.validate();
        assertEquals("10:3:7", jdbc.queryForObject("SELECT on_hand || ':' || reserved || ':' || version FROM inventory_stock", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE dispatch_started_at IS NOT NULL AND published_at IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox", Integer.class));
        assertEquals(0, Flyway.configure().dataSource(source).load().migrate().migrationsExecuted);
    }

    @Test void releasedV030ActiveHoldAndOldDurableCheckoutSurviveUpgradeAndSettle() {
        var source = isolated();
        Flyway.configure().dataSource(source).target("2").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID tenant = UUID.randomUUID(), product = UUID.randomUUID(), heldOrder = UUID.randomUUID(), reservedEvent = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_stock VALUES(?,?,10,3,7)",tenant,product);
        jdbc.update("INSERT INTO inventory_reservation VALUES(?,?,'owner',?,3,7500,'USD','pm_approved','RESERVED',?)",tenant,heldOrder,product,reservedEvent);
        var before = jdbc.queryForList("SELECT * FROM inventory_stock");
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(before,jdbc.queryForList("SELECT * FROM inventory_stock"));
        assertEquals(3,jdbc.queryForObject("SELECT quantity FROM reservation_line WHERE order_id = ?",Integer.class,heldOrder));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var reservations = new ReservationService(jdbc,new commerce.runtime.Outbox(jdbc,mapper));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        tx.executeWithoutResult(status -> reservations.settle(tenant,heldOrder,false,reservedEvent));
        assertEquals("10:0:8",jdbc.queryForObject("SELECT on_hand || ':' || reserved || ':' || version FROM inventory_stock",String.class));
        UUID replayOrder = UUID.randomUUID();
        var event = new commerce.runtime.Event(UUID.randomUUID(),"order.created",1,java.time.Instant.now(),UUID.randomUUID().toString(),null,tenant,
                mapper.valueToTree(java.util.Map.of("orderId",replayOrder,"customerId","owner","productId",product,"quantity",2,"totalMinor",5000,"currency","USD","paymentMethod","pm_approved")));
        var listener = new InventoryEvents(mapper,new commerce.runtime.Inbox(jdbc),reservations);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("commerce.events.v1",0,0,replayOrder.toString(),mapper.writeValueAsString(event));
        tx.executeWithoutResult(status -> listener.onEvent(record));
        tx.executeWithoutResult(status -> listener.onEvent(record));
        UUID cause = jdbc.queryForObject("SELECT reservation_event_id FROM inventory_reservation WHERE order_id = ?",UUID.class,replayOrder);
        tx.executeWithoutResult(status -> reservations.settle(tenant,replayOrder,true,cause));
        tx.executeWithoutResult(status -> reservations.settle(tenant,replayOrder,true,cause));
        assertEquals("8:0:10",jdbc.queryForObject("SELECT on_hand || ':' || reserved || ':' || version FROM inventory_stock",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM inbox",Integer.class));
    }

    private static DriverManagerDataSource isolated() {
        var root = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(root).execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
