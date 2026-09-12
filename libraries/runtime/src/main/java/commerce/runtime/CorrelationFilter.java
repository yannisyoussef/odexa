package commerce.runtime;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {
    private static final String REQUEST_ATTRIBUTE = CorrelationFilter.class.getName() + ".id";
    private final ObjectMapper mapper;

    public CorrelationFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(Correlation.HEADER));
        boolean invalid = values.size() > 1 || (values.size() == 1 && !Correlation.isUuid(values.getFirst()));
        Object saved = request.getAttribute(REQUEST_ATTRIBUTE);
        String id = saved instanceof String value && Correlation.isUuid(value) ? value
                : !invalid && !values.isEmpty() ? UUID.fromString(values.getFirst()).toString()
                : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, id);
        MDC.put(Correlation.MDC_KEY, id);
        response.setHeader(Correlation.HEADER, id);
        try {
            if (invalid) {
                ProblemResponses.write(response, mapper, 400, "INVALID_CORRELATION_ID",
                        "X-Correlation-ID must be a single UUID.");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
