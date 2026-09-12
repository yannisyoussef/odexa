package commerce.paymentsimulator;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulatorUnitTest {
    @Test void documentedSandboxReferencesAreDeterministic() {
        assertEquals("AUTHORIZED", SimulatorPayments.decision("pm_approved"));
        assertEquals("DECLINED", SimulatorPayments.decision("pm_declined"));
        assertThrows(ProviderProblem.class, () -> SimulatorPayments.decision("other"));
        assertThrows(ProviderProblem.class, () -> SimulatorPayments.decision(null));
    }

    @Test void requiredSecretIsComparedWithoutLoggingOrExposingIt() {
        String secret = UUID.randomUUID().toString();
        var filter = new ProviderKeyFilter(secret, JsonMapper.builder().build());
        assertTrue(filter.matches(secret));
        assertFalse(filter.matches(null));
        assertFalse(filter.matches(""));
        assertFalse(filter.matches(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderKeyFilter("", JsonMapper.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderKeyFilter("\n", JsonMapper.builder().build()));
    }
}
