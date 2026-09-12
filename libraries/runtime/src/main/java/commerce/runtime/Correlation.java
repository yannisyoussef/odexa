package commerce.runtime;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class Correlation {
    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Correlation() {
    }

    /** Background work gets a fresh identifier without contaminating a pooled thread's MDC. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return isUuid(value) ? value : UUID.randomUUID().toString();
    }

    static boolean isUuid(String value) {
        return value != null && UUID_TEXT.matcher(value).matches();
    }
}
