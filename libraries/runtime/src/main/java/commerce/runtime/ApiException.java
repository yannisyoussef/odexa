package commerce.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/** An intentional, client-safe API failure. Never pass database or provider messages here. */
public class ApiException extends RuntimeException {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,79}");
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        if (status < 400 || status > 599 || code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid API error status or code");
        }
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
