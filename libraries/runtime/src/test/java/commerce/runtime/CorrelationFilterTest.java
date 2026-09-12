package commerce.runtime;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {
    private final CorrelationFilter filter = new CorrelationFilter(JsonMapper.builder().build());

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void generatesOneIdForRequestAndResponseAndCleansOnlyItsMdcKey() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        MDC.put("unrelated", "preserved");
        filter.doFilter(request, response, (incoming, outgoing) -> {
            String id = Correlation.current();
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(Correlation.current()).isEqualTo(id);
            assertThat(response.getHeader(Correlation.HEADER)).isEqualTo(id);
        });
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @Test
    void preservesValidHeaderAndClearsOnFailure() {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(Correlation.HEADER, id);
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(Correlation.current()).isEqualTo(id);
            throw new ServletException("synthetic failure");
        })).isInstanceOf(ServletException.class);
        assertThat(response.getHeader(Correlation.HEADER)).isEqualTo(id);
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void rejectsHeaderInjectionAndDoesNotReflectUntrustedValue() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(Correlation.HEADER, "untrusted-value\r\nInjected: yes");
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (incoming, outgoing) -> invoked.set(true));
        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("untrusted-value", "Injected");
        assertThat(Correlation.isUuid(response.getHeader(Correlation.HEADER))).isTrue();
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void rejectsDuplicateHeadersEvenWhenIdentical() throws Exception {
        var request = new MockHttpServletRequest();
        String id = UUID.randomUUID().toString();
        request.addHeader(Correlation.HEADER, id);
        request.addHeader(Correlation.HEADER, id);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("Invalid request reached the application");
        });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void backgroundIdsDoNotLeakAcrossJobs() {
        assertThat(Correlation.isUuid(Correlation.current())).isTrue();
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
        MDC.put(Correlation.MDC_KEY, "untrusted-value");
        assertThat(Correlation.isUuid(Correlation.current())).isTrue();
    }
}
