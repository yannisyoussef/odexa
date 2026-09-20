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
    @Autowired Outbox outbox;
    @Autowired OrderLifecycle lifecycle;
    @Autowired OrderQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanIsolatedContainerDatabase() {
        jdbc.execute("TRUNCATE TABLE order_line, order_history, customer_order, inbox, outbox");
    }

    @Test
    void concurrentSameKeyHasExactlyOneOrderAndOneOutboxEvent() throws Exception {
        int contenders = 12;
        UUID tenant = UUID.randomUUID();
        CheckoutSnapshot snapshot = basket();
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
                .when(failingOutbox).append(anyString(), eq(2), any(), anyString(), any(), isNull());
        CheckoutWriter failingWriter = new CheckoutWriter(orders, failingOutbox);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                failingWriter.create(candidate, "rollback", fingerprint(candidate.snapshot()))));
        assertEquals(0, count("order_history"));
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
        assertEquals(List.of(OrderStatus.CREATED, OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED),
                orders.history(order.id()).stream().map(OrderHistoryEntry::status).toList());
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
        OrderEventConsumer failingConsumer = new OrderEventConsumer(mapper, inbox, failingOutbox, orders, java.time.Clock.systemUTC());
        String raw = mapper.writeValueAsString(payment(order, reservationId, true));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> failingConsumer.onMessage(raw)));
        assertEquals(OrderStatus.PENDING_PAYMENT, owned(order).status());
        assertEquals(1, count("inbox"));
        assertEquals(1, count("outbox"));
        assertEquals(2, orders.history(order.id()).size());
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

    @Test
    void customerAndMerchantKeysetsRemainStableAcrossInsertionsAndHideOtherTenants() {
        UUID tenant = UUID.randomUUID();
        var customer = new commerce.runtime.Actor(tenant, "alice", java.util.Set.of("CUSTOMER"));
        var merchant = new commerce.runtime.Actor(tenant, "merchant", java.util.Set.of("MERCHANT_USER"));
        CheckoutSnapshot snapshot = OrderStateMachineTest.created().snapshot();
        Instant time = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 1; i <= 125; i++) {
            Order value = Order.create(new UUID(0, i), tenant, "alice", snapshot, time);
            writer.create(value, "page-" + i, fingerprint(snapshot));
        }
        Order hiddenOwner = writer.create(Order.create(UUID.randomUUID(), tenant, "bob", snapshot, time), "bob", fingerprint(snapshot)).order();
        Order hiddenTenant = writer.create(Order.create(UUID.randomUUID(), UUID.randomUUID(), "alice", snapshot, time), "tenant", fingerprint(snapshot)).order();
        OrderQuery query = new OrderQuery(20, OrderStatus.CREATED, time, time.plusSeconds(1), null);
        OrderPage first = queries.list(customer, false, query);
        assertEquals(20, first.items().size());
        assertEquals(new UUID(0, 125), first.items().getFirst().id());
        // Matches every filter and sorts ahead of page one: offset paging would shift and repeat a row.
        Order newer = writer.create(Order.create(new UUID(1, 0), tenant, "alice", snapshot, time), "newer", fingerprint(snapshot)).order();
        var seen = new java.util.HashSet<UUID>();
        first.items().forEach(order -> assertTrue(seen.add(order.id())));
        String cursor = first.nextCursor();
        while (cursor != null) {
            OrderPage page = queries.list(customer, false, new OrderQuery(20, OrderStatus.CREATED, time,
                    time.plusSeconds(1), OrderQuery.Cursor.decode(cursor)));
            page.items().forEach(order -> assertTrue(seen.add(order.id())));
            cursor = page.nextCursor();
        }
        assertEquals(125, seen.size());
        assertFalse(seen.contains(newer.id()));
        assertEquals(newer.id(), queries.list(customer, false, query).items().getFirst().id());
        assertFalse(seen.contains(hiddenOwner.id()));
        assertFalse(seen.contains(hiddenTenant.id()));
        assertEquals(hiddenOwner, queries.get(merchant, true, hiddenOwner.id()));
        assertEquals(404, assertThrows(ApiException.class, () -> queries.get(customer, false, hiddenOwner.id())).status());
        assertEquals(404, assertThrows(ApiException.class, () -> queries.get(merchant, true, hiddenTenant.id())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> queries.list(customer, true, query)).status());
        assertTrue(queries.list(customer, false, new OrderQuery(20, OrderStatus.CONFIRMED, null, null, null)).items().isEmpty());
        assertEquals(100, queries.list(merchant, true, new OrderQuery(100, null, null, null, null)).items().size());
        assertEquals(1, queries.history(merchant, true, hiddenOwner.id()).size());
        assertEquals(404, assertThrows(ApiException.class, () -> queries.history(customer, false, hiddenOwner.id())).status());
    }

    @Test
    void concurrentCancellationRetriesHaveOneDurableHistoryAndFact() throws Exception {
        Order order = persist();
        var actor = customer(order);
        CyclicBarrier start = new CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Order>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return lifecycle.cancel(actor, order.id());
            }));
            for (var future : futures) assertEquals(OrderStatus.CANCELLED, future.get(15, TimeUnit.SECONDS).status());
        }
        assertEquals(2, orders.history(order.id()).size());
        assertEquals(1, count("outbox"));
        assertEquals("order.cancelled", jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class));
        // A recreated instance and repository return the same committed result.
        var restarted = new OrderLifecycle(new OrderRepository(jdbc), outbox, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30));
        Order retry = new TransactionTemplate(transactionManager).execute(status -> restarted.cancel(actor, order.id()));
        assertEquals(owned(order), retry);
        consumer.onMessage(mapper.writeValueAsString(reserved(order, UUID.randomUUID())));
        consumer.onMessage(mapper.writeValueAsString(payment(order, UUID.randomUUID(), true)));
        assertEquals(OrderStatus.CANCELLED, owned(order).status());
        assertEquals(2, orders.history(order.id()).size());
    }

    @Test
    void cancellationAndPublicationRaceNeverDispatchesACancelledCheckout() throws Exception {
        for (int i = 0; i < 12; i++) {
            Order order = writer.create(OrderStateMachineTest.created(), "race", "a".repeat(64)).order();
            var sent = new java.util.concurrent.CopyOnWriteArrayList<Event>();
            var kafka = kafkaRecording(sent, false);
            var publisher = new commerce.runtime.OutboxPublisher(jdbc, kafka, transactionManager, 100, 1000);
            CyclicBarrier start = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Integer> cancel = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try { lifecycle.cancel(customer(order), order.id()); return 200; }
                    catch (ApiException error) { return error.status(); }
                });
                Future<Integer> publish = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return publisher.publishBatch(); });
                int result = cancel.get(15, TimeUnit.SECONDS);
                publish.get(15, TimeUnit.SECONDS);
                publisher.publishBatch();
                boolean checkoutSent = sent.stream().anyMatch(event -> event.payload().path("orderId").asString().equals(order.id().toString())
                        && event.eventType().equals("order.created"));
                assertEquals(result == 409, checkoutSent);
                assertEquals(result == 200 ? OrderStatus.CANCELLED : OrderStatus.CREATED, owned(order).status());
            }
        }
    }

    @Test
    void uncertainDispatchAndConcurrentReservationOrPaymentAlwaysForbidCancellation() throws Exception {
        for (boolean paymentFirst : new boolean[] {false, true}) {
            Order order = writer.create(OrderStateMachineTest.created(), "inflight", "a".repeat(64)).order();
            var publisher = new commerce.runtime.OutboxPublisher(jdbc, kafkaRecording(new ArrayList<>(), true), transactionManager, 100, 1000);
            assertThrows(IllegalStateException.class, publisher::publishBatch);
            assertEquals(OrderStatus.CREATED, owned(order).status());
            CyclicBarrier start = new CyclicBarrier(2);
            UUID reservation = UUID.randomUUID();
            Event event = paymentFirst ? payment(order, reservation, true) : reserved(order, reservation);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Integer> cancel = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return assertThrows(ApiException.class, () -> lifecycle.cancel(customer(order), order.id())).status();
                });
                Future<?> delivery = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    consumer.onMessage(mapper.writeValueAsString(event));
                    return null;
                });
                assertEquals(409, cancel.get(15, TimeUnit.SECONDS));
                delivery.get(15, TimeUnit.SECONDS);
            }
            assertNotEquals(OrderStatus.CANCELLED, owned(order).status());
        }
    }

    @Test
    void expiryUsesControlledTimeSurvivesRestartAndCompetingWorkersNeverReleaseDispatchedWork() throws Exception {
        Order order = persist();
        Instant deadline = order.createdAt().plusSeconds(1800);
        var transaction = new TransactionTemplate(transactionManager);
        var early = expiryAt(deadline.minusNanos(1));
        assertEquals(0, transaction.<Integer>execute(status -> early.expireBatch()));
        var restarted = expiryAt(deadline);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return transaction.execute(status -> restarted.expireBatch()); });
            var b = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return transaction.execute(status -> expiryAt(deadline).expireBatch()); });
            assertEquals(1, a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(OrderStatus.EXPIRED, owned(order).status());
        assertEquals(2, orders.history(order.id()).size());
        assertEquals("DISPATCH_EXPIRED", orders.history(order.id()).getLast().reason());
        assertEquals(0, transaction.<Integer>execute(status -> restarted.expireBatch()));
        assertEquals(409, assertThrows(ApiException.class, () -> lifecycle.cancel(customer(order), order.id())).status());
        Order dispatched = writer.create(OrderStateMachineTest.created(), "fenced", "b".repeat(64)).order();
        jdbc.update("UPDATE outbox SET dispatch_started_at = CURRENT_TIMESTAMP WHERE aggregate_id = ?", dispatched.id().toString());
        assertEquals(0, transaction.<Integer>execute(status -> expiryAt(deadline.plusSeconds(3600)).expireBatch()));
        assertEquals(OrderStatus.CREATED, owned(dispatched).status());
        consumer.onMessage(mapper.writeValueAsString(reserved(dispatched, UUID.randomUUID())));
        assertEquals(0, transaction.<Integer>execute(status -> expiryAt(deadline.plusSeconds(86400)).expireBatch()));
        assertEquals(OrderStatus.PENDING_PAYMENT, owned(dispatched).status());
    }

    @Test
    void expiryRacingPublicationNeverDispatchesExpiredCheckout() throws Exception {
        for (int i = 0; i < 8; i++) {
            Order order = writer.create(OrderStateMachineTest.created(), "expiry-race", "a".repeat(64)).order();
            var sent = new java.util.concurrent.CopyOnWriteArrayList<Event>();
            var publisher = new commerce.runtime.OutboxPublisher(jdbc, kafkaRecording(sent, false), transactionManager, 100, 1000);
            var transaction = new TransactionTemplate(transactionManager);
            var worker = expiryAt(order.createdAt().plusSeconds(1800));
            CyclicBarrier start = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var expire = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return transaction.execute(status -> worker.expireBatch()); });
                var publish = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return publisher.publishBatch(); });
                expire.get(15, TimeUnit.SECONDS);
                publish.get(15, TimeUnit.SECONDS);
                publisher.publishBatch();
                boolean checkoutSent = sent.stream().anyMatch(event -> event.eventType().equals("order.created")
                        && event.payload().path("orderId").asString().equals(order.id().toString()));
                assertEquals(owned(order).status() != OrderStatus.EXPIRED, checkoutSent);
            }
        }
    }

    @Test
    void anotherCustomerOrTenantCannotCancelOrDisturbACancellableOrder() {
        Order order = persist();
        var sameTenantStranger = new commerce.runtime.Actor(order.tenantId(), "mallory", java.util.Set.of("CUSTOMER"));
        var otherTenantNamesake = new commerce.runtime.Actor(UUID.randomUUID(), order.customerId(), java.util.Set.of("CUSTOMER"));
        for (var intruder : List.of(sameTenantStranger, otherTenantNamesake)) {
            ApiException hidden = assertThrows(ApiException.class, () -> lifecycle.cancel(intruder, order.id()));
            assertEquals(404, hidden.status());
            assertEquals("ORDER_NOT_FOUND", hidden.code());
        }
        assertEquals(OrderStatus.CREATED, owned(order).status());
        assertEquals(1, orders.history(order.id()).size());
        assertEquals("order.created", jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class));
        assertEquals(OrderStatus.CANCELLED, lifecycle.cancel(customer(order), order.id()).status());
    }

    @Test
    void expiryDrainsABacklogLargerThanOneBatchAcrossPollsWithTheCheckoutCorrelation() {
        CheckoutSnapshot snapshot = OrderStateMachineTest.created().snapshot();
        UUID tenant = UUID.randomUUID();
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 1; i <= 30; i++) {
            writer.create(Order.create(new UUID(0, i), tenant, "alice", snapshot, created), "stale-" + i, fingerprint(snapshot));
        }
        var checkoutCorrelations = jdbc.queryForList("SELECT aggregate_id || '=' || (payload->>'correlationId') FROM outbox ORDER BY 1", String.class);
        var transaction = new TransactionTemplate(transactionManager);
        var worker = expiryAt(created.plusSeconds(1800));
        assertEquals(25, transaction.<Integer>execute(status -> worker.expireBatch()));
        assertEquals(5, transaction.<Integer>execute(status -> worker.expireBatch()));
        assertEquals(0, transaction.<Integer>execute(status -> worker.expireBatch()));
        assertEquals(30, jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE status = 'EXPIRED'", Integer.class));
        assertEquals(30, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload->>'eventType' = 'order.expired'", Integer.class));
        assertEquals(30, count("outbox"));
        assertEquals(30, new java.util.HashSet<>(checkoutCorrelations).size());
        assertEquals(checkoutCorrelations, jdbc.queryForList("SELECT aggregate_id || '=' || (payload->>'correlationId') FROM outbox ORDER BY 1", String.class));
    }

    @Test
    void lifecycleFailureRollsBackWithdrawalHistoryStateAndAllowsRetry() {
        Order order = persist();
        Outbox failing = spy(new Outbox(jdbc, mapper));
        doThrow(new IllegalStateException("outbox unavailable")).when(failing).append(eq("order.cancelled"), any(), anyString(), any(), any());
        var broken = new OrderLifecycle(orders, failing, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .execute(status -> broken.cancel(customer(order), order.id())));
        assertEquals(OrderStatus.CREATED, owned(order).status());
        assertEquals(1, orders.history(order.id()).size());
        assertEquals("order.created", jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class));
        assertEquals(OrderStatus.CANCELLED, lifecycle.cancel(customer(order), order.id()).status());
    }

    @Test
    void rejectionAndDeclineCreateBusinessHistoryAndCausalFactsWithoutDuplicates() {
        Order rejected = persist();
        Event rejection = event(rejected, "inventory.rejected", UUID.randomUUID(), UUID.randomUUID(),
                Map.of("orderId", rejected.id(), "reason", "INSUFFICIENT_STOCK"));
        consumer.onMessage(mapper.writeValueAsString(rejection));
        consumer.onMessage(mapper.writeValueAsString(rejection));
        assertEquals(List.of("ORDER_CREATED", "INSUFFICIENT_STOCK"), orders.history(rejected.id()).stream().map(OrderHistoryEntry::reason).toList());
        assertEquals(409, assertThrows(ApiException.class, () -> lifecycle.cancel(customer(rejected), rejected.id())).status());
        Order declined = writer.create(OrderStateMachineTest.created(), "declined", "b".repeat(64)).order();
        UUID reservation = UUID.randomUUID();
        consumer.onMessage(mapper.writeValueAsString(reserved(declined, reservation)));
        Event decline = payment(declined, reservation, false);
        consumer.onMessage(mapper.writeValueAsString(decline));
        consumer.onMessage(mapper.writeValueAsString(decline));
        assertEquals(List.of("ORDER_CREATED", "STOCK_RESERVED", "PAYMENT_DECLINED"), orders.history(declined.id()).stream().map(OrderHistoryEntry::reason).toList());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload->>'eventType' = 'order.rejected'", Integer.class));
        assertEquals(409, assertThrows(ApiException.class, () -> lifecycle.cancel(customer(declined), declined.id())).status());
    }

    private OrderLifecycle expiryAt(Instant time) {
        return new OrderLifecycle(new OrderRepository(jdbc), outbox, java.time.Clock.fixed(time, java.time.ZoneOffset.UTC), java.time.Duration.ofMinutes(30));
    }

    private static commerce.runtime.Actor customer(Order order) {
        return new commerce.runtime.Actor(order.tenantId(), order.customerId(), java.util.Set.of("CUSTOMER"));
    }

    @SuppressWarnings("unchecked")
    private org.springframework.kafka.core.KafkaTemplate<Object, Object> kafkaRecording(List<Event> events, boolean fail) {
        var kafka = (org.springframework.kafka.core.KafkaTemplate<Object, Object>) mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(anyString(), any(), any())).thenAnswer(call -> {
            events.add(mapper.readValue((String) call.getArgument(2), Event.class));
            return fail ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("uncertain send"))
                    : java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        return kafka;
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

    private static CheckoutSnapshot basket() {
        return new CheckoutSnapshot(List.of(new OrderLine(UUID.randomUUID(),2,"A",2500,5000,1),
                new OrderLine(UUID.randomUUID(),3,"B",1000,3000,7)),8000,"USD","pm_approved");
    }

    @Test void basketSnapshotQueriesCausalEventsAndCancellationRemainAtomic() {
        CheckoutSnapshot snapshot = basket();
        Order order = writer.create(candidate(UUID.randomUUID(),"owner",snapshot),"basket",fingerprint(snapshot)).order();
        assertEquals(2,owned(order).snapshot().items().size());
        assertEquals(8000,owned(order).snapshot().totalMinor());
        Event created = mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id = ?",String.class,order.id().toString()),Event.class);
        assertEquals(2,created.eventVersion()); assertEquals(2,created.payload().get("items").size());
        UUID reservedId = UUID.randomUUID();
        Event reserved = new Event(reservedId,"inventory.reserved",2,Instant.now(),UUID.randomUUID().toString(),created.eventId(),order.tenantId(),created.payload());
        consumer.onMessage(mapper.writeValueAsString(payment(order,reservedId,true)));
        assertEquals(OrderStatus.CREATED,owned(order).status());
        consumer.onMessage(mapper.writeValueAsString(reserved));
        consumer.onMessage(mapper.writeValueAsString(reserved));
        consumer.onMessage(mapper.writeValueAsString(payment(order,reservedId,false)));
        assertEquals(OrderStatus.CONFIRMED,owned(order).status());
        assertEquals(3,orders.history(order.id()).size());
        assertEquals(order.snapshot(),owned(order).snapshot());
        Order cancel = writer.create(candidate(order.tenantId(),"owner",basket()),"cancel-basket","b".repeat(64)).order();
        var actor = new commerce.runtime.Actor(cancel.tenantId(),cancel.customerId(),java.util.Set.of("CUSTOMER"));
        lifecycle.cancel(actor,cancel.id());
        assertEquals(OrderStatus.CANCELLED,owned(cancel).status());
        assertEquals(2,owned(cancel).snapshot().items().size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND payload->>'eventType' = 'order.created'",Integer.class,cancel.id().toString()));
    }

    private static Order candidate(UUID tenant, String customer, CheckoutSnapshot snapshot) {
        return Order.create(UUID.randomUUID(), tenant, customer, snapshot, Instant.now());
    }

    private static String fingerprint(CheckoutSnapshot snapshot) {
        return new CheckoutRequest(snapshot.items().stream().map(i -> new CheckoutRequest.Item(i.productId(),i.quantity())).toList(), snapshot.paymentMethod()).fingerprint();
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
        @Bean OrderQueries queries(OrderRepository orders) { return new OrderQueries(orders); }
        @Bean OrderLifecycle lifecycle(OrderRepository orders, Outbox outbox) {
            return new OrderLifecycle(orders, outbox, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30));
        }
        @Bean CheckoutWriter writer(OrderRepository orders, Outbox outbox) { return new CheckoutWriter(orders, outbox); }
        @Bean OrderEventConsumer consumer(ObjectMapper mapper, Inbox inbox, Outbox outbox, OrderRepository orders) {
            return new OrderEventConsumer(mapper, inbox, outbox, orders, java.time.Clock.systemUTC());
        }
    }
}
