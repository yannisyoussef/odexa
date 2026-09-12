package commerce.runtime;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "runtime.events.enabled", havingValue = "true", matchIfMissing = true)
@EnableKafka
@EnableScheduling
public class EventsConfig {
    public static final String TOPIC = "commerce.events.v1";
    public static final String DLT = "commerce.events.v1.DLT";
    private static final Logger LOG = LoggerFactory.getLogger(EventsConfig.class);

    @Bean
    NewTopic commerceEventsTopic(@Value("${runtime.events.partitions:3}") int partitions,
            @Value("${runtime.events.replication-factor:1}") int replicas) {
        return TopicBuilder.name(TOPIC).partitions(positive(partitions, 1000))
                .replicas(positive(replicas, 100)).build();
    }

    @Bean
    NewTopic commerceEventsDeadLetterTopic(@Value("${runtime.events.partitions:3}") int partitions,
            @Value("${runtime.events.replication-factor:1}") int replicas) {
        return TopicBuilder.name(DLT).partitions(positive(partitions, 1000))
                .replicas(positive(replicas, 100)).build();
    }

    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {
        return new ProducerListener<>() {
            @Override
            public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata, Exception exception) {
                LOG.error("Kafka publish failed; topic={} exceptionType={}",
                        record.topic(), exception.getClass().getSimpleName());
            }
        };
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<Object, Object> template,
            @Value("${runtime.events.publish-timeout-ms:5000}") long timeoutMillis) {
        boundedTimeout(timeoutMillis);
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(DLT, record.partition()));
        // Do not fall back to a random partition if the corresponding DLT partition is missing.
        recoverer.setVerifyPartition(false);
        recoverer.setFailIfSendResultIsError(true);
        // Spring also considers the producer's finite delivery.timeout.ms, plus this buffer.
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(timeoutMillis));
        recoverer.setTimeoutBuffer(0);
        recoverer.setAppendOriginalHeaders(false);
        recoverer.setStripPreviousExceptionHeaders(true);
        recoverer.setLogRecoveryRecord(false);
        recoverer.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_CAUSE);
        return recoverer;
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer,
            @Value("${runtime.events.retry-interval-ms:1000}") long intervalMillis,
            @Value("${runtime.events.retry-attempts:3}") long retries) {
        if (intervalMillis < 1 || intervalMillis > 30_000 || retries < 0 || retries > 10) {
            throw new IllegalArgumentException("Kafka retry configuration is outside safe bounds");
        }
        var handler = new SanitizedKafkaErrorHandler(recoverer, new FixedBackOff(intervalMillis, retries));
        // A failed DLT send must propagate: the source offset is never acknowledged as recovered.
        handler.setAckAfterHandle(true);
        handler.setResetStateOnRecoveryFailure(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, DefaultErrorHandler kafkaErrorHandler,
            ObjectMapper mapper) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setRecordInterceptor(new EventCorrelationInterceptor(mapper));
        return factory;
    }

    static long boundedTimeout(long millis) {
        if (millis < 1 || millis > 30_000) {
            throw new IllegalArgumentException("Kafka publish timeout is outside safe bounds");
        }
        return millis;
    }

    private static int positive(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("Kafka topic configuration is outside safe bounds");
        }
        return value;
    }
}
