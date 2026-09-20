package commerce.payment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Dispatch uses the persisted provider, never the current default for new payments. */
@Component
@Primary
public final class RoutingPaymentProvider implements PaymentProvider {
    private final SimulatorHttpPaymentProvider simulator;
    private final ObjectProvider<StripePaymentProvider> stripe;
    public RoutingPaymentProvider(SimulatorHttpPaymentProvider simulator, ObjectProvider<StripePaymentProvider> stripe,
            @org.springframework.beans.factory.annotation.Value("${payment.provider:simulator}") String selected) {
        this.simulator = simulator;
        this.stripe = stripe;
        if ("stripe".equals(selected) && stripe.getIfAvailable() == null) {
            throw new IllegalArgumentException("Stripe provider selection requires STRIPE_ENABLED=true");
        }
    }
    private PaymentProvider adapter(String name) {
        if ("simulator".equals(name)) return simulator;
        if ("stripe".equals(name)) {
            var configured = stripe.getIfAvailable();
            if (configured != null) return configured;
        }
        throw new UncertainOutcome();
    }
    public Result authorize(Request request) { return adapter(request.provider()).authorize(request); }
    public Result lookup(Request request, String id) { return adapter(request.provider()).lookup(request, id); }
    public RefundResult refund(RefundRequest request) { return adapter(request.provider()).refund(request); }
    public RefundResult lookupRefund(RefundRequest request, String id) {
        return adapter(request.provider()).lookupRefund(request, id);
    }
}
