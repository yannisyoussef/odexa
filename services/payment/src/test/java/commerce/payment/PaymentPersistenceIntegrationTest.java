package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;

import commerce.runtime.ApiException;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import commerce.runtime.Outbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(PaymentPersistenceIntegrationTest.Config.class)
class PaymentPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired PaymentStore store;
    @Autowired RefundStore refunds;
    @Autowired ProviderEvents events;
    @Autowired ReservationConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach void clean() { jdbc.execute("TRUNCATE refund, provider_event, payment, inbox, outbox"); }

    @Test void concurrentDuplicateDeliveryCreatesExactlyOneInboxAndDurableJob() throws Exception {
        String raw = raw(UUID.randomUUID(), UUID.randomUUID(), 2500);
        concurrently(24, () -> { consumer.receive(raw); return true; });
        assertEquals(1, count("payment"));
        assertEquals(1, count("inbox"));
        assertEquals(0, count("outbox"));
    }

    @Test void conflictingPayloadRollsBackInboxAlongWithJobMutation() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        assertThrows(IllegalArgumentException.class,
                () -> consumer.receive(raw(UUID.randomUUID(), order, 3500)));
        assertEquals(1, count("payment"));
        assertEquals(1, count("inbox"));
        assertEquals(2500L, store.get(tenant, "customer-a", order).amountMinor());
    }

    @Test void independentDatabaseConnectionsCannotClaimTheSameLiveLease() throws Exception {
        consumer.receive(raw(UUID.randomUUID(), UUID.randomUUID(), 2500));
        var claims = concurrently(24, () -> store.claim().isPresent());
        assertEquals(1, claims.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM payment", Integer.class));
    }

    @Test void expiredLeaseIsReclaimedAndStaleSuccessOrFailureCannotOverwriteWinner() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        var old = store.claim().orElseThrow();
        expireLease();
        var current = store.claim().orElseThrow();
        assertEquals(old.request(), current.request());
        assertNotEquals(old.leaseToken(), current.leaseToken());
        assertFalse(store.complete(old, result(PaymentProvider.Outcome.DECLINED)));
        store.uncertain(old);
        assertTrue(store.complete(current, result(PaymentProvider.Outcome.AUTHORIZED)));
        assertFalse(store.complete(current, result(PaymentProvider.Outcome.AUTHORIZED)));
        assertEquals("AUTHORIZED", store.get(tenant, "customer-a", order).status());
        assertEquals(1, count("outbox"));
        assertEquals("payment.authorized", jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class));
        assertEquals(order.toString(), jdbc.queryForObject("SELECT aggregate_id FROM outbox", String.class));
        assertEquals(current.reservationEventId().toString(),
                jdbc.queryForObject("SELECT payload->>'causationId' FROM outbox", String.class));
    }

    @Test void resultAndOutboxRollbackTogetherAndRetryCanFinish() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        var claim = store.claim().orElseThrow();
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(store.complete(claim, result(PaymentProvider.Outcome.AUTHORIZED)));
            throw new IllegalStateException("Force transaction rollback");
        }));
        assertEquals("PENDING", store.get(tenant, "customer-a", order).status());
        assertEquals(0, count("outbox"));
        assertTrue(store.complete(claim, result(PaymentProvider.Outcome.DECLINED)));
        assertEquals("DECLINED", store.get(tenant, "customer-a", order).status());
        assertEquals("payment.declined", jdbc.queryForObject("SELECT payload->>'eventType' FROM outbox", String.class));
    }

    @Test void unknownOutcomeRetriesWithDelayThenRequiresReviewWithoutReleasingStock() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = store.claim().orElseThrow();
            assertEquals(attempt, claim.attempts());
            assertEquals(order, claim.request().orderId());
            store.uncertain(claim);
            assertTrue(store.claim().isEmpty());
            if (attempt < 3) {
                assertEquals("PENDING", store.get(tenant, "customer-a", order).status());
                makeDue();
            }
        }
        assertEquals("REVIEW_REQUIRED", store.get(tenant, "customer-a", order).status());
        assertEquals(0, count("outbox")); // No decline/release event for any uncertain result.
    }

    @Test void processDeathOnFinalAttemptBecomesReviewAfterLeaseExpiry() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(attempt, store.claim().orElseThrow().attempts());
            expireLease();
        }
        assertTrue(store.claim().isEmpty());
        assertEquals("REVIEW_REQUIRED", store.get(tenant, "customer-a", order).status());
        assertEquals(0, count("outbox"));
    }

    @Test void actualWorkerCallsProviderOutsideTransactionAndRestoresCorrelation() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        PaymentProvider provider = request -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(order, request.orderId());
            return result(PaymentProvider.Outcome.AUTHORIZED);
        };
        new PaymentWorker(store, provider).runOnce();
        assertEquals("AUTHORIZED", store.get(tenant, "customer-a", order).status());
        assertEquals(1, count("outbox"));
    }

    @Test void lookupHidesOtherTenantAndOtherOwner() {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        assertNotNull(store.get(tenant, "customer-a", order));
        assertThrows(ApiException.class, () -> store.get(UUID.randomUUID(), "customer-a", order));
        assertThrows(ApiException.class, () -> store.get(tenant, "customer-other-a", order));
    }

    @Test void reviewIsDurableAndAuthoritativeLookupConvergesExactlyOnce() {
        for (var outcome : java.util.List.of(PaymentProvider.Outcome.AUTHORIZED, PaymentProvider.Outcome.DECLINED)) {
            clean();
            UUID order = UUID.randomUUID();
            consumer.receive(raw(UUID.randomUUID(), order, 2500));
            var first = store.claim().orElseThrow();
            store.complete(first, result(PaymentProvider.Outcome.REVIEW_REQUIRED));
            makeDue();
            var review = store.claim().orElseThrow();
            assertTrue(review.reconciling());
            assertFalse(store.complete(first, result(PaymentProvider.Outcome.DECLINED)));
            assertFalse(store.complete(review, result(outcome)), "A changed provider reference is not valid evidence");
            var authority = new PaymentProvider.Result(review.providerId(), outcome);
            assertTrue(store.complete(review, authority));
            assertFalse(store.complete(review, authority));
            assertEquals(outcome.name(), store.get(tenant, "customer-a", order).status());
            assertEquals(1, count("outbox"));
        }
    }

    @Test void oldUnknownCommandNeverRepeatsAuthorizationAfterIdempotencyWindow() {
        consumer.receive(raw(UUID.randomUUID(), UUID.randomUUID(), 2500));
        jdbc.execute("UPDATE payment SET created_at = CURRENT_TIMESTAMP - INTERVAL '24 hours'");
        assertTrue(store.claim().isEmpty());
        makeDue();
        assertTrue(store.claim().orElseThrow().reconciling());
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM payment", Integer.class));
    }

    @Test void concurrentWebhookReplaySchedulesLookupOnceWithoutTrustingSnapshot() throws Exception {
        UUID order = UUID.randomUUID();
        consumer.receive(raw(UUID.randomUUID(), order, 2500));
        var claim = store.claim().orElseThrow();
        store.complete(claim, new PaymentProvider.Result("provider_reference", PaymentProvider.Outcome.REVIEW_REQUIRED));
        concurrently(24, () -> { events.accept("simulator", "event1", "payment.updated", "provider_reference"); return true; });
        assertEquals(1, count("provider_event"));
        assertEquals("REVIEW_REQUIRED", store.get(tenant, "customer-a", order).status());
        assertTrue(store.claim().orElseThrow().reconciling());
        assertEquals(0, count("outbox"));
        events.accept("stripe", "event1", "payment.updated", "provider_reference");
        assertEquals(2, count("provider_event")); // Namespaces must not collide.
    }

    @Test void fullRefundConcurrentReplayConflictAndOwnership() throws Exception {
        UUID order = authorized();
        var merchant = merchant();
        var results = concurrently(20, () -> refunds.create(merchant, order, "same-key", 2500, "USD"));
        assertEquals(1, results.stream().filter(RefundStore.Created::initial).count());
        assertEquals(1, count("refund"));
        UUID id = results.getFirst().view().id();
        assertEquals(409, assertThrows(ApiException.class, () -> refunds.create(merchant, order, "same-key", 2501, "USD")).status());
        assertEquals(409, assertThrows(ApiException.class, () -> refunds.create(merchant, order, "other-key", 2500, "USD")).status());
        var customer = new commerce.runtime.Actor(tenant, "customer-a", java.util.Set.of("CUSTOMER"));
        assertEquals(id, refunds.get(customer, order, id, false).id());
        assertEquals(403, assertThrows(ApiException.class, () -> refunds.create(customer, order, "x", 2500, "USD")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> refunds.get(
                new commerce.runtime.Actor(UUID.randomUUID(), "merchant", java.util.Set.of("MERCHANT_ADMIN")), order, id, true)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> refunds.get(
                new commerce.runtime.Actor(tenant, "other-owner", java.util.Set.of("CUSTOMER")), order, id, false)).status());
    }

    @Test void refundsCannotStartAgainstUncertainPaymentOrForPartialAmount() {
        UUID order = UUID.randomUUID(); consumer.receive(raw(UUID.randomUUID(), order, 2500));
        assertEquals(409, assertThrows(ApiException.class, () -> refunds.create(merchant(), order, "k", 2500, "USD")).status());
        store.complete(store.claim().orElseThrow(), result(PaymentProvider.Outcome.AUTHORIZED));
        assertEquals(409, assertThrows(ApiException.class, () -> refunds.create(merchant(), order, "k", 2499, "USD")).status());
        assertEquals(0, count("refund"));
    }

    @Test void refundUncertaintyLeaseRecoveryAndOutboxRollback() {
        UUID order = authorized();
        var created = refunds.create(merchant(), order, "refund", 2500, "USD");
        var stale = refunds.claim().orElseThrow();
        jdbc.execute("UPDATE refund SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var current = refunds.claim().orElseThrow();
        assertEquals(stale.request(), current.request());
        assertFalse(refunds.complete(stale, new PaymentProvider.RefundResult("refund_reference", PaymentProvider.RefundOutcome.FAILED)));
        refunds.complete(current, new PaymentProvider.RefundResult("refund_reference", PaymentProvider.RefundOutcome.REVIEW_REQUIRED));
        events.accept("simulator", "refund-event", "refund.updated", "refund_reference");
        var reconcile = refunds.claim().orElseThrow();
        assertTrue(reconcile.reconciling());
        var result = new PaymentProvider.RefundResult("refund_reference", PaymentProvider.RefundOutcome.SUCCEEDED);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            refunds.complete(reconcile, result); throw new IllegalStateException("rollback");
        }));
        assertEquals("REVIEW_REQUIRED", refunds.get(merchant(), order, created.view().id(), true).status());
        assertTrue(refunds.complete(reconcile, result));
        assertFalse(refunds.complete(reconcile, result));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE payload->>'eventType' = 'payment.refunded'", Integer.class));
        assertEquals("AUTHORIZED", store.get(tenant, "customer-a", order).status());
        assertEquals(409, assertThrows(ApiException.class, () -> refunds.create(merchant(), order, "again", 2500, "USD")).status());
    }

    @Test void onlyDefinitiveRefundFailureAllowsAnotherCommand() {
        UUID order = authorized();
        refunds.create(merchant(), order, "failed", 2500, "USD");
        var first = refunds.claim().orElseThrow();
        refunds.complete(first, new PaymentProvider.RefundResult("failed_refund", PaymentProvider.RefundOutcome.FAILED));
        assertTrue(refunds.create(merchant(), order, "new", 2500, "USD").initial());
        assertEquals(2, count("refund"));
        assertEquals(2, refunds.list(merchant(), order, true, null).items().size());
    }

    private UUID authorized() {
        UUID order = UUID.randomUUID(); consumer.receive(raw(UUID.randomUUID(), order, 2500));
        store.complete(store.claim().orElseThrow(), result(PaymentProvider.Outcome.AUTHORIZED));
        return order;
    }
    private commerce.runtime.Actor merchant() {
        return new commerce.runtime.Actor(tenant, "merchant", java.util.Set.of("MERCHANT_ADMIN"));
    }

    private void expireLease() {
        jdbc.execute("UPDATE payment SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        makeDue();
    }
    private void makeDue() {
        jdbc.execute("UPDATE payment SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    private PaymentProvider.Result result(PaymentProvider.Outcome outcome) {
        return new PaymentProvider.Result(UUID.randomUUID().toString(), outcome);
    }
    private String raw(UUID eventId, UUID orderId, long amount) {
        return mapper.writeValueAsString(new Event(eventId, "inventory.reserved", 1, Instant.now(),
                UUID.randomUUID().toString(), UUID.randomUUID(), tenant,
                mapper.valueToTree(Map.of("orderId", orderId, "customerId", "customer-a",
                        "productId", UUID.randomUUID(), "quantity", 1, "totalMinor", amount,
                        "currency", "USD", "paymentMethod", "pm_approved"))));
    }
    private <T> java.util.List<T> concurrently(int count, Callable<T> task) throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(task));
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean Outbox outbox(JdbcTemplate jdbc, ObjectMapper mapper) { return new Outbox(jdbc, mapper); }
        @Bean Inbox inbox(JdbcTemplate jdbc) { return new Inbox(jdbc); }
        @Bean RefundStore refunds(JdbcTemplate jdbc, Outbox outbox, RetryPolicy policy) { return new RefundStore(jdbc, outbox, policy); }
        @Bean ProviderEvents events(JdbcTemplate jdbc) { return new ProviderEvents(jdbc); }
        @Bean RetryPolicy retryPolicy() { return new RetryPolicy(3, 10, 1, 4); }
        @Bean PaymentStore store(JdbcTemplate jdbc, Outbox outbox, RetryPolicy policy) { return new PaymentStore(jdbc, outbox, policy); }
        @Bean ReservationConsumer consumer(ObjectMapper mapper, Inbox inbox, PaymentStore store) {
            return new ReservationConsumer(mapper, inbox, store);
        }
    }
}
