package commerce.inventory;

import commerce.runtime.ApiException;

final class InventoryPolicy {
    enum ReservationState { RESERVED, COMMITTED, RELEASED, REJECTED }

    private InventoryPolicy() {
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

    static void checkAdjustment(long onHand, long reserved, long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new ApiException(412, "STALE_VERSION", "The inventory version has changed");
        }
        if (onHand < 0) {
            throw new ApiException(400, "INVALID_STOCK", "On-hand stock must be non-negative");
        }
        if (onHand < reserved) {
            throw new ApiException(409, "STOCK_RESERVED", "On-hand stock cannot be lower than reserved stock");
        }
    }

    // First terminal outcome wins. Unknown payment outcomes deliberately leave the hold intact.
    static ReservationState settle(ReservationState current, boolean authorized) {
        return current == ReservationState.RESERVED
                ? (authorized ? ReservationState.COMMITTED : ReservationState.RELEASED) : current;
    }
}
