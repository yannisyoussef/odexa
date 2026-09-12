package commerce.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KafkaRuntimeIntegrationTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final EventsConfig CONFIGURATION = new EventsConfig();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static DefaultKafkaProducerFactory<Object, Object> producers;
    private static KafkaTemplate<Object, Object> kafka;

    @BeforeAll
    static void initializeBroker() throws Exception {
        try (var admin = AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(CONFIGURATION.commerceEventsTopic(3, 1),
                    CONFIGURATION.commerceEventsDeadLetterTopic(3, 1))).all().get(30, TimeUnit.SECONDS);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        producers = new DefaultKafkaProducerFactory<>(properties);
        kafka = new KafkaTemplate<>(producers);
        kafka.setProducerListener(CONFIGURATION.kafkaProducerListener());
    }

    @AfterAll
    static void closeProducer() {
        if (producers != null) {
            producers.destroy();
        }
    }

    @Test
    void retriesTransientFailureThenCommitsAfterSuccessfulProcessingAndPropagatesCorrelation() throws Exception {
        String key = UUID.randomUUID().toString();
        String group = "retry-test-" + UUID.randomUUID();
        Event event = sample();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch processed = new CountDownLatch(1);
        List<String> correlations = new ArrayList<>();
        var listener = listener(group, record -> {
            if (!key.equals(record.key())) {
                return;
            }
            correlations.add(Correlation.current());
            MAPPER.readValue((String) record.value(), Event.class);
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("untrusted-value");
            }
            processed.countDown();
        });
        listener.start();
        try {
            var sent = kafka.send(new ProducerRecord<Object, Object>(EventsConfig.TOPIC, 0, key,
                    MAPPER.writeValueAsString(event))).get(15, TimeUnit.SECONDS);
            assertThat(processed.await(30, TimeUnit.SECONDS)).isTrue();
            listener.stop(); // Stop drains and commits processed RECORD offsets.
            assertThat(attempts).hasValue(3);
            assertThat(correlations).containsExactly(event.correlationId(), event.correlationId(), event.correlationId());
            assertCommitted(group, new TopicPartition(EventsConfig.TOPIC, 0), sent.getRecordMetadata().offset() + 1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void unsupportedVersionIsRetriedThenPublishedToSameDltPartitionBeforeSourceCommit() throws Exception {
        assertMalformedReachesDlt("unsupported-version", json -> json.put("eventVersion", 99));
    }

    @Test
    void unknownEventTypeIsNotSilentlyAcknowledged() throws Exception {
        assertMalformedReachesDlt("unknown-type", json -> json.put("eventType", "unknown.event"));
    }

    private void assertMalformedReachesDlt(String label, java.util.function.Consumer<ObjectNode> corrupt) throws Exception {
        String key = UUID.randomUUID().toString();
        String group = label + "-" + UUID.randomUUID();
        ObjectNode json = MAPPER.valueToTree(sample());
        corrupt.accept(json);
        String raw = MAPPER.writeValueAsString(json);
        AtomicInteger attempts = new AtomicInteger();
        var listener = listener(group, record -> {
            if (key.equals(record.key())) {
                attempts.incrementAndGet();
                MAPPER.readValue((String) record.value(), Event.class);
                throw new AssertionError("Malformed envelope was accepted");
            }
        });
        try (var dlt = new KafkaConsumer<Object, Object>(consumerProperties("dlt-test-" + UUID.randomUUID()))) {
            dlt.subscribe(List.of(EventsConfig.DLT));
            listener.start();
            var sent = kafka.send(new ProducerRecord<Object, Object>(EventsConfig.TOPIC, 2, key, raw)).get(15, TimeUnit.SECONDS);
            ConsumerRecord<Object, Object> deadLetter = awaitRecord(dlt, key);
            assertThat(deadLetter.partition()).isEqualTo(2);
            assertThat(deadLetter.value()).isEqualTo(raw);
            assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
            assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
            assertThat(attempts).hasValue(4); // Initial attempt plus three bounded retries.
            listener.stop();
            assertCommitted(group, new TopicPartition(EventsConfig.TOPIC, 2), sent.getRecordMetadata().offset() + 1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void committedOutboxPublishesRealEnvelopeAndInboxDeduplicatesItsRedelivery() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("runtime-schema.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        var transaction = new TransactionTemplate(manager);
        var outbox = new Outbox(jdbc, MAPPER);
        String aggregate = UUID.randomUUID().toString();
        String correlation = UUID.randomUUID().toString();
        UUID tenant = UUID.randomUUID();
        UUID causation = UUID.randomUUID();
        MDC.put(Correlation.MDC_KEY, correlation);
        try {
            transaction.executeWithoutResult(status -> outbox.append("inventory.reserved", tenant, aggregate,
                    Map.of("orderId", aggregate, "quantity", 1), causation));
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
        var publisher = new OutboxPublisher(jdbc, kafka, manager, 25, 10_000);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        try (var consumer = new KafkaConsumer<Object, Object>(consumerProperties("outbox-test-" + UUID.randomUUID()))) {
            consumer.subscribe(List.of(EventsConfig.TOPIC));
            ConsumerRecord<Object, Object> record = awaitRecord(consumer, aggregate);
            Event event = MAPPER.readValue((String) record.value(), Event.class);
            assertThat(event.eventType()).isEqualTo("inventory.reserved");
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.causationId()).isEqualTo(causation);
            assertThat(event.correlationId()).isEqualTo(correlation);
            Boolean published = jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE event_id = ?",
                    Boolean.class, event.eventId());
            assertThat(published).isTrue();
            var inbox = new Inbox(jdbc);
            boolean first = transaction.execute(status -> inbox.first(event.eventId(), "delivery-test"));
            boolean duplicate = transaction.execute(status -> inbox.first(event.eventId(), "delivery-test"));
            assertThat(first).isTrue();
            assertThat(duplicate).isFalse();
        }
        assertThat(publisher.publishBatch()).isZero();
    }

    private static KafkaMessageListenerContainer<Object, Object> listener(String group, MessageListener<Object, Object> callback) {
        var properties = new ContainerProperties(EventsConfig.TOPIC);
        properties.setGroupId(group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(callback);
        properties.setPollTimeout(100);
        var container = new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<Object, Object>(consumerProperties(group)), properties);
        container.setCommonErrorHandler(CONFIGURATION.kafkaErrorHandler(
                CONFIGURATION.deadLetterPublishingRecoverer(kafka, 5000), 10, 3));
        container.setRecordInterceptor(new EventCorrelationInterceptor(MAPPER));
        return container;
    }

    private static Map<String, Object> consumerProperties(String group) {
        return Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    }

    private static ConsumerRecord<Object, Object> awaitRecord(KafkaConsumer<Object, Object> consumer, String key) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Expected Kafka record did not arrive within 30 seconds");
    }

    private static void assertCommitted(String group, TopicPartition partition, long minimumOffset) throws Exception {
        try (var admin = AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            assertThat(offsets).containsKey(partition);
            assertThat(offsets.get(partition).offset()).isGreaterThanOrEqualTo(minimumOffset);
        }
    }

    private static Event sample() {
        return new Event(UUID.randomUUID(), "order.created", 1, Instant.now(), UUID.randomUUID().toString(),
                null, UUID.randomUUID(), MAPPER.createObjectNode().put("quantity", 1));
    }
}
