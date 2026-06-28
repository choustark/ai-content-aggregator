package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmProperties} 绑定 + 校验测试, 同时覆盖 DeepSeek 与 GLM 两个子配置.
 */
class LlmPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of(
                            "llm.deepseek.api-key", "ds-key",
                            "llm.deepseek.base-url", "https://ds.example.com",
                            "llm.glm.api-key", "glm-key",
                            "llm.glm.base-url", "https://glm.example.com"))));

    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldBindBothModelsWhenConfigured() {
        runner.run(ctx -> {
            LlmProperties props = ctx.getBean(LlmProperties.class);
            assertThat(props.getDeepseek().getApiKey()).isEqualTo("ds-key");
            assertThat(props.getDeepseek().getBaseUrl()).isEqualTo("https://ds.example.com");
            assertThat(props.getGlm().getApiKey()).isEqualTo("glm-key");
            assertThat(props.getGlm().getBaseUrl()).isEqualTo("https://glm.example.com");
        });
    }

    @Test
    void shouldApplyDefaultBaseUrlWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "llm.deepseek.api-key", "ds-key",
                                "llm.glm.api-key", "glm-key"))))
                .run(ctx -> {
                    LlmProperties props = ctx.getBean(LlmProperties.class);
                    assertThat(props.getDeepseek().getBaseUrl()).isEqualTo("https://api.deepseek.com");
                    assertThat(props.getGlm().getBaseUrl()).isEqualTo("https://open.bigmodel.cn");
                });
    }

    @Test
    void shouldFailToStartWhenDeepSeekKeyMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "llm.glm.api-key", "glm-key"))))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
