package commerce.inventory;

import commerce.runtime.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static commerce.inventory.InventoryPolicy.ReservationState.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryPolicyTest {
    @Test
    void firstTerminalOutcomeWinsAndRejectionsStayRejected() {
        assertEquals(COMMITTED, InventoryPolicy.settle(RESERVED, true));
        assertEquals(RELEASED, InventoryPolicy.settle(RESERVED, false));
        for (var terminal : new InventoryPolicy.ReservationState[]{COMMITTED, RELEASED, REJECTED}) {
            assertEquals(terminal, InventoryPolicy.settle(terminal, true));
            assertEquals(terminal, InventoryPolicy.settle(terminal, false));
        }
    }

    @Test
    void adjustmentsProtectHoldsAndRequireCurrentVersion() {
        assertDoesNotThrow(() -> InventoryPolicy.checkAdjustment(4, 4, 2, 2));
        assertThrows(ApiException.class, () -> InventoryPolicy.checkAdjustment(3, 4, 2, 2));
        assertThrows(ApiException.class, () -> InventoryPolicy.checkAdjustment(-1, 0, 2, 2));
        assertThrows(ApiException.class, () -> InventoryPolicy.checkAdjustment(100, 4, 1, 2));
    }

    @Test
    void requiresStrongVersion() {
        assertEquals(1, InventoryPolicy.expectedVersion("\"1\""));
        assertEquals(Long.MAX_VALUE, InventoryPolicy.expectedVersion("\"9223372036854775807\""));
        assertThrows(ApiException.class, () -> InventoryPolicy.expectedVersion(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "*", "W/\"1\"", "\"0\"", "\"-1\"", "\"01\"", "\"1\",\"2\"", "\"9223372036854775808\""})
    void rejectsAmbiguousOrInvalidEtags(String etag) {
        assertThrows(ApiException.class, () -> InventoryPolicy.expectedVersion(etag));
    }
}
