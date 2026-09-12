package commerce.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {
    private static final String ISSUER = "https://identity.odexa.cc/realms/odexa";

    @Test
    void requiresBothExactIssuerAndAudience() {
        var validator = SecurityConfig.tokenValidator(ISSUER);
        assertThat(validator.validate(token(ISSUER, "odexa-api").build()).hasErrors()).isFalse();
        assertThat(validator.validate(token("https://untrusted.example/realms/odexa", "odexa-api").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "other-api").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "odexa-api.evil").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "odexa-api").claims(claims -> claims.remove("aud")).build()).hasErrors()).isTrue();
    }

    @Test
    void validatesExpirationAndNotBefore() {
        var validator = SecurityConfig.tokenValidator(ISSUER);
        assertThat(validator.validate(token(ISSUER, "odexa-api")
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build()).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "odexa-api")
                .notBefore(Instant.now().plusSeconds(3600)).build()).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "odexa-api")
                .claims(claims -> claims.remove("exp")).build()).hasErrors()).isTrue();
    }

    @Test
    void mapsOnlyRealmRolesToSpringAuthorities() {
        Jwt jwt = token(ISSUER, "odexa-api")
                .claim("scope", "PLATFORM_ADMIN")
                .claim("resource_access", Map.of("client", Map.of("roles", List.of("SUPPORT"))))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", "MERCHANT_USER"))).build();
        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_MERCHANT_USER", "FACTOR_BEARER");
    }

    private static Jwt.Builder token(String issuer, String audience) {
        return Jwt.withTokenValue("unit-test-token").header("alg", "RS256")
                .issuer(issuer).subject("customer-a").audience(List.of(audience))
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(600));
    }
}
