package commerce.catalog;

import commerce.runtime.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogControllerTest {
    private final CatalogService service = mock(CatalogService.class);
    private final CatalogController controller = new CatalogController(service);
    private final UUID tenant = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final ProductInput input = new ProductInput("Tote", "", 2500L, "USD", true);

    @Test
    void merchantWritesReturnVersionAndLocationAndUseOnlyJwtTenant() {
        Product product = new Product(productId, "Tote", "", 2500, "USD", true, 1);
        when(service.create(tenant, input)).thenReturn(product);
        var response = controller.create(jwt("MERCHANT_USER"), input);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("\"1\"", response.getHeaders().getETag());
        assertEquals("/api/v1/products/" + productId, response.getHeaders().getLocation().toString());
        verify(service).create(tenant, input);
    }

    @Test
    void customerAndPlatformAdminCannotWrite() {
        for (String role : List.of("CUSTOMER", "PLATFORM_ADMIN", "SUPPORT")) {
            assertEquals(403, assertThrows(ApiException.class, () -> controller.create(jwt(role), input)).status());
        }
        verifyNoInteractions(service);
    }

    @Test
    void merchantPutRequiresIfMatchBeforeMutation() {
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.update(jwt("MERCHANT_ADMIN"), productId, null, input)).status());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedReadsAreTenantScoped() {
        when(service.list(tenant, 20, null, null)).thenReturn(new ProductPage(List.of(), null));
        assertTrue(controller.list(jwt("CUSTOMER"), 20, null, null).items().isEmpty());
        verify(service).list(tenant, 20, null, null);
    }

    @Test void internalBatchIndependentlyChecksRoleTenantAndBounds() {
        var batch = new CatalogBatchController(service);
        var request = new CatalogBatchController.Batch(List.of(productId));
        when(service.batch(tenant,List.of(productId))).thenReturn(List.of(new Product(productId,"Tote","",2500,"USD",true,1)));
        assertEquals(1,batch.batch(jwt("CUSTOMER"),request).size());
        verify(service).batch(tenant,List.of(productId));
        for (String role : List.of("MERCHANT_USER","SUPPORT","PLATFORM_ADMIN"))
            assertEquals(403,assertThrows(ApiException.class,() -> batch.batch(jwt(role),request)).status());
        for (var ids : List.of(List.<UUID>of(),List.of(productId,productId),java.util.stream.IntStream.range(0,21).mapToObj(i -> UUID.randomUUID()).toList()))
            assertEquals(400,assertThrows(ApiException.class,() -> batch.batch(jwt("CUSTOMER"),new CatalogBatchController.Batch(ids))).status());
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("test-placeholder").header("alg", "RS256").subject("customer-a")
                .claim("tenant_id", tenant.toString()).claim("realm_access", Map.of("roles", List.of(role))).build();
    }
}
