package commerce.payment;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RefundWorker {
    private final RefundStore store;
    private final PaymentProvider provider;
    public RefundWorker(RefundStore store, PaymentProvider provider) { this.store = store; this.provider = provider; }
    @Scheduled(fixedDelayString = "${payment.worker.poll-ms:500}")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void runOnce() {
        try { store.claim().ifPresent(this::process); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(RefundWorker.class).warn("Refund progress unavailable; lease recovery will retry");
        }
    }
    private void process(RefundStore.Claim claim) {
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", claim.correlationId());
        try {
            PaymentProvider.RefundResult result;
            try {
                result = claim.reconciling() ? provider.lookupRefund(claim.request(), claim.providerId())
                        : provider.refund(claim.request());
                if (result == null) throw new PaymentProvider.UncertainOutcome();
            } catch (RuntimeException unknown) { store.uncertain(claim); return; }
            store.complete(claim, result);
        } finally {
            if (previous == null) MDC.remove("correlationId"); else MDC.put("correlationId", previous);
        }
    }
}
