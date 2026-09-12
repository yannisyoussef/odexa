package commerce.runtime;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationTest {
    @Test
    void catalogCanDisableAllRuntimeEventAndJdbcComponentsWithoutKafkaOrDatasourceBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(EventsConfig.class, Outbox.class, Inbox.class, OutboxPublisher.class)
                .withPropertyValues("runtime.events.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EventsConfig.class).doesNotHaveBean(Outbox.class)
                            .doesNotHaveBean(Inbox.class).doesNotHaveBean(OutboxPublisher.class);
                });
    }

    @Test
    void defaultsRequireExternalDatabasePasswordAndDurableKafkaSettings() throws Exception {
        Properties defaults = new Properties();
        try (InputStream resource = getClass().getResourceAsStream("/runtime-defaults.properties")) {
            assertThat(resource).isNotNull();
            defaults.load(resource);
        }
        assertThat(defaults.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(defaults.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
        assertThat(defaults.getProperty("spring.kafka.producer.properties.enable.idempotence")).isEqualTo("true");
        assertThat(defaults.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo("false");
        assertThat(defaults.getProperty("spring.kafka.consumer.auto-offset-reset")).isEqualTo("earliest");
        assertThat(defaults.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("record");
        assertThat(defaults.getProperty("spring.sql.init.schema-locations"))
                .isEqualTo("classpath:runtime-schema.sql,classpath:schema.sql");
        assertThat(defaults.getProperty("spring.kafka.admin.auto-create")).isEqualTo("${runtime.events.enabled:true}");
        assertThat(defaults.getProperty("spring.kafka.listener.auto-startup")).isEqualTo("${runtime.events.enabled:true}");
    }
}
