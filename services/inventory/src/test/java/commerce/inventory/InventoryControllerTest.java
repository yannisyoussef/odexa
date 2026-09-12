package commerce.inventory;

import commerce.runtime.ApiException;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryControllerTest {
    private final InventoryService service = mock(InventoryService.class);
    private final InventoryController controller = new InventoryController(service);
    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @Test
    void readUsesTenantFromJwtAndReturnsEtag() {
        Stock stock = new Stock(product, 100, 10, 90, 4);
        when(service.get(tenant, product)).thenReturn(stock);
        var response = controller.get(jwt("CUSTOMER"), product);
        assertEquals("\"4\"", response.getHeaders().getETag());
        assertEquals(stock, response.getBody());
        verify(service).get(tenant, product);
    }

    @Test
    void merchantAdjustmentRequiresVersionAndForwardsTenant() {
        Stock stock = new Stock(product, 90, 10, 80, 5);
        when(service.adjust(tenant, product, 4, 90)).thenReturn(stock);
        var response = controller.adjust(jwt("MERCHANT_ADMIN"), product, "\"4\"", new StockInput(90L));
        assertEquals("\"5\"", response.getHeaders().getETag());
        verify(service).adjust(tenant, product, 4, 90);
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.adjust(jwt("MERCHANT_USER"), product, null, new StockInput(90L))).status());
    }

    @Test
    void customerAndPlatformRolesCannotAdjustInventory() {
        for (String role : List.of("CUSTOMER", "PLATFORM_ADMIN", "SUPPORT")) {
            assertEquals(403, assertThrows(ApiException.class,
                    () -> controller.adjust(jwt(role), product, "\"1\"", new StockInput(90L))).status());
        }
        verifyNoInteractions(service);
    }

    @Test
    void onHandMustBePresentAndNonNegative() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new StockInput(0L)).isEmpty());
            assertFalse(validator.validate(new StockInput(-1L)).isEmpty());
            assertFalse(validator.validate(new StockInput(null)).isEmpty());
        }
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("test-placeholder").header("alg", "RS256").subject("customer-a")
                .claim("tenant_id", tenant.toString()).claim("realm_access", Map.of("roles", List.of(role))).build();
    }
}
