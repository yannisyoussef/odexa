package commerce.runtime;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionAdvice {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionAdvice.class);

    @org.springframework.web.bind.annotation.InitBinder
    public void strictIdentifiers(org.springframework.web.bind.WebDataBinder binder) {
        // A failed ConversionService conversion can fall back to Spring's permissive UUID editor.
        binder.registerCustomEditor(java.util.UUID.class, new java.beans.PropertyEditorSupport() {
            @Override
            public void setAsText(String value) {
                if (!Correlation.isUuid(value)) {
                    throw new IllegalArgumentException("A canonical UUID is required");
                }
                setValue(java.util.UUID.fromString(value));
            }
        });
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> api(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, TypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        // Never include rejected values, JSON snippets, validation messages or parser causes.
        if (exception instanceof HandlerMethodValidationException validation && validation.isForReturnValue()) {
            return unexpected(exception);
        }
        return response(400, "INVALID_REQUEST", "The request is invalid.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> forbidden(AccessDeniedException exception) {
        return response(403, "FORBIDDEN", "Access is not permitted.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException exception) {
        return response(401, "UNAUTHORIZED", "Authentication is required.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NoResourceFoundException exception) {
        return response(404, "NOT_FOUND", "The resource was not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(405, "METHOD_NOT_ALLOWED", "The HTTP method is not supported.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMedia(HttpMediaTypeNotSupportedException exception) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "The content type is not supported.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return response(406, "NOT_ACCEPTABLE", "The requested representation is not supported.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        LOG.error("Request failed; correlationId={} exceptionType={}",
                Correlation.current(), exception.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "The request could not be completed.");
    }

    private static ResponseEntity<ProblemDetail> response(int status, String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ProblemResponses.problem(status, code, detail));
    }
}
