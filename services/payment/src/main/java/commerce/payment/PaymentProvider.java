package commerce.payment;

import java.util.UUID;

/** Business port: no HTTP or provider-specific types leak into the worker. */
public interface PaymentProvider {
    Result authorize(Request request) throws UncertainOutcome;

    record Request(UUID orderId, long amountMinor, String currency, String paymentMethod) { }
    record Result(String providerId, Outcome outcome) {
        public Result {
            if (providerId == null || providerId.isBlank() || providerId.length() > 200 || outcome == null) {
                throw new IllegalArgumentException("Invalid provider result");
            }
        }
    }
    enum Outcome { AUTHORIZED, DECLINED }

    /** A transport/protocol failure is not evidence of a decline. Never include provider response data. */
    final class UncertainOutcome extends RuntimeException {
        public UncertainOutcome() { super("Payment provider outcome is not known"); }
    }
}
