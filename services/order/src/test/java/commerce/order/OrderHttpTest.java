package commerce.order;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import commerce.runtime.CorrelationFilter;
import commerce.runtime.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real runtime security filter chain; jwt() supplies an already-validated test principal. */
@SpringJUnitConfig(OrderHttpTest.HttpConfig.class)
@WebAppConfiguration
class OrderHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired OrderRepository orders;
    @Autowired CatalogClient catalog;
    @Autowired CheckoutWriter writer;
    @Autowired ObjectMapper mapper;
    @Autowired commerce.runtime.Outbox outbox;
    private MockMvc mvc;
    private final Order order = OrderStateMachineTest.created();

    @BeforeEach
    void setup() {
        reset(orders, catalog, writer, outbox);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationFilter(mapper)).apply(springSecurity()).build();
    }

    @Test
    void missingJwtIs401AndMerchantOrMissingTenantIs403() throws Exception {
        mvc.perform(get("/api/v1/orders/" + order.id()))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/orders/" + order.id()).with(actor(order.tenantId(), order.customerId(), "MERCHANT_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/" + order.id()).with(jwt().jwt(token -> token
                .subject(order.customerId()).claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orders, catalog, writer);
    }

    @Test
    void ownerReadIs200ButUnknownCrossTenantAndOtherOwnerAre404() throws Exception {
        when(orders.findOwned(order.tenantId(), order.customerId(), order.id())).thenReturn(Optional.of(order));
        mvc.perform(get("/api/v1/orders/" + order.id()).with(customer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(order.id().toString()))
                .andExpect(jsonPath("$.paymentMethod").doesNotExist()).andExpect(jsonPath("$.customerId").doesNotExist());
        mvc.perform(get("/api/v1/orders/" + order.id()).with(actor(UUID.randomUUID(), order.customerId(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + order.id()).with(actor(order.tenantId(), "other-customer", "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + UUID.randomUUID()).with(customer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAndReplayReturnSameLocationWith201Then200AndCorrelation() throws Exception {
        CheckoutRequest request = new CheckoutRequest(order.snapshot().productId(), 2, "pm_approved");
        when(catalog.snapshot(eq(request), anyString(), anyString())).thenReturn(order.snapshot());
        when(writer.create(any(), eq("checkout"), eq(request.fingerprint()))).thenReturn(new CheckoutWriter.Result(order, true));
        String correlation = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/orders").with(customer()).header("Idempotency-Key", "checkout")
                        .header("X-Correlation-ID", correlation).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/orders/" + order.id()))
                .andExpect(header().string("X-Correlation-ID", correlation)).andExpect(jsonPath("$.totalMinor").value(5000));
        when(orders.findByKey(order.tenantId(), order.customerId(), "checkout"))
                .thenReturn(Optional.of(new OrderRepository.StoredOrder(order, request.fingerprint())));
        clearInvocations(catalog, writer);
        mvc.perform(post("/api/v1/orders").with(customer()).header("Idempotency-Key", "checkout")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(header().string("Location", "/api/v1/orders/" + order.id()));
        verifyNoInteractions(catalog, writer);
    }

    @Test
    void missingKeyInvalidQuantityMalformedUuidAndBadCorrelationAre400() throws Exception {
        CheckoutRequest request = new CheckoutRequest(order.snapshot().productId(), 2, "pm_approved");
        mvc.perform(post("/api/v1/orders").with(customer()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(customer()).header("Idempotency-Key", "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                                new CheckoutRequest(request.productId(), 101, request.paymentMethod()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders/not-a-uuid").with(customer())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders/" + order.id()).with(customer()).header("X-Correlation-ID", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(catalog, writer);
    }

    @Test
    void queryAndHistoryOperationsRequireExplicitRolesAndHideForeignResources() throws Exception {
        when(orders.list(eq(order.tenantId()), eq(order.customerId()), any())).thenReturn(List.of(order));
        when(orders.list(eq(order.tenantId()), isNull(), any())).thenReturn(List.of(order));
        when(orders.findOwned(order.tenantId(), order.customerId(), order.id())).thenReturn(Optional.of(order));
        when(orders.findTenant(order.tenantId(), order.id())).thenReturn(Optional.of(order));
        when(orders.history(order.id())).thenReturn(List.of(new OrderHistoryEntry(0, OrderStatus.CREATED, order.createdAt(), "ORDER_CREATED")));
        mvc.perform(get("/api/v1/orders").with(customer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(order.id().toString()))
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist());
        verify(orders).list(eq(order.tenantId()), eq(order.customerId()), any());
        for (String role : List.of("MERCHANT_ADMIN", "MERCHANT_USER")) {
            var merchant = actor(order.tenantId(), "merchant", role);
            mvc.perform(get("/api/v1/merchant/orders").with(merchant)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].customerId").doesNotExist()).andExpect(jsonPath("$.items[0].paymentMethod").doesNotExist());
            mvc.perform(get("/api/v1/merchant/orders/" + order.id()).with(merchant)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchant/orders/" + order.id() + "/history").with(merchant)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].reason").value("ORDER_CREATED"));
            mvc.perform(get("/api/v1/orders").with(merchant)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/v1/orders", "/api/v1/orders/" + order.id() + "/history",
                "/api/v1/merchant/orders", "/api/v1/merchant/orders/" + order.id(), "/api/v1/merchant/orders/" + order.id() + "/history")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/v1/merchant/orders", "/api/v1/merchant/orders/" + order.id(), "/api/v1/merchant/orders/" + order.id() + "/history")) {
            mvc.perform(get(path).with(customer())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/orders/" + order.id() + "/history").with(actor(order.tenantId(), "other", "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/merchant/orders/" + order.id()).with(actor(UUID.randomUUID(), "merchant", "MERCHANT_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/merchant/orders/" + order.id() + "/history").with(actor(UUID.randomUUID(), "merchant", "MERCHANT_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidQueryReturnsCodedProblemsAndNoDatabaseQuery() throws Exception {
        for (var entry : Map.of("limit", "0", "status", "UNKNOWN", "cursor", "invalid", "createdFrom", "bad", "customerId", "other").entrySet()) {
            mvc.perform(get("/api/v1/orders").with(customer()).param(entry.getKey(), entry.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").exists());
        }
        verifyNoInteractions(orders);
    }

    @Test
    void cancellationHasNoPayloadRequiresOwnershipAndReturnsStableConflicts() throws Exception {
        String path = "/api/v1/orders/" + order.id() + "/cancel";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(actor(order.tenantId(), "merchant", "MERCHANT_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(customer()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(customer()).param("customerId", "other")).andExpect(status().isBadRequest());
        when(orders.lockOwned(order.tenantId(), order.customerId(), order.id())).thenReturn(Optional.of(order));
        mvc.perform(post(path).with(actor(order.tenantId(), "other", "CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(customer())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        when(outbox.withdrawUnattempted("order.created", order.tenantId(), order.id().toString())).thenReturn(Optional.of(new commerce.runtime.Outbox.Withdrawn(UUID.randomUUID(), "checkout-correlation")));
        mvc.perform(post(path).with(customer())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        when(orders.lockOwned(order.tenantId(), order.customerId(), order.id())).thenReturn(Optional.of(order.stopBeforeDispatch(OrderStatus.CANCELLED)));
        clearInvocations(outbox);
        mvc.perform(post(path).with(customer())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verifyNoInteractions(outbox);
    }

    @Test void basketBoundaryRejectsNullAlternateFieldsUnknownOwnedValuesAndCoercion() throws Exception {
        String item = "{\"productId\":\"" + order.snapshot().productId() + "\",\"quantity\":2}";
        for (String body : List.of(
                "{\"items\":[" + item + "],\"productId\":null,\"paymentMethod\":\"pm_approved\"}",
                "{\"productId\":\"" + order.snapshot().productId() + "\",\"quantity\":2,\"items\":null,\"paymentMethod\":\"pm_approved\"}",
                "{\"items\":[" + item.replace("2}","2,\"unitPriceMinor\":1}") + "],\"paymentMethod\":\"pm_approved\"}",
                "{\"items\":[" + item.replace("2}","\"2\"}") + "],\"paymentMethod\":\"pm_approved\"}")) {
            mvc.perform(post("/api/v1/orders").with(customer()).header("Idempotency-Key","invalid-basket")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(catalog,writer);
    }

    private JwtRequestPostProcessor customer() {
        return actor(order.tenantId(), order.customerId(), "CUSTOMER");
    }

    private static JwtRequestPostProcessor actor(UUID tenant, String subject, String role) {
        return jwt().jwt(token -> token.subject(subject).claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of(role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    @ComponentScan(basePackages = "commerce.runtime", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = ControllerAdvice.class))
    static class HttpConfig {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean OrderRepository orders() { return mock(OrderRepository.class); }
        @Bean CatalogClient catalog() { return mock(CatalogClient.class); }
        @Bean CheckoutWriter writer() { return mock(CheckoutWriter.class); }
        @Bean CheckoutService service(OrderRepository orders, CheckoutWriter writer, CatalogClient catalog) {
            return new CheckoutService(orders, writer, catalog, java.time.Clock.systemUTC());
        }
        @Bean commerce.runtime.Outbox outbox() { return mock(commerce.runtime.Outbox.class); }
        @Bean OrderQueries queries(OrderRepository orders) { return new OrderQueries(orders); }
        @Bean OrderQueryController queryController(OrderQueries queries) { return new OrderQueryController(queries); }
        @Bean OrderLifecycle lifecycle(OrderRepository orders, commerce.runtime.Outbox outbox) {
            return new OrderLifecycle(orders, outbox, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30));
        }
        @Bean OrderLifecycleController lifecycleController(OrderLifecycle lifecycle) { return new OrderLifecycleController(lifecycle); }
        @Bean OrderController controller(CheckoutService service) { return new OrderController(service); }
    }
}
