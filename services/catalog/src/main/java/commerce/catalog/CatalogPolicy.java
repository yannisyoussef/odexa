package commerce.catalog;

import commerce.runtime.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

final class CatalogPolicy {
    private CatalogPolicy() {
    }

    static long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new ApiException(428, "PRECONDITION_REQUIRED", "A quoted version If-Match header is required");
        }
        if (!ifMatch.matches("\"[1-9][0-9]{0,18}\"")) {
            throw new ApiException(400, "INVALID_ETAG", "If-Match must contain one quoted positive version");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException ignored) {
            throw new ApiException(400, "INVALID_ETAG", "If-Match version is out of range");
        }
    }

    static UUID decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.length() != 48) {
                throw new IllegalArgumentException();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            UUID id = UUID.fromString(decoded);
            if (!encodeCursor(id).equals(cursor)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException ignored) {
            throw new ApiException(400, "INVALID_CURSOR", "The pagination cursor is invalid");
        }
    }

    static String encodeCursor(UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static String searchPattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        return "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
