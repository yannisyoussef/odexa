package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import commerce.runtime.Correlation;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class SimulatorHttpPaymentProviderTest {
    private final HttpClient http = mock(HttpClient.class);
    private final String key = UUID.randomUUID().toString();
    private final SimulatorHttpPaymentProvider adapter = new SimulatorHttpPaymentProvider(
            JsonMapper.builder().build(), "http://localhost:8085", key, http);
    private final PaymentProvider.Request request = new PaymentProvider.Request(UUID.randomUUID(), 2500,
            "USD", "pm_approved");

    @Test void timeoutRemainsUncertainAndEveryRetryUsesOrderUuidAndCurrentCorrelation() {
        String correlation = UUID.randomUUID().toString();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest sent = invocation.getArgument(0);
                    assertEquals(request.orderId().toString(), sent.headers().firstValue("Idempotency-Key").orElseThrow());
                    assertTrue(key.equals(sent.headers().firstValue("X-Provider-Key").orElseThrow()));
                    assertEquals(correlation, sent.headers().firstValue(Correlation.HEADER).orElseThrow());
                    assertEquals(Duration.ofSeconds(5), sent.timeout().orElseThrow());
                    assertEquals("/provider/v1/payments", sent.uri().getPath());
                    return CompletableFuture.failedFuture(new HttpTimeoutException("sandbox timeout"));
                });
        try (var ignored = MDC.putCloseable(Correlation.MDC_KEY, correlation)) {
            assertUncertain(adapter);
            assertUncertain(adapter);
        }
        verify(http, times(2)).sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test void backgroundRequestGetsAValidCorrelationWithoutChangingMdc() {
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest sent = invocation.getArgument(0);
                    String correlation = sent.headers().firstValue(Correlation.HEADER).orElseThrow();
                    assertEquals(UUID.fromString(correlation).toString(), correlation);
                    return CompletableFuture.failedFuture(new IOException("sandbox disconnected"));
                });
        String previous = MDC.get(Correlation.MDC_KEY);
        MDC.remove(Correlation.MDC_KEY);
        try {
            assertUncertain(adapter);
            assertNull(MDC.get(Correlation.MDC_KEY));
        } finally {
            if (previous != null) {
                MDC.put(Correlation.MDC_KEY, previous);
            }
        }
    }

    @Test void onlyExplicitProviderDeclineIsADecline() {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        UUID id = UUID.randomUUID();
        when(response.body()).thenReturn(providerBody(id, PaymentProvider.Outcome.DECLINED));
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        assertEquals(new PaymentProvider.Result(id.toString(), PaymentProvider.Outcome.DECLINED), adapter.authorize(request));
        when(response.statusCode()).thenReturn(409);
        assertUncertain(adapter);
        when(response.statusCode()).thenReturn(503);
        assertUncertain(adapter);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
        assertUncertain(adapter);
    }

    @Test void deadlineCancelsTheExchangeAndSubscribedBody() {
        var future = new CompletableFuture<HttpResponse<byte[]>>();
        var subscription = mock(Flow.Subscription.class);
        var body = new AtomicReference<HttpResponse.BodySubscriber<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    body.set(handler.apply(mock(HttpResponse.ResponseInfo.class)));
                    body.get().onSubscribe(subscription);
                    return future;
                });
        var shortDeadline = new SimulatorHttpPaymentProvider(JsonMapper.builder().build(),
                "http://localhost:8085", key, http, Duration.ofMillis(30));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertUncertain(shortDeadline));
        assertTrue(future.isCancelled());
        verify(subscription).cancel();
        body.get().onComplete();
        assertTrue(body.get().getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void interruptionIsPreservedAndLateBodySubscriptionIsCancelled() {
        var future = new CompletableFuture<HttpResponse<byte[]>>();
        var body = new AtomicReference<HttpResponse.BodySubscriber<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    body.set(handler.apply(mock(HttpResponse.ResponseInfo.class)));
                    Thread.currentThread().interrupt();
                    return future;
                });
        try {
            assertUncertain(adapter);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(future.isCancelled());
        var subscription = mock(Flow.Subscription.class);
        body.get().onSubscribe(subscription);
        verify(subscription).cancel();
        verify(subscription, never()).request(anyLong());
        assertTrue(body.get().getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void subscriberRejectsOversizeBeforeCopyingAndCountsAcrossDeliveries() {
        for (int accepted : new int[] {0, 16 * 1024}) {
            var subscriber = new SimulatorHttpPaymentProvider.LimitedBodySubscriber();
            var subscription = mock(Flow.Subscription.class);
            subscriber.onSubscribe(subscription);
            if (accepted > 0) {
                subscriber.onNext(List.of(ByteBuffer.allocate(8192), ByteBuffer.allocate(8192)));
            }
            var overflow = ByteBuffer.allocate(16 * 1024 - accepted + 1);
            subscriber.onNext(List.of(overflow));
            assertEquals(0, overflow.position());
            verify(subscription).cancel();
            subscriber.onComplete();
            assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        }
    }

    @Test void realServerStallingAfterHeadersOrBodyBytesRemainsUncertain() throws Exception {
        for (boolean sendBody : new boolean[] {false, true}) {
            var headersSent = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var server = new LocalProviderServer(exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 0);
                    if (sendBody) {
                        exchange.getResponseBody().write(providerBody(UUID.randomUUID(), PaymentProvider.Outcome.DECLINED));
                    }
                    exchange.getResponseBody().flush();
                    headersSent.countDown();
                    awaitRelease(release);
                }
            })) {
                try {
                    var provider = server.provider(Duration.ofMillis(750));
                    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertUncertain(provider));
                    assertEquals(0L, headersSent.getCount(), "Must exercise a body stall, not a connect timeout");
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test void realServerAcceptsExactly16KiB() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] body = paddedBody(id, PaymentProvider.Outcome.AUTHORIZED, 16 * 1024);
        try (var server = new LocalProviderServer(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
        })) {
            assertEquals(new PaymentProvider.Result(id.toString(), PaymentProvider.Outcome.AUTHORIZED),
                    server.provider(Duration.ofSeconds(5)).authorize(request));
        }
    }

    @Test void realServerOversizedFixedAndChunkedResponsesNeverBecomeDeclines() throws Exception {
        byte[] body = paddedBody(UUID.randomUUID(), PaymentProvider.Outcome.DECLINED, 16 * 1024 + 1);
        for (boolean chunked : new boolean[] {false, true}) {
            try (var server = new LocalProviderServer(exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
                    exchange.getResponseBody().write(body);
                }
            })) {
                var provider = server.provider(Duration.ofSeconds(5));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertUncertain(provider));
            }
        }
    }

    @Test void realServerOversizedStreamIsRejectedWithoutWaitingForEndOfBody() throws Exception {
        byte[] body = paddedBody(UUID.randomUUID(), PaymentProvider.Outcome.DECLINED, 16 * 1024 + 1);
        var release = new CountDownLatch(1);
        try (var server = new LocalProviderServer(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
                awaitRelease(release);
            }
        })) {
            try {
                var provider = server.provider(Duration.ofSeconds(5));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertUncertain(provider));
            } finally {
                release.countDown();
            }
        }
    }

    @Test void realServerTruncatedResponseIsUncertainEvenWithCompleteDeclineJson() throws Exception {
        byte[] body = providerBody(UUID.randomUUID(), PaymentProvider.Outcome.DECLINED);
        try (var server = new LocalProviderServer(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, body.length + 100);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
            }
        })) {
            var provider = server.provider(Duration.ofSeconds(5));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertUncertain(provider));
        }
    }

    @Test void nonpositiveDeadlineFailsStartup() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThrows(IllegalArgumentException.class, () -> new SimulatorHttpPaymentProvider(
                    JsonMapper.builder().build(), "http://localhost:8085", key, http, timeout));
        }
    }

    private void assertUncertain(SimulatorHttpPaymentProvider provider) {
        var error = assertThrows(PaymentProvider.UncertainOutcome.class, () -> provider.authorize(request));
        assertEquals("Payment provider outcome is not known", error.getMessage());
        assertNull(error.getCause());
    }

    private static byte[] providerBody(UUID id, PaymentProvider.Outcome outcome) {
        return ("{\"id\":\"" + id + "\",\"status\":\"" + outcome + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] paddedBody(UUID id, PaymentProvider.Outcome outcome, int length) {
        String json = new String(providerBody(id, outcome), StandardCharsets.UTF_8);
        return (json + " ".repeat(length - json.length())).getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Test response was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Test server interrupted");
        }
    }

    private final class LocalProviderServer implements AutoCloseable {
        private final HttpServer server;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        LocalProviderServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/provider/v1/payments", handler);
            server.start();
        }

        SimulatorHttpPaymentProvider provider(Duration timeout) {
            return new SimulatorHttpPaymentProvider(JsonMapper.builder().build(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), key, client, timeout);
        }

        @Override
        public void close() {
            server.stop(0);
            client.shutdownNow();
            client.close();
        }
    }

    @Test void blankProviderSecretFailsStartup() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatorHttpPaymentProvider(
                JsonMapper.builder().build(), "http://localhost:8085", "", http));
    }
}
