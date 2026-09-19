package commerce.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GatewayTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private HttpServer server;
    private MockMvc mvc;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> bearer = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicReference<String> forwarded = new AtomicReference<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            correlation.set(exchange.getRequestHeaders().getFirst(EdgeFilter.CORRELATION));
            forwarded.set(exchange.getRequestHeaders().getFirst("Forwarded"));
            byte[] output = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("ETag", "\"3\"");
            exchange.getResponseHeaders().add("Location", base + "/api/v1/orders/" + ID);
            exchange.sendResponseHeaders(201, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
        server.start();
        Routes routes = new Routes(base, base, base, base);
        mvc = MockMvcBuilders.standaloneSetup(new ProxyController(routes, 32, 1024))
                .addFilters(new EdgeFilter(routes, 100, 200, 32)).build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void preservesCredentialsCorrelationAndConcurrencyWithoutTrustingForwardingHeaders() throws Exception {
        mvc.perform(post("/api/v1/orders").content("{}")
                        .header("Authorization", "Bearer local-fixture-not-a-token")
                        .header(EdgeFilter.CORRELATION, ID)
                        .header("Forwarded", "host=evil.example;for=1.2.3.4"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().string("Location", "/api/v1/orders/" + ID))
                .andExpect(header().string(EdgeFilter.CORRELATION, ID));
        assertEquals("Bearer local-fixture-not-a-token", bearer.get());
        assertEquals(ID, correlation.get());
        assertNull(forwarded.get());
    }

    @Test
    void routesEveryNewOrderOperationAndRejectsUnlistedLifecycleCommands() throws Exception {
        for (String path : List.of("/api/v1/orders", "/api/v1/orders/" + ID + "/history",
                "/api/v1/merchant/orders", "/api/v1/merchant/orders/" + ID,
                "/api/v1/merchant/orders/" + ID + "/history")) {
            mvc.perform(get(path)).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/orders/" + ID + "/cancel")).andExpect(status().isCreated());
        assertEquals(6, calls.get());
        for (String path : List.of("/api/v1/orders/" + ID + "/refund", "/api/v1/merchant/orders/" + ID + "/cancel")) {
            mvc.perform(post(path)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/merchant/orders")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + ID + "/cancel")).andExpect(status().isNotFound());
        assertEquals(6, calls.get());
    }

    @Test
    void rejectsAdministrativeAndUnlistedRoutesWithoutContactingBackend() throws Exception {
        for (String path : List.of("/actuator/env", "/actuator", "/api/v1/provider/payments", "/api/v1/products/garbage")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(delete("/api/v1/products/" + ID)).andExpect(status().isNotFound());
        assertEquals(0, calls.get());
    }

    @Test
    void boundsBodiesAndRejectsEncodedRequestsAndInvalidCorrelation() throws Exception {
        mvc.perform(post("/api/v1/orders").content("x".repeat(33))).andExpect(status().is(413));
        mvc.perform(post("/api/v1/orders").header("Content-Encoding", "gzip")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/v1/products").header(EdgeFilter.CORRELATION, "not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"));
        assertEquals(0, calls.get());
    }

    @Test
    void unknownLengthBodiesAreStillBoundedBeforeForwarding() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/orders") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[33]);
        request.setAttribute(EdgeFilter.CORRELATION, ID);
        var response = new MockHttpServletResponse();
        new ProxyController(new Routes(base, base, base, base), 32, 1024).proxy(request, response);
        assertEquals(413, response.getStatus());
        assertEquals(0, calls.get());
    }

    @Test
    void refusesToFollowOrExposeArbitraryRedirects() throws Exception {
        server.createContext("/api/v1/products", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://untrusted.invalid/collect");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isFound()).andExpect(header().doesNotExist("Location"));
        assertEquals(1, calls.get());
    }

    @Test
    void timesOutWhenUpstreamSendsHeadersButStallsBody() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/api/v1/products", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("ETag", "\"partial\"");
                exchange.sendResponseHeaders(200, 64);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                try {
                    releaseBody.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Duration deadline = Duration.ofSeconds(1);
        Routes routes = new Routes(base, base, base, base);
        MockMvc boundedMvc = MockMvcBuilders.standaloneSetup(new ProxyController(routes, 32, 1024, deadline))
                .addFilters(new EdgeFilter(routes, 100, 200, 32)).build();
        long started = System.nanoTime();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    boundedMvc.perform(get("/api/v1/products"))
                            .andExpect(status().isGatewayTimeout())
                            .andExpect(content().contentType("application/problem+json"))
                            .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"))
                            .andExpect(header().doesNotExist("ETag")));
            assertEquals(0, headersSent.getCount(), "Upstream must have flushed headers and a partial body");
            assertTrue(System.nanoTime() - started < deadline.plusSeconds(2).toNanos(),
                    "The response body must finish or time out within the deadline (with scheduling tolerance)");
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void oversizedUpstreamBodyRemainsBadGatewayWithAsyncClient() throws Exception {
        server.createContext("/api/v1/products", exchange -> {
            try (exchange) {
                byte[] output = new byte[1025];
                exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
            }
        });
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void bucketIsBoundedAndRefillsUsingMonotonicTime() {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(2, 2, clock::get);
        assertTrue(bucket.take());
        assertTrue(bucket.take());
        assertFalse(bucket.take());
        clock.addAndGet(500_000_000);
        assertTrue(bucket.take());
        assertFalse(bucket.take());
    }

    @Test
    void routesRejectTraversalAndDestinationOverrides() {
        Routes routes = new Routes(base, base, base, base);
        for (String path : List.of("//evil.example", "/api/v1/products/../actuator/env", "/api/v1/products%2f..%2factuator",
                "/api/v1/products;host=evil", "/api/v1/products/" + ID + "/extra")) {
            assertNull(routes.destination("GET", path));
        }
        assertThrows(IllegalArgumentException.class, () -> Routes.origin("http://localhost:8081/path"));
        assertThrows(IllegalArgumentException.class, () -> Routes.origin("http://user@localhost:8081"));
    }

    @Test
    void responseSubscriberCancelsOnDeadlineAndIgnoresLateBodySignals() {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { requests.incrementAndGet(); }
            public void cancel() { cancellations.incrementAndGet(); }
        });
        var timeout = new HttpTimeoutException("Response deadline exceeded");
        subscriber.cancel(timeout);
        subscriber.cancel(timeout);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        subscriber.onComplete();
        assertEquals(1, cancellations.get());
        assertEquals(1, requests.get(), "Cancelled subscribers must not request more body data");
        var failure = assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertSame(timeout, failure.getCause());
    }

    @Test
    void responseSubscriberStillCancelsAfterFutureDeliversAnError() {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        AtomicInteger cancellations = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancellations.incrementAndGet(); }
        });
        var timeout = new HttpTimeoutException("Response deadline exceeded");
        subscriber.onError(timeout);
        subscriber.cancel(timeout);
        subscriber.cancel(timeout);
        assertEquals(1, cancellations.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void responseSubscriberCancelsSubscriptionArrivingAfterDeadline() {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        var timeout = new HttpTimeoutException("Response deadline exceeded");
        subscriber.cancel(timeout);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { requests.incrementAndGet(); }
            public void cancel() { cancellations.incrementAndGet(); }
        });
        assertEquals(1, cancellations.get());
        assertEquals(0, requests.get());
        var failure = assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertSame(timeout, failure.getCause());
    }

    @Test
    void responseSubscriberCancelsBeforeExceedingBound() {
        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(3);
        AtomicInteger cancellations = new AtomicInteger();
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancellations.incrementAndGet(); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        assertEquals(1, cancellations.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
