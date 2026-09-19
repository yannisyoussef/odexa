package commerce.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class EdgeFilter extends OncePerRequestFilter {
    static final String CORRELATION = "X-Correlation-ID";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final Routes routes;
    private final TokenBucket bucket;
    private final TokenBucket webhooks = new TokenBucket(100, 200, System::nanoTime);
    private final int maxRequest;

    EdgeFilter(Routes routes, @Value("${gateway.requests-per-second}") int rate,
               @Value("${gateway.burst}") int burst, @Value("${gateway.max-request-bytes}") int maxRequest) {
        this.routes = routes;
        this.bucket = new TokenBucket(rate, burst, System::nanoTime);
        this.maxRequest = maxRequest;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(CORRELATION);
        String correlation = provided == null || !UUID_TEXT.matcher(provided).matches()
                ? UUID.randomUUID().toString() : provided;
        request.setAttribute(CORRELATION, correlation);
        response.setHeader(CORRELATION, correlation);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        MDC.put("correlationId", correlation);
        try {
            if (provided != null && !UUID_TEXT.matcher(provided).matches()) {
                problem(response, 400, "INVALID_CORRELATION_ID", "Correlation ID must be a UUID");
                return;
            }
            if (Routes.health(request.getMethod(), request.getRequestURI())) {
                chain.doFilter(request, response);
                return;
            }
            if (!(Routes.webhook(request.getRequestURI()) ? webhooks : bucket).take()) {
                response.setHeader("Retry-After", "1");
                problem(response, 429, "RATE_LIMITED", "Request rate exceeded");
                return;
            }
            if (routes.destination(request.getMethod(), request.getRequestURI()) == null) {
                problem(response, 404, "NOT_FOUND", "Resource not found");
                return;
            }
            if (request.getContentLengthLong() > (Routes.webhook(request.getRequestURI()) ? 65536 : maxRequest)) {
                problem(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds edge limit");
                return;
            }
            if (request.getHeader("Content-Encoding") != null) {
                problem(response, 415, "UNSUPPORTED_ENCODING", "Encoded request bodies are not supported");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    static void problem(HttpServletResponse response, int status, String code, String title) throws IOException {
        // All values are internal constants, never request data or exception messages.
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"https://odexa.cc/problems/" + code.toLowerCase(java.util.Locale.ROOT)
                + "\",\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
