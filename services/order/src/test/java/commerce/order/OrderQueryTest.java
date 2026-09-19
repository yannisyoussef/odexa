package commerce.order;

import static org.junit.jupiter.api.Assertions.*;

import commerce.runtime.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class OrderQueryTest {
    @Test
    void opaqueCursorRoundTripsTimestampTiesAndRejectsMalformedValues() {
        var cursor = new OrderQuery.Cursor(Instant.parse("2026-09-01T12:00:00.123456Z"), UUID.randomUUID());
        assertEquals(cursor, OrderQuery.Cursor.decode(cursor.encode()));
        for (String raw : new String[] {"", "x", "a".repeat(39), cursor.encode() + "=", "a".repeat(5000)}) {
            assertEquals("INVALID_ORDER_CURSOR", assertThrows(ApiException.class, () -> OrderQuery.Cursor.decode(raw)).code());
        }
    }

    @Test
    void filtersHaveStableCodesAndNeverAcceptOwnershipOverridesOrDuplicateParameters() {
        Map<String, String> invalid = Map.of("limit", "101", "status", "UNKNOWN", "createdFrom", "yesterday", "cursor", "bad",
                "customerId", "someone", "tenantId", UUID.randomUUID().toString());
        Map<String, String> codes = Map.of("limit", "INVALID_ORDER_LIMIT", "status", "INVALID_ORDER_STATUS", "createdFrom", "INVALID_ORDER_TIMESTAMP",
                "cursor", "INVALID_ORDER_CURSOR", "customerId", "INVALID_ORDER_FILTER", "tenantId", "INVALID_ORDER_FILTER");
        invalid.forEach((key, value) -> {
            var query = new LinkedMultiValueMap<String, String>(); query.add(key, value);
            assertEquals(codes.get(key), assertThrows(ApiException.class, () -> OrderQuery.parse(query)).code());
        });
        for (String limit : new String[] {"0", "-1", "1.0", "9999999999999999999", "", " 20"}) {
            var query = new LinkedMultiValueMap<String, String>(); query.add("limit", limit);
            assertEquals("INVALID_ORDER_LIMIT", assertThrows(ApiException.class, () -> OrderQuery.parse(query)).code());
        }
        var query = new LinkedMultiValueMap<String, String>();
        query.add("createdFrom", "2026-09-01T00:00:00Z"); query.add("createdBefore", "2026-09-01T00:00:00Z");
        assertEquals("INVALID_ORDER_DATE_RANGE", assertThrows(ApiException.class, () -> OrderQuery.parse(query)).code());
        query.clear(); query.add("status", "CREATED"); query.add("status", "CONFIRMED");
        assertEquals("INVALID_ORDER_FILTER", assertThrows(ApiException.class, () -> OrderQuery.parse(query)).code());
        assertEquals(20, OrderQuery.parse(new LinkedMultiValueMap<>()).limit());
    }
}
