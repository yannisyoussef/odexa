package commerce.inventory;

import commerce.runtime.ApiException;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import commerce.runtime.Outbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class InventoryPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InventoryService stock;
    private InventoryEvents listener;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setTimeout(15);
        stock = new InventoryService(jdbc);
        listener = new InventoryEvents(mapper, new Inbox(jdbc), new ReservationService(jdbc, new Outbox(jdbc, mapper)));
        jdbc.update("INSERT INTO inventory_stock (tenant_id, product_id, on_hand) VALUES (?, ?, 10)", tenant, product);
    }

    @Test
    void concurrentReservationsNeverOversellAndEachDecisionHasOneOutboxEvent() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Event created = created(order(UUID.randomUUID(), 2));
            tasks.add(() -> receive(created));
        }
        concurrently(tasks);
        Stock result = stock.get(tenant, product);
        assertEquals(10, result.onHand());
        assertEquals(10, result.reserved());
        assertEquals(0, result.available());
        assertEquals(6, result.version());
        assertEquals(5, count("SELECT count(*) FROM inventory_reservation WHERE tenant_id = ? AND state = 'RESERVED'"));
        assertEquals(7, count("SELECT count(*) FROM inventory_reservation WHERE tenant_id = ? AND state = 'REJECTED'"));
        assertEquals(12, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
        assertEquals(5, count("SELECT count(*) FROM outbox WHERE tenant_id = ? AND payload->>'eventType' = 'inventory.reserved'"));
        assertEquals(7, count("SELECT count(*) FROM outbox WHERE tenant_id = ? AND payload->>'eventType' = 'inventory.rejected'"));
        assertEquals(12, count("""
                SELECT count(*) FROM outbox WHERE tenant_id = ?
                AND aggregate_id = payload->'payload'->>'orderId' AND topic = 'commerce.events.v1'
                """));
    }

    @Test
    void duplicateOrderEventsAndConcurrentConflictingSettlementsApplyOnlyOnce() throws Exception {
        OrderCreated order = order(UUID.randomUUID(), 3);
        Event original = created(order);
        concurrently(List.of(() -> receive(original), () -> receive(original), () -> receive(created(order))));
        assertEquals(3, stock.get(tenant, product).reserved());
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
        concurrently(List.of(() -> receive(payment(order.orderId(), true)), () -> receive(payment(order.orderId(), false)),
                () -> receive(payment(order.orderId(), true)), () -> receive(payment(order.orderId(), false))));
        String state = reservationState(order.orderId());
        assertTrue(List.of("COMMITTED", "RELEASED").contains(state));
        Stock result = stock.get(tenant, product);
        assertEquals(state.equals("COMMITTED") ? 7 : 10, result.onHand());
        assertEquals(0, result.reserved());
        assertEquals(3, result.version());
        receive(original);
        assertEquals(result, stock.get(tenant, product));
    }

    @Test
    void authorizationCommitsAndDeclineReleasesWithoutDecreasingOnHand() {
        OrderCreated authorized = order(UUID.randomUUID(), 3);
        receive(created(authorized));
        Event authorization = payment(authorized.orderId(), true);
        receive(authorization);
        receive(authorization);
        receive(payment(authorized.orderId(), false));
        assertEquals("COMMITTED", reservationState(authorized.orderId()));
        assertEquals(7, stock.get(tenant, product).onHand());
        OrderCreated declined = order(UUID.randomUUID(), 2);
        receive(created(declined));
        receive(payment(declined.orderId(), false));
        receive(payment(declined.orderId(), true));
        assertEquals("RELEASED", reservationState(declined.orderId()));
        assertEquals(7, stock.get(tenant, product).onHand());
        assertEquals(0, stock.get(tenant, product).reserved());
    }

    @Test
    void wrongOrMissingPaymentCausationLeavesStockAndReservationUnchangedThenRealAuthorizationCommits() {
        OrderCreated order = order(UUID.randomUUID(), 3);
        Event original = created(order);
        receive(original);
        OrderCreated other = order(UUID.randomUUID(), 1);
        receive(created(other));
        Event reserved = reservedEvent(order.orderId());
        assertEquals(original.eventId(), reserved.causationId());
        assertEquals(reserved.eventId(), jdbc.queryForObject("""
                SELECT reservation_event_id FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?
                """, UUID.class, tenant, order.orderId()));
        Stock held = stock.get(tenant, product);
        for (UUID causationId : new UUID[]{null, UUID.randomUUID(), original.eventId(), reservedEvent(other.orderId()).eventId()}) {
            for (boolean authorized : new boolean[]{true, false}) {
                Event invalid = payment(order.orderId(), authorized, causationId);
                assertThrows(IllegalArgumentException.class, () -> receive(invalid));
                assertEquals(held, stock.get(tenant, product));
                assertEquals("RESERVED", reservationState(order.orderId()));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?",
                        Integer.class, invalid.eventId()));
            }
        }
        receive(payment(order.orderId(), true, reserved.eventId()));
        assertEquals("COMMITTED", reservationState(order.orderId()));
        assertEquals("RESERVED", reservationState(other.orderId()));
        Stock committed = stock.get(tenant, product);
        assertEquals(7, committed.onHand());
        assertEquals(1, committed.reserved());
        assertEquals(held.version() + 1, committed.version());
        for (UUID causationId : new UUID[]{null, UUID.randomUUID()}) {
            Event invalid = payment(order.orderId(), false, causationId);
            assertThrows(IllegalArgumentException.class, () -> receive(invalid));
            assertEquals(committed, stock.get(tenant, product));
            assertEquals("COMMITTED", reservationState(order.orderId()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?",
                    Integer.class, invalid.eventId()));
        }
        assertEquals(2, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
    }

    @Test
    void customerIdAt255CharacterBoundaryCanBeReservedAndSettled() {
        OrderCreated order = new OrderCreated(UUID.randomUUID(), "c".repeat(255), product, 2, 5000, "USD", "pm_approved");
        receive(created(order));
        assertEquals(order.customerId(), jdbc.queryForObject("""
                SELECT customer_id FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?
                """, String.class, tenant, order.orderId()));
        receive(payment(order.orderId(), true));
        assertEquals("COMMITTED", reservationState(order.orderId()));
        assertEquals(8, stock.get(tenant, product).onHand());
        assertEquals(0, stock.get(tenant, product).reserved());
    }

    @Test
    void outboxFailureRollsBackInboxStockAndReservationThenRedeliverySucceeds() {
        Outbox failing = mock(Outbox.class);
        doThrow(new IllegalStateException("injected outbox failure")).when(failing)
                .append(anyString(), any(), anyString(), any(), any());
        var broken = new InventoryEvents(mapper, new Inbox(jdbc), new ReservationService(jdbc, failing));
        Event event = created(order(UUID.randomUUID(), 3));
        assertThrows(IllegalStateException.class,
                () -> tx.executeWithoutResult(status -> broken.onEvent(record(event))));
        assertEquals(0, stock.get(tenant, product).reserved());
        assertEquals(1, stock.get(tenant, product).version());
        assertEquals(0, count("SELECT count(*) FROM inventory_reservation WHERE tenant_id = ?"));
        assertEquals(0, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, event.eventId()));
        receive(event);
        assertEquals(3, stock.get(tenant, product).reserved());
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
        String json = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE tenant_id = ?", String.class, tenant);
        Event published = mapper.readValue(json, Event.class);
        assertEquals(event.eventId(), published.causationId());
        assertEquals(event.correlationId(), published.correlationId());
        assertEquals(published.eventId(), jdbc.queryForObject("""
                SELECT reservation_event_id FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?
                """, UUID.class, tenant, UUID.fromString(event.payload().get("orderId").stringValue())));
    }

    @Test
    void missingReservationRollsBackInboxAndConflictingSnapshotDoesNotChangeStock() {
        Event earlyPayment = payment(UUID.randomUUID(), true, UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> receive(earlyPayment));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Integer.class, earlyPayment.eventId()));
        UUID orderId = UUID.randomUUID();
        receive(created(order(orderId, 3)));
        assertThrows(IllegalArgumentException.class, () -> receive(created(order(orderId, 4))));
        assertEquals(3, stock.get(tenant, product).reserved());
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE tenant_id = ?"));
    }

    @Test
    void concurrentMerchantUpdatesRespectEtagsAndReservedFloorAndTenant() throws Exception {
        receive(created(order(UUID.randomUUID(), 3)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> tx.executeWithoutResult(status -> stock.adjust(tenant, product, 2, 2))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> stock.get(UUID.randomUUID(), product)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> tx.executeWithoutResult(
                status -> stock.adjust(UUID.randomUUID(), product, 2, 100))).status());
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                try {
                    tx.executeWithoutResult(status -> stock.adjust(tenant, product, 2, 20));
                    wins.incrementAndGet();
                } catch (ApiException error) {
                    assertEquals(412, error.status());
                }
            });
        }
        concurrently(tasks);
        assertEquals(1, wins.get());
        assertEquals(20, stock.get(tenant, product).onHand());
        assertEquals(3, stock.get(tenant, product).reserved());
    }

    @Test
    void rejectedOrderRemainsRejectedAfterRestockingAndOtherTenantCannotUseStock() {
        OrderCreated order = order(UUID.randomUUID(), 11);
        receive(created(order));
        tx.executeWithoutResult(status -> stock.adjust(tenant, product, 1, 100));
        receive(created(order));
        assertEquals("REJECTED", reservationState(order.orderId()));
        assertEquals(0, stock.get(tenant, product).reserved());
        Event otherTenant = new Event(UUID.randomUUID(), "order.created", 1, Instant.now(),
                UUID.randomUUID().toString(), null, UUID.randomUUID(), mapper.valueToTree(order(UUID.randomUUID(), 1)));
        receive(otherTenant);
        assertEquals(0, stock.get(tenant, product).reserved());
        assertEquals(100, stock.get(tenant, product).onHand());
    }

    @Test
    void localSeedIsCompatibleAndRestartSafe() {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("data-local.sql"));
        populator.execute(jdbc.getDataSource());
        populator.execute(jdbc.getDataSource());
        assertEquals(100, stock.get(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("11111111-1111-4111-8111-111111111111")).onHand());
        assertEquals(100, stock.get(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                UUID.fromString("22222222-2222-4222-8222-222222222222")).onHand());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, tenant);
    }

    private String reservationState(UUID orderId) {
        return jdbc.queryForObject("SELECT state FROM inventory_reservation WHERE tenant_id = ? AND order_id = ?",
                String.class, tenant, orderId);
    }

    private OrderCreated order(UUID id, int quantity) {
        return new OrderCreated(id, "customer-a", product, quantity, quantity * 2500L, "USD", "pm_approved");
    }

    private Event created(OrderCreated order) {
        return new Event(UUID.randomUUID(), "order.created", 1, Instant.now(), UUID.randomUUID().toString(),
                null, tenant, mapper.valueToTree(order));
    }

    private Event reservedEvent(UUID orderId) {
        String json = jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE tenant_id = ? AND aggregate_id = ?
                AND payload->>'eventType' = 'inventory.reserved'
                """, String.class, tenant, orderId.toString());
        return mapper.readValue(json, Event.class);
    }

    private Event payment(UUID orderId, boolean authorized) {
        return payment(orderId, authorized, reservedEvent(orderId).eventId());
    }

    private Event payment(UUID orderId, boolean authorized, UUID causationId) {
        return new Event(UUID.randomUUID(), authorized ? "payment.authorized" : "payment.declined", 1,
                Instant.now(), UUID.randomUUID().toString(), causationId, tenant,
                mapper.valueToTree(Map.of("orderId", orderId, "paymentId", UUID.randomUUID())));
    }

    private ConsumerRecord<String, String> record(Event event) {
        return new ConsumerRecord<>("commerce.events.v1", 0, 0, event.payload().get("orderId").stringValue(),
                mapper.writeValueAsString(event));
    }

    private void receive(Event event) {
        tx.executeWithoutResult(status -> listener.onEvent(record(event)));
    }

    private static void concurrently(List<Runnable> actions) throws Exception {
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(actions.size())) {
            for (Runnable action : actions) {
                futures.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    action.run();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }
}
