package commerce.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class WebhookSecurity {
    @Bean @Order(0)
    SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/webhooks/stripe", "/api/v1/webhooks/simulator")
                .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r -> r.requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/webhooks/stripe", "/api/v1/webhooks/simulator").permitAll().anyRequest().denyAll()).build();
    }
}
