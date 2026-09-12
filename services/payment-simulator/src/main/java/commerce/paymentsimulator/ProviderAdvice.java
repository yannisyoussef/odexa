package commerce.paymentsimulator;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProviderAdvice extends ResponseEntityExceptionHandler {
    static ProblemDetail problem(int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("https://odexa.cc/problems/" + code.toLowerCase(Locale.ROOT)));
        problem.setProperty("code", code);
        return problem;
    }

    @ExceptionHandler(ProviderProblem.class)
    ResponseEntity<ProblemDetail> providerProblem(ProviderProblem exception) {
        return ResponseEntity.status(exception.status()).body(
                problem(exception.status(), exception.code(), exception.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(problem(status.value(), "INVALID_REQUEST", "Invalid provider request"),
                headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        return ResponseEntity.internalServerError().body(
                problem(500, "INTERNAL_ERROR", "Unable to process provider request"));
    }
}
