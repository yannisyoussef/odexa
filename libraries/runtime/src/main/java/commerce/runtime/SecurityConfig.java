package commerce.runtime;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8180/realms/odexa}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:http://localhost:8180/realms/odexa/protocol/openid-connect/certs}") String jwks) {
        // JWK backchannel is independently configurable; it does NOT replace issuer validation.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(tokenValidator(issuer));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> audiences = jwt.getAudience();
            return jwt.getExpiresAt() != null && audiences != null && audiences.contains("odexa-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            "The token is not valid for this API.", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> Actor.realmRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(org.springframework.security.core.GrantedAuthority.class::cast).toList());
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
            JwtAuthenticationConverter converter, ObjectMapper mapper) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator", "/actuator/**").denyAll()
                        .requestMatchers("/api/**").access((authentication, context) -> {
                            var auth = authentication.get();
                            if (!auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
                                return new AuthorizationDecision(false);
                            }
                            try {
                                Actor.from(jwt);
                                return new AuthorizationDecision(true);
                            } catch (ApiException exception) {
                                return new AuthorizationDecision(false);
                            }
                        })
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            ProblemResponses.write(response, mapper, 401, "UNAUTHORIZED", "Authentication is required.");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                ProblemResponses.write(response, mapper, 403, "FORBIDDEN", "Access is not permitted.")))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            ProblemResponses.write(response, mapper, 401, "UNAUTHORIZED", "Authentication is required.");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                ProblemResponses.write(response, mapper, 403, "FORBIDDEN", "Access is not permitted."))
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
