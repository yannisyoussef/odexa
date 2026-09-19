package commerce.order;

import commerce.runtime.ApiException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

/** Small, bounded query vocabulary; ownership is never a client-supplied filter. */
public record OrderQuery(int limit, OrderStatus status, Instant createdFrom, Instant createdBefore, Cursor cursor) {
    private static final Set<String> PARAMETERS = Set.of("limit", "status", "createdFrom", "createdBefore", "cursor");

    public static OrderQuery parse(MultiValueMap<String, String> parameters) {
        if (parameters.entrySet().stream().anyMatch(e -> !PARAMETERS.contains(e.getKey()) || e.getValue().size() != 1)) {
            throw invalid("INVALID_ORDER_FILTER");
        }
        int limit = 20;
        if (parameters.containsKey("limit")) {
            String raw = parameters.getFirst("limit");
            if (raw == null || !raw.matches("[1-9][0-9]{0,2}")) throw invalid("INVALID_ORDER_LIMIT");
            limit = Integer.parseInt(raw);
            if (limit > 100) throw invalid("INVALID_ORDER_LIMIT");
        }
        OrderStatus status = null;
        if (parameters.containsKey("status")) {
            try {
                status = OrderStatus.valueOf(parameters.getFirst("status"));
            } catch (IllegalArgumentException | NullPointerException failure) {
                throw invalid("INVALID_ORDER_STATUS");
            }
        }
        Instant from = timestamp(parameters.getFirst("createdFrom"));
        Instant before = timestamp(parameters.getFirst("createdBefore"));
        if (from != null && before != null && !from.isBefore(before)) throw invalid("INVALID_ORDER_DATE_RANGE");
        return new OrderQuery(limit, status, from, before, Cursor.decode(parameters.getFirst("cursor")));
    }

    private static Instant timestamp(String raw) {
        if (raw == null) return null;
        try {
            if (raw.length() > 40) throw invalid("INVALID_ORDER_TIMESTAMP");
            Instant value = Instant.parse(raw);
            // Bound to a documented civil range supported identically by HTTP and PostgreSQL.
            if (value.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || !value.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
                throw invalid("INVALID_ORDER_TIMESTAMP");
            }
            return value;
        } catch (DateTimeParseException failure) {
            throw invalid("INVALID_ORDER_TIMESTAMP");
        }
    }

    private static ApiException invalid(String code) {
        return new ApiException(400, code, "The order query is invalid");
    }

    public record Cursor(Instant createdAt, UUID id) {
        public String encode() {
            ByteBuffer bytes = ByteBuffer.allocate(29).put((byte) 1).putLong(createdAt.getEpochSecond())
                    .putInt(createdAt.getNano()).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        }

        static Cursor decode(String raw) {
            if (raw == null) return null;
            try {
                if (raw.length() != 39) throw new IllegalArgumentException();
                ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(raw));
                if (bytes.remaining() != 29 || bytes.get() != 1) throw new IllegalArgumentException();
                long seconds = bytes.getLong();
                int nanos = bytes.getInt();
                if (nanos < 0 || nanos > 999999999) throw new IllegalArgumentException();
                Instant time = timestamp(Instant.ofEpochSecond(seconds, nanos).toString());
                Cursor cursor = new Cursor(time, new UUID(bytes.getLong(), bytes.getLong()));
                if (!cursor.encode().equals(raw)) throw new IllegalArgumentException();
                return cursor;
            } catch (RuntimeException failure) {
                throw invalid("INVALID_ORDER_CURSOR");
            }
        }
    }
}
