package com.choucj.aiaggregator.common.config;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.3a {@link LlmConfig} 测试 — 验证两个 {@link ChatModel} Bean 创建 + 缺 api-key 启动失败.
 */
class LlmConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, TestConfig.class)
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
    void shouldRegisterDeepSeekChatModelBean() {
        runner.run(ctx -> {
            ChatModel model = ctx.getBean("deepSeekChatModel", ChatModel.class);
            assertThat(model).isNotNull();
        });
    }

    @Test
    void shouldRegisterGlmChatModelBean() {
        runner.run(ctx -> {
            ChatModel model = ctx.getBean("glmChatModel", ChatModel.class);
            assertThat(model).isNotNull();
        });
    }

    @Test
    void shouldFailToStartWhenDeepSeekKeyMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(LlmConfig.class, TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "llm.glm.api-key", "glm-key"))))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shouldFailToStartWhenGlmKeyMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(LlmConfig.class, TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "llm.deepseek.api-key", "ds-key"))))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
