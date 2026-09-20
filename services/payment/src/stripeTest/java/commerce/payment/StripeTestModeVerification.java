package commerce.payment;

import java.util.UUID;

/** Explicit external verification. Never part of check/integrationTest or default CI. */
public final class StripeTestModeVerification {
    public static void main(String[] args) throws InterruptedException {
        String key = System.getenv("STRIPE_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("STRIPE_API_KEY is required; real Stripe verification did not run");
        var provider = new StripePaymentProvider(key);
        var request = new PaymentProvider.Request(UUID.randomUUID(), 100, "USD", "pm_card_visa", "stripe");
        try {
            var payment = provider.authorize(request);
            if (payment.outcome() != PaymentProvider.Outcome.AUTHORIZED) throw new IllegalStateException();
            if (!payment.equals(provider.authorize(request))) throw new IllegalStateException();
            var command = new PaymentProvider.RefundRequest(UUID.randomUUID(), request.orderId(), payment.providerId(), 100, "USD", "stripe");
            var refund = provider.refund(command);
            refund = provider.lookupRefund(command, refund.providerId());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (refund.outcome() == PaymentProvider.RefundOutcome.REVIEW_REQUIRED && System.nanoTime() < deadline) {
                Thread.sleep(500);
                refund = provider.lookupRefund(command, refund.providerId());
            }
            if (refund.outcome() != PaymentProvider.RefundOutcome.SUCCEEDED) throw new IllegalStateException();
            System.out.println("PASS real Stripe Test Mode payment, idempotent replay, full refund and lookup. Webhook delivery not covered by this command.");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Real Stripe Test Mode verification failed; inspect the Test Mode dashboard. Provider details suppressed.");
        }
    }
}
