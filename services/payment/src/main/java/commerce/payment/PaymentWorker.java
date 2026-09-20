package commerce.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorker.class);
    private final PaymentStore store;
    private final PaymentProvider provider;

    public PaymentWorker(PaymentStore store, PaymentProvider provider) {
        this.store = store;
        this.provider = provider;
    }

    @Scheduled(fixedDelayString = "${payment.worker.poll-ms:500}")
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void runOnce() {
        try {
            store.claim().ifPresent(this::process);
        } catch (RuntimeException exception) {
            // Lease expiry recovers DB/outbox failures; no provider payload or exception is logged.
            LOG.warn("Payment worker could not persist progress; lease recovery will retry");
        }
    }

    private void process(PaymentStore.Claim claim) {
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", claim.correlationId());
        try {
            PaymentProvider.Result result;
            try {
                // Deliberately outside all store transactions. Idempotency is always the order UUID.
                result = claim.reconciling() ? provider.lookup(claim.request(), claim.providerId())
                        : provider.authorize(claim.request());
                if (result == null) throw new PaymentProvider.UncertainOutcome();
            } catch (RuntimeException exception) {
                store.uncertain(claim);
                return;
            }
            store.complete(claim, result);
        } finally {
            if (previous == null) MDC.remove("correlationId");
            else MDC.put("correlationId", previous);
        }
    }
}
