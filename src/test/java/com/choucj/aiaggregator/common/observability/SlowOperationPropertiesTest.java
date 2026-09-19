package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SlowOperationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void should_use_expected_defaults_when_properties_are_absent() {
        contextRunner.run(context -> {
            SlowOperationProperties properties = context.getBean(SlowOperationProperties.class);
            assertThat(properties.getRedisThreshold()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.getHttpThreshold()).isEqualTo(Duration.ofSeconds(2));
            // SDK 独立阈值是 Story 10.3 已登记的批准偏差, 默认 10s 必须被测试锁定防漂移.
            assertThat(properties.getSdkThreshold()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void should_bind_environment_style_values_when_properties_are_present() {
        contextRunner
                .withPropertyValues(
                        "observability.slow-operations.redis-threshold=250ms",
                        "observability.slow-operations.http-threshold=3s",
                        "observability.slow-operations.sdk-threshold=15s")
                .run(context -> {
                    SlowOperationProperties properties = context.getBean(SlowOperationProperties.class);
                    assertThat(properties.getRedisThreshold()).isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.getHttpThreshold()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getSdkThreshold()).isEqualTo(Duration.ofSeconds(15));
                });
    }

    @Test
    void should_reject_non_positive_threshold_when_context_starts() {
        contextRunner
                .withPropertyValues("observability.slow-operations.redis-threshold=0ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 失败必须来自阈值校验本身, 而非绑定类型错误等无关原因.
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("thresholds must be greater than zero");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlowOperationProperties.class)
    static class TestConfiguration {
    }
}
