package commerce.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import commerce.runtime.SecurityConfig;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(CatalogBatchHttpTest.Config.class)
@WebAppConfiguration
class CatalogBatchHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired CatalogService service;
    private MockMvc mvc;
    private final UUID tenant = UUID.randomUUID(), product = UUID.randomUUID();
    private final String path = "/api/internal/v1/products/batch";
    private String body() { return "{\"productIds\":[\"" + product + "\"]}"; }
    @BeforeEach void setup() { reset(service); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void customerBatchPassesActualSecurityChainAndUsesOnlySignedTenant() throws Exception {
        when(service.batch(tenant,List.of(product))).thenReturn(List.of(new Product(product,"A","private description",2500,"USD",true,7)));
        mvc.perform(post(path).with(jwt().jwt(t -> t.subject("owner").claim("tenant_id",tenant.toString())
                        .claim("realm_access",Map.of("roles",List.of("CUSTOMER")))))
                .contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(product.toString()))
                .andExpect(jsonPath("$[0].unitPriceMinor").value(2500)).andExpect(jsonPath("$[0].description").doesNotExist());
        verify(service).batch(tenant,List.of(product));
    }
    @Test void missingAuthenticationTenantAndRoleCannotReachBatchService() throws Exception {
        mvc.perform(post(path).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().jwt(t -> t.subject("owner").claim("realm_access",Map.of("roles",List.of("CUSTOMER")))))
                .contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(t -> t.subject("owner").claim("tenant_id",tenant.toString())
                        .claim("realm_access",Map.of("roles",List.of("MERCHANT_ADMIN")))))
                .contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    @ComponentScan(basePackages="commerce.runtime",useDefaultFilters=false,
            includeFilters=@ComponentScan.Filter(type=FilterType.ANNOTATION,classes=ControllerAdvice.class))
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean CatalogService service() { return mock(CatalogService.class); }
        @Bean CatalogBatchController controller(CatalogService service) { return new CatalogBatchController(service); }
    }
}
