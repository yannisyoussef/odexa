package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import commerce.runtime.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PaymentControllerTest {
    @Test void queryAlwaysUsesJwtTenantAndOwnerNotRequestSuppliedIdentity() {
        PaymentStore store = mock(PaymentStore.class);
        PaymentController controller = new PaymentController(store);
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("unit-test-jwt").header("alg", "none")
                .subject("customer-a").claim("tenant_id", tenant.toString()).build();
        controller.get(order, jwt);
        verify(store).get(tenant, "customer-a", order);
        assertThrows(ApiException.class, () -> controller.get(order, null));
    }
}
