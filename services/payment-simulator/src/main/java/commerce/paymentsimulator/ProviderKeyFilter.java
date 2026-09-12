package commerce.paymentsimulator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ProviderKeyFilter extends OncePerRequestFilter {
    private final byte[] expectedDigest;
    private final ObjectMapper mapper;

    public ProviderKeyFilter(@Value("${simulator.provider-api-key}") String key, ObjectMapper mapper) {
        if (key == null || key.isBlank() || key.length() > 512
                || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("PROVIDER_API_KEY must be a nonempty printable secret");
        }
        this.expectedDigest = digest(key);
        this.mapper = mapper;
    }

    boolean matches(String presented) {
        // Compare fixed-size digests in constant time; never log either key or its digest.
        return MessageDigest.isEqual(expectedDigest, digest(presented == null ? "" : presented));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if ("GET".equals(request.getMethod()) && (path.equals("/actuator/health")
                || path.startsWith("/actuator/health/"))) {
            chain.doFilter(request, response);
            return;
        }
        if (!path.startsWith("/provider/v1/")) {
            reject(response, 404, "NOT_FOUND", "Resource not found");
            return;
        }
        var keys = Collections.list(request.getHeaders("X-Provider-Key"));
        boolean accepted = matches(keys.size() == 1 ? keys.getFirst() : null);
        if (!accepted) {
            reject(response, 401, "PROVIDER_UNAUTHORIZED", "Provider authentication required");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(ProviderAdvice.problem(status, code, detail)));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required key comparison algorithm unavailable");
        }
    }
}
