package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证主配置模板提供有限额的本地滚动日志，以支持连续数日运行时回溯排障。
 */
class LocalLoggingConfigurationTest {

    @Test
    void should_configure_bounded_rolling_file_when_application_template_is_loaded() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application.example", new ClassPathResource("application.example.yml"));
        PropertySource<?> source = sources.getFirst();

        assertThat(source.getProperty("logging.file.name"))
                .isEqualTo("logs/ai-content-aggregator.log");
        assertThat(source.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .isEqualTo("logs/ai-content-aggregator.%d{yyyy-MM-dd}.%i.log.gz");
        assertThat(source.getProperty("logging.logback.rollingpolicy.max-file-size"))
                .isEqualTo("20MB");
        assertThat(source.getProperty("logging.logback.rollingpolicy.max-history"))
                .isEqualTo(14);
        assertThat(source.getProperty("logging.logback.rollingpolicy.total-size-cap"))
                .isEqualTo("1GB");
        assertThat(source.getProperty("logging.structured.format.console")).isNull();
        assertThat(source.getProperty("logging.structured.format.file")).isNull();
    }

    @Test
    void should_disable_local_file_appender_when_prod_configuration_is_loaded() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application-prod", new ClassPathResource("application-prod.yml"));

        assertThat(sources.getFirst().getProperty("logging.file.name")).isEqualTo("");
        assertThat(sources.getFirst().getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }
}
