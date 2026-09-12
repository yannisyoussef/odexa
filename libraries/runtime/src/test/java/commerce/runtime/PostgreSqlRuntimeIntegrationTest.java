package commerce.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(MockitoExtension.class)
class PostgreSqlRuntimeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate transaction;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    @Mock private KafkaTemplate<Object, Object> kafka;
    private Outbox outbox;
    private Inbox inbox;

    @BeforeAll
    static void initializeDatabase() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var schema = new ResourceDatabasePopulator(new ClassPathResource("runtime-schema.sql"));
        schema.execute(dataSource);
        schema.execute(dataSource); // Immutable initialization is safe on subsequent application starts.
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE test_mutation (id uuid PRIMARY KEY)");
    }

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.update("DELETE FROM inbox");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM test_mutation");
        outbox = new Outbox(jdbc, MAPPER);
        inbox = new Inbox(jdbc);
    }

    @Test
    void refusesAutocommitAndReadOnlyTransactions() {
        assertThatThrownBy(() -> inbox.first(UUID.randomUUID(), "consumer")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.append("order.created", TENANT, "order", Map.of("quantity", 1), null))
                .isInstanceOf(IllegalStateException.class);
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> inbox.first(UUID.randomUUID(), "consumer")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("inbox")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void businessMutationInboxAndOutboxCommitAtomically() {
        UUID eventId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            assertThat(inbox.first(eventId, "inventory")).isTrue();
            jdbc.update("INSERT INTO test_mutation (id) VALUES (?)", eventId);
            outbox.append("inventory.reserved", TENANT, "order", Map.of("quantity", 1), eventId);
        });
        assertThat(count("test_mutation")).isEqualTo(1);
        assertThat(count("inbox")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        String raw = jdbc.queryForObject("SELECT payload::text FROM outbox", String.class);
        Event event = MAPPER.readValue(raw, Event.class);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.causationId()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo("inventory.reserved");
        assertThat(Correlation.isUuid(event.correlationId())).isTrue();
    }

    @Test
    void listenerFailureRollsBackInboxAndDomainAndAllowsRedelivery() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(inbox.first(eventId, "inventory")).isTrue();
            jdbc.update("INSERT INTO test_mutation (id) VALUES (?)", eventId);
            outbox.append("inventory.reserved", TENANT, "order", Map.of("quantity", 1), eventId);
            throw new IllegalStateException("synthetic business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("test_mutation")).isZero();
        assertThat(count("inbox")).isZero();
        assertThat(count("outbox")).isZero();
        boolean first = transaction.execute(status -> inbox.first(eventId, "inventory"));
        assertThat(first).isTrue();
    }

    @Test
    void duplicateDoesNotAbortPostgresTransactionAndConsumerIdentityIsPartOfTheKey() {
        UUID eventId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            assertThat(inbox.first(eventId, "inventory")).isTrue();
            assertThat(inbox.first(eventId, "inventory")).isFalse();
            assertThat(inbox.first(eventId, "order")).isTrue();
            jdbc.update("INSERT INTO test_mutation (id) VALUES (?)", eventId);
        });
        assertThat(count("inbox")).isEqualTo(2);
        assertThat(count("test_mutation")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveriesHaveExactlyOneWinner() throws Exception {
        UUID eventId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start");
                    }
                    return transaction.execute(status -> inbox.first(eventId, "inventory"));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(count("inbox")).isEqualTo(1);
    }

    @Test
    void publishesOnlyTheOldestPendingEventPerAggregateAndUsesItsKey() {
        append("same-order", "order.created");
        append("same-order", "order.confirmed");
        when(kafka.send(eq(EventsConfig.TOPIC), any(), any())).thenReturn(acknowledged());
        var publisher = publisher(25, 1000);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(published()).isEqualTo(1);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(published()).isEqualTo(2);
        assertThat(publisher.publishBatch()).isZero();
        verify(kafka, times(2)).send(eq(EventsConfig.TOPIC), eq("same-order"), any());
    }

    @Test
    void failedOrTimedOutBrokerAcknowledgmentLeavesTheRowPending() {
        append("order", "order.created");
        when(kafka.send(eq(EventsConfig.TOPIC), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic broker failure")))
                .thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> publisher(1, 50).publishBatch()).isInstanceOf(IllegalStateException.class);
        assertThat(published()).isZero();
        assertThatThrownBy(() -> publisher(1, 50).publishBatch()).isInstanceOf(IllegalStateException.class);
        assertThat(published()).isZero();
    }

    @Test
    void partialBatchFailureRollsBackMarkersSoEarlierAcknowledgedRecordsAreSafelyRedelivered() {
        append("first-order", "order.created");
        append("second-order", "order.created");
        List<String> delivered = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        when(kafka.send(eq(EventsConfig.TOPIC), any(), any())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(2);
            if ("second-order".equals(invocation.getArgument(1)) && fail.getAndSet(false)) {
                return CompletableFuture.failedFuture(new IllegalStateException("synthetic broker failure"));
            }
            delivered.add(MAPPER.readValue(raw, Event.class).eventId().toString());
            return acknowledged();
        });
        assertThatThrownBy(() -> publisher(25, 1000).publishBatch()).isInstanceOf(IllegalStateException.class);
        assertThat(published()).isZero();
        assertThat(publisher(25, 1000).publishBatch()).isEqualTo(2);
        assertThat(published()).isEqualTo(2);
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(0)).isEqualTo(delivered.get(1));
    }

    @Test
    void competingPublisherSkipsLockedClaimsAndDoesNotOvertakeTheSameAggregate() throws Exception {
        append("same-order", "order.created");
        append("same-order", "order.confirmed");
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<SendResult<Object, Object>> acknowledgment = new CompletableFuture<>();
        when(kafka.send(eq(EventsConfig.TOPIC), any(), any())).thenAnswer(invocation -> {
            claimed.countDown();
            return acknowledgment;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = executor.submit(() -> publisher(1, 15_000).publishBatch());
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(publisher(1, 1000).publishBatch()).isZero();
            } finally {
                acknowledgment.complete(null);
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(published()).isEqualTo(1);
    }

    @Test
    void competingPublisherCanPublishAnotherAggregateWithoutWaitingForTheLockedOne() throws Exception {
        append("first-order", "order.created");
        append("other-order", "order.created");
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<SendResult<Object, Object>> acknowledgment = new CompletableFuture<>();
        when(kafka.send(eq(EventsConfig.TOPIC), any(), any())).thenAnswer(invocation -> {
            if ("first-order".equals(invocation.getArgument(1))) {
                claimed.countDown();
                return acknowledgment;
            }
            return acknowledged();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = executor.submit(() -> publisher(1, 15_000).publishBatch());
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(publisher(1, 1000).publishBatch()).isEqualTo(1);
            } finally {
                acknowledgment.complete(null);
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(published()).isEqualTo(2);
    }

    private void append(String aggregate, String type) {
        transaction.executeWithoutResult(status -> outbox.append(type, TENANT, aggregate, Map.of("quantity", 1), null));
    }

    private OutboxPublisher publisher(int batchSize, long timeoutMillis) {
        return new OutboxPublisher(jdbc, kafka, manager, batchSize, timeoutMillis);
    }

    private static CompletableFuture<SendResult<Object, Object>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    private static long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static long published() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Long.class);
    }
}
