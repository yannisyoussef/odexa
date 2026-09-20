package commerce.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** One bounded body/deadline per SDK call; SDK retries are disabled by the adapter. */
final class StripeTransport extends com.stripe.net.HttpClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @Override public StripeResponse request(StripeRequest request) throws ApiConnectionException {
        var subscriber = new SimulatorHttpPaymentProvider.LimitedBodySubscriber();
        java.util.concurrent.CompletableFuture<?> future = null;
        try {
            var body = request.content() == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.content().byteArrayContent());
            var builder = HttpRequest.newBuilder(request.url().toURI()).timeout(Duration.ofSeconds(5))
                    .method(request.method().name(), body);
            request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            var responseFuture = client.sendAsync(builder.build(), ignored -> subscriber);
            future = responseFuture;
            var response = responseFuture.get(5, TimeUnit.SECONDS);
            return new StripeResponse(response.statusCode(), HttpHeaders.of(response.headers().map()),
                    new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("Provider request interrupted");
        } catch (Exception error) {
            throw new ApiConnectionException("Provider request unavailable");
        } finally {
            if (future != null) future.cancel(true);
            subscriber.cancel(new java.io.IOException("Provider request finished"));
        }
    }
}
