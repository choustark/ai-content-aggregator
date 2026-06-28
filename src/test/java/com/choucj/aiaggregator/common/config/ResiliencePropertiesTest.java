package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResilienceProperties} 绑定 + 默认值测试.
 */
class ResiliencePropertiesTest {

    @EnableConfigurationProperties(ResilienceProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenNoConfig() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    ResilienceProperties props = ctx.getBean(ResilienceProperties.class);
                    assertThat(props.getRetry().getMaxAttempts()).isEqualTo(3);
                    assertThat(props.getRetry().getBackoffMillis()).isEqualTo(1000L);
                    assertThat(props.getTimeout().getHttpMillis()).isEqualTo(10000L);
                    assertThat(props.getTimeout().getRedisMillis()).isEqualTo(3000L);
                    assertThat(props.getTimeout().getLlmMillis()).isEqualTo(60000L);
                    assertThat(props.getDegradation().isEnabled()).isTrue();
                    assertThat(props.getDegradation().isFallbackToDefault()).isTrue();
                });
    }

    @Test
    void shouldBindCustomValuesWhenProvided() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "resilience.retry.max-attempts", "5",
                                "resilience.retry.backoff-millis", "2000",
                                "resilience.timeout.llm-millis", "120000",
                                "resilience.degradation.enabled", "false"))))
                .run(ctx -> {
                    ResilienceProperties props = ctx.getBean(ResilienceProperties.class);
                    assertThat(props.getRetry().getMaxAttempts()).isEqualTo(5);
                    assertThat(props.getRetry().getBackoffMillis()).isEqualTo(2000L);
                    assertThat(props.getTimeout().getLlmMillis()).isEqualTo(120000L);
                    assertThat(props.getDegradation().isEnabled()).isFalse();
                });
    }
}
