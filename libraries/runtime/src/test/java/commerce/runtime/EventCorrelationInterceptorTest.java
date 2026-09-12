package commerce.runtime;

import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EventCorrelationInterceptorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EventCorrelationInterceptor interceptor = new EventCorrelationInterceptor(mapper);

    @AfterEach
    void cleanup() {
        MDC.remove(Correlation.MDC_KEY);
    }

    @Test
    void propagatesEnvelopeCorrelationAndCleansAfterEachRecord() {
        String correlation = UUID.randomUUID().toString();
        Event event = new Event(UUID.randomUUID(), "order.created", 1, Instant.now(), correlation,
                null, UUID.randomUUID(), mapper.createObjectNode().put("quantity", 1));
        var record = new ConsumerRecord<Object, Object>(EventsConfig.TOPIC, 0, 0, "key", mapper.writeValueAsString(event));
        assertThat(interceptor.intercept(record, null)).isSameAs(record);
        assertThat(Correlation.current()).isEqualTo(correlation);
        interceptor.afterRecord(record, null);
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void neverSkipsMalformedRecordsAndReplacesStaleMdcBeforeProcessing() {
        var record = new ConsumerRecord<Object, Object>(EventsConfig.TOPIC, 0, 0, "key", "not-json");
        MDC.put(Correlation.MDC_KEY, "stale-value");
        assertThat(interceptor.intercept(record, null)).isSameAs(record);
        assertThat(Correlation.isUuid(Correlation.current())).isTrue();
        interceptor.clearThreadState(null);
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void tombstonesAreNotSilentlyAcknowledgedOrDroppedByTheInterceptor() {
        var record = new ConsumerRecord<Object, Object>(EventsConfig.TOPIC, 0, 0, "key", null);
        assertThat(interceptor.intercept(record, null)).isSameAs(record);
        assertThat(Correlation.isUuid(Correlation.current())).isTrue();
    }
}
