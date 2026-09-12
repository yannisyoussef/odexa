package commerce.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProxyController {
    private static final List<String> REQUEST_HEADERS = List.of("Authorization", "Content-Type", "Accept",
            "Idempotency-Key", "If-Match", "If-None-Match");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "ETag", "Retry-After", "WWW-Authenticate");
    private final Routes routes;
    private final int maxRequest;
    private final int maxResponse;
    private final Duration upstreamTimeout;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    ProxyController(Routes routes, @Value("${gateway.max-request-bytes}") int maxRequest,
                    @Value("${gateway.max-response-bytes}") int maxResponse) {
        this(routes, maxRequest, maxResponse, Duration.ofSeconds(10));
    }

    ProxyController(Routes routes, int maxRequest, int maxResponse, Duration upstreamTimeout) {
        if (upstreamTimeout.isZero() || upstreamTimeout.isNegative()) {
            throw new IllegalArgumentException("Upstream timeout must be positive");
        }
        this.routes = routes;
        this.maxRequest = maxRequest;
        this.maxResponse = maxResponse;
        this.upstreamTimeout = upstreamTimeout;
    }

    @RequestMapping("/api/v1/**")
    void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        URI origin = routes.destination(request.getMethod(), request.getRequestURI());
        if (origin == null) {
            EdgeFilter.problem(response, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxRequest + 1);
        if (body.length > maxRequest) {
            EdgeFilter.problem(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds edge limit");
            return;
        }
        try {
            String query = request.getQueryString();
            URI target = URI.create(origin + request.getRequestURI() + (query == null ? "" : "?" + query));
            var builder = HttpRequest.newBuilder(target).timeout(upstreamTimeout)
                    .method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            for (String name : REQUEST_HEADERS) {
                String value = request.getHeader(name);
                if (value != null) {
                    builder.header(name, value);
                }
            }
            builder.header(EdgeFilter.CORRELATION, (String) request.getAttribute(EdgeFilter.CORRELATION));
            // Host, Forwarded, X-Forwarded-*, cookies and hop-by-hop headers are deliberately not copied.
            HttpResponse<byte[]> upstream = sendWithDeadline(builder.build());
            byte[] output = upstream.body();
            response.setStatus(upstream.statusCode());
            for (String name : RESPONSE_HEADERS) {
                upstream.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
            }
            upstream.headers().firstValue("Location").ifPresent(value -> {
                String location = safeLocation(origin, value);
                if (location != null) {
                    response.setHeader("Location", location);
                }
            });
            response.getOutputStream().write(output);
        } catch (HttpTimeoutException ex) {
            EdgeFilter.problem(response, 504, "UPSTREAM_TIMEOUT", "Upstream service timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            EdgeFilter.problem(response, 503, "UPSTREAM_UNAVAILABLE", "Upstream service unavailable");
        } catch (IllegalArgumentException ex) {
            EdgeFilter.problem(response, 400, "INVALID_REQUEST", "Request is not valid");
        } catch (IOException ex) {
            if (response.isCommitted()) {
                throw ex;
            }
            EdgeFilter.problem(response, 502, "UPSTREAM_UNAVAILABLE", "Upstream service unavailable");
        }
    }

    private HttpResponse<byte[]> sendWithDeadline(HttpRequest request) throws IOException, InterruptedException {
        // HttpRequest.timeout does not cover a stalled response body on JDK 25.
        // Start one deadline before dispatch, not a fresh timeout after receiving headers.
        var subscriber = new LimitedBodySubscriber(maxResponse);
        long timeoutNanos = upstreamTimeout.toNanos();
        long started = System.nanoTime();
        var future = client.sendAsync(request, ignored -> subscriber);
        boolean completed = false;
        try {
            var upstream = future.get(Math.max(0, timeoutNanos - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            completed = true;
            return upstream;
        } catch (TimeoutException ex) {
            throw new HttpTimeoutException("Upstream response deadline exceeded");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException("Upstream request failed", ex.getCause());
        } finally {
            if (!completed) {
                future.cancel(true);
                subscriber.cancel(new IOException("Upstream request cancelled"));
            }
        }
    }

    private String safeLocation(URI origin, String value) {
        try {
            URI location = origin.resolve(value);
            if (!origin.getScheme().equals(location.getScheme()) || !origin.getRawAuthority().equals(location.getRawAuthority())
                    || location.getRawFragment() != null || location.getRawQuery() != null
                    || routes.destination("GET", location.getRawPath()) == null) {
                return null;
            }
            return location.getRawPath();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
