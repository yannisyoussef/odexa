package commerce.runtime;

import java.util.UUID;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class RuntimeWebConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, UUID.class, value -> {
            // UUID.fromString alone also accepts abbreviated forms such as 1-1-1-1-1.
            if (!Correlation.isUuid(value)) {
                throw new IllegalArgumentException("A canonical UUID is required");
            }
            return UUID.fromString(value);
        });
    }
}
