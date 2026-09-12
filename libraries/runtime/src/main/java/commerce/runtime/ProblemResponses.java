package commerce.runtime;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

final class ProblemResponses {
    private ProblemResponses() {
    }

    static ProblemDetail problem(int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("https://odexa.cc/problems/" + code.toLowerCase(Locale.ROOT)));
        HttpStatus knownStatus = HttpStatus.resolve(status);
        problem.setTitle(knownStatus == null ? "Request failed" : knownStatus.getReasonPhrase());
        String correlationId = Correlation.current();
        // Otherwise MVC populates instance from the request URI, potentially reflecting rejected values.
        problem.setInstance(URI.create("urn:uuid:" + correlationId));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    static void write(HttpServletResponse response, ObjectMapper mapper,
            int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        ProblemDetail problem = problem(status, code, detail);
        // Servlet security/filter responses bypass MVC's ProblemDetail Jackson mixin.
        // Keep extensions flat even when the injected mapper has no HTTP-specific customization.
        var body = new LinkedHashMap<String, Object>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", problem.getInstance().toString());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        mapper.writeValue(response.getWriter(), body);
    }
}
