package commerce.catalog;

import commerce.runtime.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class CatalogPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private final UUID tenant = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private CatalogService service;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new CatalogService(jdbc);
    }

    @Test void batchReadsOneSnapshotDuringConcurrentCatalogChangeAndHidesForeignIds() throws Exception {
        Product a = service.create(tenant,input("A")), b = service.create(tenant,input("B"));
        Product foreign = service.create(UUID.randomUUID(),input("Foreign"));
        for (UUID unavailable : java.util.List.of(foreign.id(),UUID.randomUUID())) {
            var error = assertThrows(ApiException.class,() -> service.batch(tenant,java.util.List.of(a.id(),unavailable)));
            assertEquals(404,error.status()); assertEquals("PRODUCT_NOT_FOUND",error.code());
        }
        var updatedButUncommitted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE product SET unit_price_minor = 9000, version = version + 1 WHERE tenant_id = ?",tenant);
                updatedButUncommitted.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            assertTrue(updatedButUncommitted.await(10,TimeUnit.SECONDS));
            try {
                var accepted = service.batch(tenant,java.util.List.of(b.id(),a.id()));
                assertTrue(accepted.stream().allMatch(p -> p.unitPriceMinor() == a.unitPriceMinor()));
            } finally { release.countDown(); }
            future.get(10,TimeUnit.SECONDS);
        }
        assertTrue(service.batch(tenant,java.util.List.of(a.id(),b.id())).stream().allMatch(p -> p.unitPriceMinor() == 9000));
    }

    @Test
    void keysetPaginationSearchAndCrossTenantIsolation() {
        UUID otherTenant = UUID.randomUUID();
        Product hidden = service.create(otherTenant, input("Hidden"));
        for (int i = 0; i < 7; i++) {
            service.create(tenant, input("Tote " + i));
        }
        var seen = new HashSet<UUID>();
        String cursor = null;
        do {
            ProductPage page = service.list(tenant, 2, cursor, "tOtE");
            assertTrue(page.items().size() <= 2);
            for (Product item : page.items()) {
                assertTrue(seen.add(item.id()), "A stable cursor must not repeat a product");
                assertNotEquals(hidden.id(), item.id());
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(7, seen.size());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(tenant, hidden.id())).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.update(tenant, hidden.id(), 1, input("Overwrite"))).status());
        service.create(tenant, input("100% tote_"));
        assertEquals(1, service.list(tenant, 20, null, "%").items().size());
        assertEquals(1, service.list(tenant, 20, null, "_").items().size());
    }

    @Test
    void concurrentCompareAndSwapAllowsOnlyOneMerchantUpdate() throws Exception {
        Product product = service.create(tenant, input("Original"));
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                outcomes.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        service.update(tenant, product.id(), 1, input("Changed"));
                        return 200;
                    } catch (ApiException error) {
                        return error.status();
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var outcome : outcomes) {
                int status = outcome.get(20, TimeUnit.SECONDS);
                if (status == 200) winners++;
                else assertEquals(412, status);
            }
            assertEquals(1, winners);
        }
        assertEquals(2, service.get(tenant, product.id()).version());
    }

    @Test
    void localSeedMatchesSchemaAndIsRestartSafeForBothTenants() {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("data-local.sql"));
        populator.execute(jdbc.getDataSource());
        populator.execute(jdbc.getDataSource());
        for (var entry : java.util.Map.of(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "11111111-1111-4111-8111-111111111111",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "22222222-2222-4222-8222-222222222222").entrySet()) {
            Product product = service.get(UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue()));
            assertEquals(2500, product.unitPriceMinor());
            assertTrue(product.active());
        }
    }

    private static ProductInput input(String name) {
        return new ProductInput(name, "", 2500L, "USD", true);
    }
}
