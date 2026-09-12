package commerce.runtime;

import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/** Framework retry/recovery logging must not receive parser snippets or provider exception text. */
final class SanitizedKafkaErrorHandler extends DefaultErrorHandler {
    SanitizedKafkaErrorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        super(recoverer, backOff);
    }

    @Override
    public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
            Consumer<?, ?> consumer, MessageListenerContainer container) {
        super.handleRemaining(sanitized(), records, consumer, container);
    }

    @Override
    public boolean handleOne(Exception exception, ConsumerRecord<?, ?> record,
            Consumer<?, ?> consumer, MessageListenerContainer container) {
        return super.handleOne(sanitized(), record, consumer, container);
    }

    @Override
    public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
            MessageListenerContainer container, boolean batchListener) {
        super.handleOtherException(sanitized(), consumer, container, batchListener);
    }

    private static IllegalStateException sanitized() {
        // Deliberately omit the cause, which can contain an entire event or rejected value.
        return new IllegalStateException("Event processing failed");
    }
}
