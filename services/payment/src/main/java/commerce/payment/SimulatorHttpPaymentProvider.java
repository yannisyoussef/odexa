package commerce.payment;

import commerce.runtime.Correlation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class SimulatorHttpPaymentProvider implements PaymentProvider {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private final HttpClient client;
    private final Duration responseTimeout;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;

    @org.springframework.beans.factory.annotation.Autowired
    public SimulatorHttpPaymentProvider(ObjectMapper mapper,
            @Value("${payment.simulator-url}") String baseUrl,
            @Value("${payment.provider-api-key}") String apiKey) {
        this(mapper, baseUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    SimulatorHttpPaymentProvider(ObjectMapper mapper, String baseUrl, String apiKey, HttpClient client) {
        this(mapper, baseUrl, apiKey, client, Duration.ofSeconds(5));
    }

    SimulatorHttpPaymentProvider(ObjectMapper mapper, String baseUrl, String apiKey, HttpClient client,
            Duration responseTimeout) {
        if (responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("Provider timeout must be positive");
        }
        this.responseTimeout = responseTimeout;
        URI base = URI.create(baseUrl);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Invalid simulator endpoint configuration");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 512
                || apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("PROVIDER_API_KEY must be a nonempty printable secret");
        }
        this.endpoint = base.resolve("/provider/v1/payments");
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public Result authorize(Request request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(responseTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(Correlation.HEADER, Correlation.current())
                    .header("X-Provider-Key", apiKey)
                    .header("Idempotency-Key", request.orderId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            HttpResponse<byte[]> response = sendWithDeadline(httpRequest);
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new UncertainOutcome();
            }
            ProviderResponse body = mapper.readValue(response.body(), ProviderResponse.class);
            return new Result(body.id().toString(), Outcome.valueOf(body.status()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UncertainOutcome();
        } catch (IOException | RuntimeException exception) {
            // Deliberately discard response bodies, request headers and underlying exception messages.
            throw new UncertainOutcome();
        }
    }

    private HttpResponse<byte[]> sendWithDeadline(HttpRequest request) throws IOException, InterruptedException {
        var subscriber = new LimitedBodySubscriber();
        long timeoutNanos = responseTimeout.toNanos();
        // On JDK 25, HttpRequest.timeout does not bound a stalled body after headers.
        // Use one deadline starting before dispatch, including the entire body subscription.
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        boolean completed = false;
        try {
            future = client.sendAsync(request, ignored -> subscriber);
            var response = future.get(Math.max(0, timeoutNanos - (System.nanoTime() - started)),
                    TimeUnit.NANOSECONDS);
            completed = true;
            return response;
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("Provider response deadline exceeded");
        } catch (ExecutionException exception) {
            // Do not retain provider data or transport exception messages.
            throw new IOException("Provider request failed");
        } finally {
            if (!completed) {
                if (future != null) {
                    future.cancel(true);
                }
                subscriber.cancel(new IOException("Provider request cancelled"));
            }
        }
    }

    /** Bounds bytes before allocation and permits deadline cancellation, even before onSubscribe. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - output.size()) {
                    cancel(new IOException("Provider response limit exceeded"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public synchronized void onComplete() {
            if (!result.isDone()) {
                result.complete(output.toByteArray());
            }
        }

        synchronized void cancel(Throwable error) {
            result.completeExceptionally(error);
            if (subscription != null) {
                Flow.Subscription current = subscription;
                subscription = null;
                current.cancel();
            }
        }
    }

    private record ProviderResponse(java.util.UUID id, String status) { }
}
