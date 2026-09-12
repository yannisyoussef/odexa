package commerce.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void derivesOnlyRealmRolesAndTenantClaim() {
        Actor actor = Actor.from(jwt(TENANT.toString()).claim("roles", List.of("PLATFORM_ADMIN"))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", "MERCHANT_USER"))).build());
        assertThat(actor.tenantId()).isEqualTo(TENANT);
        assertThat(actor.subject()).isEqualTo("customer-a");
        assertThat(actor.roles()).containsExactlyInAnyOrder("CUSTOMER", "MERCHANT_USER");
        actor.requireRole("MERCHANT_ADMIN", "CUSTOMER");
        assertThatThrownBy(() -> actor.roles().add("PLATFORM_ADMIN")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "invalid", "1-1-1-1-1", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\n"})
    void rejectsMissingOrNonCanonicalTenant(String tenant) {
        assertForbidden(() -> Actor.from(jwt(tenant).build()));
    }

    @Test
    void rejectsNonStringTenantAndMissingSubject() {
        assertForbidden(() -> Actor.from(jwt(null).claim("tenant_id", 42).build()));
        assertForbidden(() -> Actor.from(jwt(TENANT.toString()).claims(claims -> claims.remove("sub")).build()));
        assertForbidden(() -> Actor.from(null));
    }

    @Test
    void malformedRolesNeverGrantPrivileges() {
        Actor actor = Actor.from(jwt(TENANT.toString()).claim("realm_access",
                Map.of("roles", List.of(42, Map.of("name", "CUSTOMER"), "CUSTOMER\n"))).build());
        assertThat(actor.roles()).isEmpty();
        assertForbidden(() -> actor.requireRole("CUSTOMER"));
        assertForbidden(actor::requireRole);
    }

    @Test
    void supportAndPlatformRolesHaveNoImplicitBusinessPrivilege() {
        Actor actor = new Actor(TENANT, "operator", Set.of("PLATFORM_ADMIN", "SUPPORT"));
        assertForbidden(() -> actor.requireRole("CUSTOMER", "MERCHANT_ADMIN"));
    }

    private static Jwt.Builder jwt(String tenant) {
        Jwt.Builder builder = Jwt.withTokenValue("unit-test-token").header("alg", "RS256").subject("customer-a");
        if (tenant != null) {
            builder.claim("tenant_id", tenant);
        }
        return builder;
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status()).isEqualTo(403));
    }
}
