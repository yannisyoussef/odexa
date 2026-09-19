package commerce.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "order.lifecycle.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OrderLifecycleWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleWorker.class);
    private final OrderLifecycle lifecycle;
    public OrderLifecycleWorker(OrderLifecycle lifecycle) { this.lifecycle = lifecycle; }

    @Scheduled(fixedDelayString = "${order.lifecycle.poll-delay-ms:30000}")
    public void poll() {
        try {
            lifecycle.expireBatch();
        } catch (RuntimeException failure) {
            LOG.warn("Order expiry batch rolled back; will retry; exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
