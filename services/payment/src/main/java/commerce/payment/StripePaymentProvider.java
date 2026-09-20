package commerce.payment;

import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stripe types and provider status vocabulary are confined to this adapter. */
@Component
@ConditionalOnProperty(name = "payment.stripe.enabled", havingValue = "true")
public final class StripePaymentProvider implements PaymentProvider {
    private final StripeClient client;

    public StripePaymentProvider(@Value("${payment.stripe.api-key}") String key) {
        this(client(key, "https://api.stripe.com"));
    }
    StripePaymentProvider(StripeClient client) { this.client = client; }

    static StripeClient client(String key, String base) {
        if (key == null || !key.matches("(?:rk|sk)_test_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Stripe requires a Test Mode API key");
        }
        return StripeClient.builder().setApiKey(key).setApiBase(base).setMaxNetworkRetries(0)
                .setConnectTimeout(2000).setReadTimeout(5000).setHttpClient(new StripeTransport()).build();
    }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).setMaxNetworkRetries(0).build();
    }
    @Override public Result authorize(Request request) {
        try {
            var params = PaymentIntentCreateParams.builder().setAmount(request.amountMinor())
                    .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                    .setPaymentMethod(request.paymentMethod()).setConfirm(true)
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER).build())
                    .putMetadata("odexa_order_id", request.orderId().toString()).build();
            return payment(client.v1().paymentIntents().create(params, options(request.orderId().toString())), request);
        } catch (CardException failure) {
            // A reusable failed intent is not a terminal decline. Seal it before releasing stock.
            try {
                PaymentIntent intent = failure.getStripeError().getPaymentIntent();
                payment(intent, request); // Validate linkage before cancellation.
                return payment(client.v1().paymentIntents().cancel(intent.getId(),
                        options(request.orderId() + ":cancel")), request);
            } catch (StripeException | RuntimeException unknown) { throw new UncertainOutcome(); }
        } catch (StripeException | RuntimeException failure) { throw new UncertainOutcome(); }
    }
    @Override public Result lookup(Request request, String id) {
        try {
            if (id != null) {
                var intent = client.v1().paymentIntents().retrieve(id);
                if (!id.equals(intent.getId())) throw new UncertainOutcome();
                return payment(intent, request);
            }
            // Recovery from a lost create response. No match is uncertainty, never permission to re-charge.
            var found = client.v1().paymentIntents().search(PaymentIntentSearchParams.builder()
                    .setQuery("metadata['odexa_order_id']:'" + request.orderId() + "'").setLimit(2L).build());
            if (found.getData().size() != 1 || Boolean.TRUE.equals(found.getHasMore())) throw new UncertainOutcome();
            return payment(found.getData().getFirst(), request);
        } catch (StripeException | RuntimeException failure) { throw new UncertainOutcome(); }
    }
    private Result payment(PaymentIntent intent, Request request) {
        if (intent == null || !Boolean.FALSE.equals(intent.getLivemode())
                || !Objects.equals(intent.getAmount(), request.amountMinor())
                || !request.currency().equalsIgnoreCase(intent.getCurrency())
                || intent.getMetadata() == null
                || !request.orderId().toString().equals(intent.getMetadata().get("odexa_order_id"))) {
            throw new UncertainOutcome();
        }
        Outcome outcome = switch (intent.getStatus()) {
            case "succeeded" -> Outcome.AUTHORIZED;
            case "canceled" -> Outcome.DECLINED;
            default -> Outcome.REVIEW_REQUIRED;
        };
        if (outcome == Outcome.AUTHORIZED && !Objects.equals(intent.getAmountReceived(), request.amountMinor())) {
            throw new UncertainOutcome();
        }
        return new Result(intent.getId(), outcome);
    }
    @Override public RefundResult refund(RefundRequest request) {
        try {
            return refund(client.v1().refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(request.paymentProviderId()).setAmount(request.amountMinor())
                    .putMetadata("odexa_refund_id", request.refundId().toString()).build(),
                    options(request.refundId().toString())), request);
        } catch (StripeException | RuntimeException failure) { throw new UncertainOutcome(); }
    }
    @Override public RefundResult lookupRefund(RefundRequest request, String id) {
        try {
            if (id != null) {
                var refund = client.v1().refunds().retrieve(id);
                if (!id.equals(refund.getId())) throw new UncertainOutcome();
                return refund(refund, request);
            }
            var found = client.v1().refunds().list(RefundListParams.builder()
                    .setPaymentIntent(request.paymentProviderId()).setLimit(10L).build());
            var matching = found.getData().stream().filter(r -> r.getMetadata() != null
                    && request.refundId().toString().equals(r.getMetadata().get("odexa_refund_id"))).toList();
            if (matching.size() != 1 || Boolean.TRUE.equals(found.getHasMore())) throw new UncertainOutcome();
            return refund(matching.getFirst(), request);
        } catch (StripeException | RuntimeException failure) { throw new UncertainOutcome(); }
    }
    private RefundResult refund(Refund refund, RefundRequest request) {
        if (refund == null || !Objects.equals(refund.getAmount(), request.amountMinor())
                || !request.currency().equalsIgnoreCase(refund.getCurrency())
                || !request.paymentProviderId().equals(refund.getPaymentIntent())
                || refund.getMetadata() == null
                || !request.refundId().toString().equals(refund.getMetadata().get("odexa_refund_id"))) {
            throw new UncertainOutcome();
        }
        return new RefundResult(refund.getId(), switch (refund.getStatus()) {
            case "succeeded" -> RefundOutcome.SUCCEEDED;
            case "failed", "canceled" -> RefundOutcome.FAILED;
            default -> RefundOutcome.REVIEW_REQUIRED;
        });
    }
}
