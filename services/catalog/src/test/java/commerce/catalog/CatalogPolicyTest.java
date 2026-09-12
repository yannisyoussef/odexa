package commerce.catalog;

import commerce.runtime.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CatalogPolicyTest {
    @Test
    void requiresOneStrongPositiveVersion() {
        assertEquals(1, CatalogPolicy.expectedVersion("\"1\""));
        assertEquals(Long.MAX_VALUE, CatalogPolicy.expectedVersion("\"9223372036854775807\""));
        assertThrows(ApiException.class, () -> CatalogPolicy.expectedVersion(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "*", "W/\"1\"", "\"0\"", "\"-1\"", "\"01\"", "\"1\",\"2\"", "\"9223372036854775808\""})
    void rejectsAmbiguousOrInvalidEtags(String etag) {
        assertThrows(ApiException.class, () -> CatalogPolicy.expectedVersion(etag));
    }

    @Test
    void cursorRoundTripsAndRejectsNonCanonicalUuid() {
        UUID id = UUID.randomUUID();
        assertEquals(id, CatalogPolicy.decodeCursor(CatalogPolicy.encodeCursor(id)));
        assertNull(CatalogPolicy.decodeCursor(null));
        assertThrows(ApiException.class, () -> CatalogPolicy.decodeCursor(""));
        assertThrows(ApiException.class, () -> CatalogPolicy.decodeCursor("not-a-cursor"));
        String shortUuid = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1-1-1-1-1".getBytes(StandardCharsets.US_ASCII));
        assertThrows(ApiException.class, () -> CatalogPolicy.decodeCursor(shortUuid));
    }

    @Test
    void searchTreatsSqlWildcardsAsLiteralText() {
        assertEquals("%", CatalogPolicy.searchPattern(null));
        assertEquals("%", CatalogPolicy.searchPattern("  "));
        assertEquals("%a\\%b\\_c\\\\%", CatalogPolicy.searchPattern(" a%b_c\\ "));
    }
}
