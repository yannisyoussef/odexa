package commerce.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {HttpSecurityTest.WebConfiguration.class, SecurityConfig.class,
        RuntimeWebConfig.class, CorrelationFilter.class, ApiExceptionAdvice.class})
class HttpSecurityTest {
    private static final String TENANT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationFilter correlation;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlation).apply(springSecurity()).build();
    }

    @Test
    void anonymousApiIs401WithProblemAndCorrelation() throws Exception {
        mvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://odexa.cc/problems/unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(result -> assertThat(Correlation.isUuid(result.getResponse().getHeader(Correlation.HEADER))).isTrue());
    }

    @Test
    void invalidBearerTokenUsesSanitizedProblemInsteadOfDecoderDetails() throws Exception {
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("untrusted-value"));
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer invalid-test-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(content().string(not(containsString("untrusted-value"))))
                .andExpect(content().string(not(containsString("invalid-test-token"))));
    }

    @Test
    void onlyGetHealthIsPublicAndOtherActuatorAndPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(post("/actuator/health").with(actor())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(actor())).andExpect(status().isForbidden());
        mvc.perform(get("/private").with(actor())).andExpect(status().isForbidden());
    }

    @Test
    void tenantComesFromJwtAndNotAConflictingHeader() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/probe").with(actor()).header("X-Tenant-ID", UUID.randomUUID())
                        .header(Correlation.HEADER, id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(header().string(Correlation.HEADER, id));
    }

    @Test
    void missingOrMalformedTenantIs403EvenWhenHeaderClaimsValidTenant() throws Exception {
        mvc.perform(get("/api/v1/probe").with(jwt()).header("X-Tenant-ID", TENANT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/probe").with(jwt().jwt(builder -> builder.claim("tenant_id", "1-1-1-1-1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerOnlyRequestsNeedNoCsrfButDoNeedTheBusinessRole() throws Exception {
        mvc.perform(post("/api/v1/merchant").with(actor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/merchant").with(jwt().jwt(builder -> builder.claim("tenant_id", TENANT)
                        .claim("realm_access", Map.of("roles", List.of("MERCHANT_USER"))))))
                .andExpect(status().isOk());
    }

    @Test
    void missingHeaderBadUuidMalformedBodyAndValidationAreSanitized400() throws Exception {
        mvc.perform(get("/api/v1/header").with(actor())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ids/untrusted-value").with(actor()))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("untrusted-value"))));
        mvc.perform(get("/api/v1/ids/1-1-1-1-1").with(actor())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/validated").with(actor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/validated").with(actor()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"untrusted-value\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("untrusted-value"))));
    }

    @Test
    void genericExceptionsNeverExposeCauseOrStackAndExplicitApiErrorsKeepTheirContract() throws Exception {
        mvc.perform(get("/api/v1/failure").with(actor()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("untrusted-value"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
        mvc.perform(get("/api/v1/conflict").with(actor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://odexa.cc/problems/idempotency_conflict"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor() {
        return jwt().jwt(builder -> builder.subject("customer-a").claim("tenant_id", TENANT)
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class WebConfiguration {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean ProbeController controller() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/probe")
        Map<String, String> probe(@AuthenticationPrincipal Jwt jwt) {
            return Map.of("tenantId", Actor.from(jwt).tenantId().toString());
        }

        @GetMapping("/actuator/health/readiness")
        Map<String, String> health() { return Map.of("status", "UP"); }

        @PostMapping("/api/v1/merchant")
        void merchant(@AuthenticationPrincipal Jwt jwt) { Actor.from(jwt).requireRole("MERCHANT_ADMIN", "MERCHANT_USER"); }

        @GetMapping("/api/v1/header")
        void header(@RequestHeader("Idempotency-Key") String key) { }

        @GetMapping("/api/v1/ids/{id}")
        void identifier(@PathVariable("id") UUID id) { }

        @PostMapping("/api/v1/validated")
        void validated(@Valid @RequestBody Quantity quantity) { }

        @GetMapping("/api/v1/failure")
        void failure() { throw new IllegalStateException("untrusted-value"); }

        @GetMapping("/api/v1/conflict")
        void conflict() { throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "The key was already used."); }
    }

    record Quantity(@Min(1) int quantity) { }
}
