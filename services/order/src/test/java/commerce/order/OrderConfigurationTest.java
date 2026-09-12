package commerce.order;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

class OrderConfigurationTest {
    @Test
    void orderDefaultsOverrideImportedRuntimeDefaults() {
        MockEnvironment environment = configuration();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertEquals("order", environment.getProperty("spring.application.name"));
        assertEquals("8083", environment.getProperty("server.port"));
        assertEquals("order-v1", environment.getProperty("spring.kafka.consumer.group-id"));
        assertEquals("always", environment.getProperty("spring.sql.init.mode"));
        assertEquals("classpath:runtime-schema.sql,classpath:schema.sql",
                environment.getProperty("spring.sql.init.schema-locations"));
        assertEquals("http://localhost:8081", environment.getProperty("order.catalog.url"));
        assertEquals("2s", environment.getProperty("order.catalog.connect-timeout"));
        assertEquals("3s", environment.getProperty("order.catalog.read-timeout"));
        // The runtime import is still active, rather than being replaced by service defaults.
        assertEquals("never", environment.getProperty("server.error.include-message"));
    }

    @Test
    void externalOverridesStillWinOverOrderDefaults() {
        MockEnvironment environment = configuration()
                .withProperty("SERVER_PORT", "9083")
                .withProperty("spring.kafka.consumer.group-id", "order-custom");
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertEquals("9083", environment.getProperty("server.port"));
        assertEquals("order-custom", environment.getProperty("spring.kafka.consumer.group-id"));
    }

    private static MockEnvironment configuration() {
        // Isolated from machine environment; loads real imports without starting DB/Kafka beans.
        return new MockEnvironment().withProperty("spring.config.location", "classpath:application.properties");
    }
}
