package commerce.payment;

import java.util.UUID;

/** Provider-neutral money commands and read-only reconciliation. */
public interface PaymentProvider {
    Result authorize(Request request) throws UncertainOutcome;
    default Result lookup(Request request, String providerId) { throw new UncertainOutcome(); }
    default RefundResult refund(RefundRequest request) { throw new UncertainOutcome(); }
    default RefundResult lookupRefund(RefundRequest request, String providerId) { throw new UncertainOutcome(); }

    record Request(UUID orderId, long amountMinor, String currency, String paymentMethod, String provider) {
        public Request(UUID orderId, long amountMinor, String currency, String paymentMethod) {
            this(orderId, amountMinor, currency, paymentMethod, "simulator");
        }
    }
    record RefundRequest(UUID refundId, UUID orderId, String paymentProviderId, long amountMinor,
                         String currency, String provider) { }
    record Result(String providerId, Outcome outcome) {
        public Result { validate(providerId, outcome); }
    }
    record RefundResult(String providerId, RefundOutcome outcome) {
        public RefundResult { validate(providerId, outcome); }
    }
    private static void validate(String id, Object outcome) {
        if (id == null || id.isBlank() || id.length() > 200 || outcome == null) {
            throw new IllegalArgumentException("Invalid provider result");
        }
    }
    enum Outcome { AUTHORIZED, DECLINED, REVIEW_REQUIRED }
    enum RefundOutcome { SUCCEEDED, FAILED, REVIEW_REQUIRED }

    /** Transport/protocol failure is never proof of failure. Contains no provider data. */
    final class UncertainOutcome extends RuntimeException {
        public UncertainOutcome() { super("Payment provider outcome is not known"); }
    }
}
