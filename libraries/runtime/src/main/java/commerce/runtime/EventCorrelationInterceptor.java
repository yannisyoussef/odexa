package commerce.runtime;

import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class EventCorrelationInterceptor implements RecordInterceptor<Object, Object> {
    private final ObjectMapper mapper;

    EventCorrelationInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> consumer) {
        MDC.remove(Correlation.MDC_KEY);
        String correlationId = UUID.randomUUID().toString();
        if (record.value() instanceof String raw) {
            try {
                JsonNode value = mapper.readTree(raw).get("correlationId");
                if (value != null && value.isString() && Correlation.isUuid(value.asString())) {
                    correlationId = value.asString();
                }
            } catch (RuntimeException exception) {
                // The listener parses Event and throws into the retry/DLT path. Never skip a record.
            }
        }
        MDC.put(Correlation.MDC_KEY, correlationId);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(Correlation.MDC_KEY);
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        MDC.remove(Correlation.MDC_KEY);
    }
}
