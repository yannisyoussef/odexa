package commerce.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import commerce.runtime.ApiException;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import commerce.runtime.Outbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(OrderDatabaseIntegrationTest.DatabaseConfig.class)
class OrderDatabaseIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired OrderRepository orders;
    @Autowired CheckoutWriter writer;
    @Autowired OrderEventConsumer consumer;
    @Autowired Inbox inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanIsolatedContainerDatabase() {
        jdbc.execute("TRUNCATE TABLE customer_order, inbox, outbox");
    }

    @Test
    void concurrentSameKeyHasExactlyOneOrderAndOneOutboxEvent() throws Exception {
        int contenders = 12;
        UUID tenant = UUID.randomUUID();
        CheckoutSnapshot snapshot = OrderStateMachineTest.created().snapshot();
        String fingerprint = fingerprint(snapshot);
        CyclicBarrier start = new CyclicBarrier(contenders);
        List<CheckoutWriter.Result> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(contenders)) {
            List<Future<CheckoutWriter.Result>> futures = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return writer.create(candidate(tenant, "customer-a", snapshot), "concurrent", fingerprint);
                }));
            }
            for (Future<CheckoutWriter.Result> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, results.stream().filter(CheckoutWriter.Result::created).count());
        assertEquals(1, results.stream().map(result -> result.order().id()).distinct().count());
        assertEquals(1, results.stream().map(result -> result.order().createdAt()).distinct().count());
        assertEquals(1, count("customer_order"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void concurrentDifferentPayloadReturnsOne409WithoutPoisoningDatabaseTransaction() throws Exception {
        UUID tenant = UUID.randomUUID();
        CheckoutSnapshot first = OrderStateMachineTest.created().snapshot();
        CheckoutSnapshot second = new CheckoutSnapshot(first.productId(), 1, first.productName(),
                first.unitPriceMinor(), first.unitPriceMinor(), first.currency(), first.catalogVersion(), first.paymentMethod());
        CyclicBarrier start = new CyclicBarrier(2);
        List<Integer> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (CheckoutSnapshot snapshot : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        writer.create(candidate(tenant, "customer-a", snapshot), "collision", fingerprint(snapshot));
                        return 201;
                    } catch (ApiException conflict) {
                        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
                        return conflict.status();
                    }
                }));
            }
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertTrue(outcomes.containsAll(List.of(201, 409)));
        assertEquals(1, count("customer_order"));
        assertEquals(1, count("outbox"));
        OrderRepository.StoredOrder winner = orders.findByKey(tenant, "customer-a", "collision").orElseThrow();
        CheckoutWriter.Result retry = writer.create(candidate(tenant, "customer-a", winner.order().snapshot()),
                "collision", winner.fingerprint());
        assertFalse(retry.created());
        assertEquals(winner.order().id(), retry.order().id());
    }

    @Test
    void sameKeyIsIndependentAcrossTenantsAndCustomersAndReadsAreOwnerScoped() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        CheckoutSnapshot snapshot = OrderStateMachineTest.created().snapshot();
        Order a = writer.create(candidate(tenantA, "customer-a", snapshot), "shared", fingerprint(snapshot)).order();
        Order b = writer.create(candidate(tenantB, "customer-a", snapshot), "shared", fingerprint(snapshot)).order();
        Order other = writer.create(candidate(tenantA, "customer-other", snapshot), "shared", fingerprint(snapshot)).order();
        assertNotEquals(a.id(), b.id());
        assertNotEquals(a.id(), other.id());
        assertEquals(3, count("customer_order"));
        assertEquals(3, count("outbox"));
        assertTrue(orders.findOwned(tenantB, "customer-a", a.id()).isEmpty());
        assertTrue(orders.findOwned(tenantA, "customer-other", a.id()).isEmpty());
        assertEquals(a, orders.findOwned(tenantA, "customer-a", a.id()).orElseThrow());
    }

    @Test
    void outboxFailureRollsBackNewOrderAndKeyRemainsReusable() {
        Order candidate = OrderStateMachineTest.created();
        Outbox failingOutbox = mock(Outbox.class);
        doThrow(new IllegalStateException("simulated outbox failure"))
                .when(failingOutbox).append(anyString(), any(), anyString(), any(), isNull());
        CheckoutWriter failingWriter = new CheckoutWriter(orders, failingOutbox);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                failingWriter.create(candidate, "rollback", fingerprint(candidate.snapshot()))));
        assertEquals(0, count("customer_order"));
        assertEquals(0, count("outbox"));
        assertTrue(writer.create(candidate, "rollback", fingerprint(candidate.snapshot())).created());
    }

    @Test
    void paymentCanArriveBeforeReservationAndInboxDedupIsDurable() {
        Order order = persist();
        UUID reservationId = UUID.randomUUID();
        Event payment = payment(order, reservationId, true);
        consumer.onMessage(mapper.writeValueAsString(payment));
        consumer.onMessage(mapper.writeValueAsString(payment));
        Order deferred = owned(order);
        assertEquals(OrderStatus.CREATED, deferred.status());
        assertEquals(payment.eventId(), deferred.deferredPayment().eventId());
        assertEquals(1, count("inbox"));
        assertEquals(1, count("outbox"));
        Event reserved = reserved(order, reservationId);
        consumer.onMessage(mapper.writeValueAsString(reserved));
        consumer.onMessage(mapper.writeValueAsString(reserved));
        Order confirmed = owned(order);
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(2, confirmed.version());
        assertNull(confirmed.deferredPayment());
        assertEquals(2, count("inbox"));
        assertEquals(2, count("outbox"));
        String cause = jdbc.queryForObject("SELECT payload->>'causationId' FROM outbox WHERE payload->>'eventType' = 'order.confirmed'", String.class);
        assertEquals(payment.eventId().toString(), cause);
        consumer.onMessage(mapper.writeValueAsString(payment(order, reservationId, false)));
        assertEquals(confirmed, owned(order));
        assertEquals(2, count("outbox"));
    }

    @Test
    void causalConflictRollsBackInboxSoMessageCanBeRetried() {
        Order order = persist();
        UUID reservationId = UUID.randomUUID();
        Event payment = payment(order, reservationId, true);
        consumer.onMessage(mapper.writeValueAsString(payment));
        Event wrongReservation = reserved(order, UUID.randomUUID());
        assertThrows(ApiException.class, () -> consumer.onMessage(mapper.writeValueAsString(wrongReservation)));
        assertEquals(1, count("inbox"));
        assertEquals(OrderStatus.CREATED, owned(order).status());
        consumer.onMessage(mapper.writeValueAsString(reserved(order, reservationId)));
        assertEquals(OrderStatus.CONFIRMED, owned(order).status());
    }

    @Test
    void confirmationOutboxFailureRollsBackStateAndInboxTogether() {
        Order order = persist();
        UUID reservationId = UUID.randomUUID();
        consumer.onMessage(mapper.writeValueAsString(reserved(order, reservationId)));
        Outbox failingOutbox = mock(Outbox.class);
        doThrow(new IllegalStateException("simulated outbox failure"))
                .when(failingOutbox).append(anyString(), any(), anyString(), any(), any());
        OrderEventConsumer failingConsumer = new OrderEventConsumer(mapper, inbox, failingOutbox, orders);
        String raw = mapper.writeValueAsString(payment(order, reservationId, true));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> failingConsumer.onMessage(raw)));
        assertEquals(OrderStatus.PENDING_PAYMENT, owned(order).status());
        assertEquals(1, count("inbox"));
        assertEquals(1, count("outbox"));
        consumer.onMessage(raw);
        assertEquals(OrderStatus.CONFIRMED, owned(order).status());
        assertEquals(2, count("inbox"));
        assertEquals(2, count("outbox"));
    }

    @Test
    void crossTenantEventCannotMutateAnOrderOrCommitItsInboxMarker() {
        Order order = persist();
        Event valid = reserved(order, UUID.randomUUID());
        Event crossTenant = new Event(valid.eventId(), valid.eventType(), 1, valid.occurredAt(),
                valid.correlationId(), valid.causationId(), UUID.randomUUID(), valid.payload());
        assertThrows(ApiException.class, () -> consumer.onMessage(mapper.writeValueAsString(crossTenant)));
        assertEquals(OrderStatus.CREATED, owned(order).status());
        assertEquals(0, count("inbox"));
    }

    private int count(String table) {
        // Test-owned constant identifiers only; no caller-controlled SQL.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Order persist() {
        Order order = OrderStateMachineTest.created();
        return writer.create(order, "checkout", fingerprint(order.snapshot())).order();
    }

    private Order owned(Order order) {
        return orders.findOwned(order.tenantId(), order.customerId(), order.id()).orElseThrow();
    }

    private Event reserved(Order order, UUID eventId) {
        CheckoutSnapshot s = order.snapshot();
        return event(order, "inventory.reserved", eventId, UUID.randomUUID(), Map.of(
                "orderId", order.id(), "customerId", order.customerId(), "productId", s.productId(),
                "quantity", s.quantity(), "totalMinor", s.totalMinor(), "currency", s.currency(), "paymentMethod", s.paymentMethod()));
    }

    private Event payment(Order order, UUID reservation, boolean authorized) {
        return event(order, authorized ? "payment.authorized" : "payment.declined", UUID.randomUUID(), reservation,
                Map.of("orderId", order.id(), "paymentId", UUID.randomUUID()));
    }

    private Event event(Order order, String type, UUID id, UUID cause, Object payload) {
        return new Event(id, type, 1, Instant.now(), UUID.randomUUID().toString(), cause,
                order.tenantId(), mapper.valueToTree(payload));
    }

    private static Order candidate(UUID tenant, String customer, CheckoutSnapshot snapshot) {
        return Order.create(UUID.randomUUID(), tenant, customer, snapshot, Instant.now());
    }

    private static String fingerprint(CheckoutSnapshot snapshot) {
        return new CheckoutRequest(snapshot.productId(), snapshot.quantity(), snapshot.paymentMethod()).fingerprint();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean OrderRepository orders(JdbcTemplate jdbc) { return new OrderRepository(jdbc); }
        @Bean Inbox inbox(JdbcTemplate jdbc) { return new Inbox(jdbc); }
        @Bean Outbox outbox(JdbcTemplate jdbc, ObjectMapper mapper) { return new Outbox(jdbc, mapper); }
        @Bean CheckoutWriter writer(OrderRepository orders, Outbox outbox) { return new CheckoutWriter(orders, outbox); }
        @Bean OrderEventConsumer consumer(ObjectMapper mapper, Inbox inbox, Outbox outbox, OrderRepository orders) {
            return new OrderEventConsumer(mapper, inbox, outbox, orders);
        }
    }
}
