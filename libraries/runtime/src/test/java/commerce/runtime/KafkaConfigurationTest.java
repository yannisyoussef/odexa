package commerce.runtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaConfigurationTest {
    @Mock private KafkaTemplate<Object, Object> template;
    @Mock private ProducerFactory<Object, Object> producerFactory;
    @Mock private Consumer<Object, Object> consumer;
    @Mock private ConsumerFactory<Object, Object> consumerFactory;
    @Mock private MessageListenerContainer container;
    @Mock private ConcurrentKafkaListenerContainerFactoryConfigurer configurer;
    @Captor private ArgumentCaptor<ProducerRecord<Object, Object>> publication;
    private final EventsConfig configuration = new EventsConfig();

    @Test
    void dltPublishFailureIsNotSuccessfulRecoveryAndPreservesPartition() {
        when(template.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties()).thenReturn(Map.of(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10));
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<Object, Object>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic failure")));
        var recoverer = configuration.deadLetterPublishingRecoverer(template, 10);
        var record = new ConsumerRecord<Object, Object>(EventsConfig.TOPIC, 2, 7, "order-id", "original-event");
        assertThatThrownBy(() -> recoverer.accept(record, new IllegalArgumentException("untrusted-value")))
                .isInstanceOf(RuntimeException.class);
        verify(template).send(publication.capture());
        assertThat(publication.getValue().topic()).isEqualTo(EventsConfig.DLT);
        assertThat(publication.getValue().partition()).isEqualTo(2);
        assertThat(publication.getValue().key()).isEqualTo(record.key());
        assertThat(publication.getValue().value()).isEqualTo(record.value());
        assertThat(publication.getValue().headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
        assertThat(publication.getValue().headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void boundedRetriesDoNotRecoverEarlyAndSanitizeTheFailure() {
        AtomicInteger recovered = new AtomicInteger();
        AtomicReference<Exception> failure = new AtomicReference<>();
        var handler = new SanitizedKafkaErrorHandler((record, exception) -> {
            recovered.incrementAndGet();
            failure.set(exception);
        }, new FixedBackOff(0, 2));
        handler.setSeekAfterError(false);
        var record = new ConsumerRecord<Object, Object>(EventsConfig.TOPIC, 0, 5, "order-id", "event");
        var exception = new IllegalArgumentException("untrusted-value");
        assertThat(handler.handleOne(exception, record, consumer, container)).isFalse();
        assertThat(handler.handleOne(exception, record, consumer, container)).isFalse();
        assertThat(recovered).hasValue(0);
        assertThat(handler.handleOne(exception, record, consumer, container)).isTrue();
        assertThat(recovered).hasValue(1);
        assertThat(failure.get().getMessage()).isEqualTo("Event processing failed");
        assertThat(failure.get().getCause()).isNull();
    }

    @Test
    void failedRecoveryNeverReturnsHandled() {
        var handler = new SanitizedKafkaErrorHandler((record, exception) -> {
            throw new IllegalStateException("DLT unavailable");
        }, new FixedBackOff(0, 0));
        handler.setSeekAfterError(false);
        assertThat(handler.handleOne(new IllegalArgumentException("untrusted-value"),
                new ConsumerRecord<>(EventsConfig.TOPIC, 0, 0, "order-id", "event"), consumer, container)).isFalse();
    }

    @Test
    void topicsAndFactoryUseMatchingPartitionsAndRecordAcknowledgements() {
        assertThat(configuration.commerceEventsTopic(3, 1).numPartitions()).isEqualTo(3);
        assertThat(configuration.commerceEventsDeadLetterTopic(3, 1).numPartitions()).isEqualTo(3);
        var handler = configuration.kafkaErrorHandler(configuration.deadLetterPublishingRecoverer(template, 50), 1, 3);
        var factory = configuration.kafkaListenerContainerFactory(configurer, consumerFactory, handler, JsonMapper.builder().build());
        verify(configurer).configure(factory, consumerFactory);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(handler.isAckAfterHandle()).isTrue();
        assertThatThrownBy(() -> configuration.kafkaErrorHandler(configuration.deadLetterPublishingRecoverer(template, 50), 1, 11))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
