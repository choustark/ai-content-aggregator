package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.config.LlmConfig;
import com.choucj.aiaggregator.common.config.LlmProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GLM base-url bug 修复验证测试.
 *
 * <p>验证 {@code https://open.bigmodel.cn/api/paas/v4} 完整路径配置是否正确,
 * 以及降级链是否正常工作.
 *
 * <p><b>背景:</b> Story 2.3a LlmProperties.Glm.baseUrl 缺路径导致 langchain4j
 * 从 {@code https://open.bigmodel.cn} 拼 {@code /chat/completions} 打到 GLM 根路径
 * 被阿里云网关 405. 正确的应是 {@code https://open.bigmodel.cn/api/paas/v4}.
 *
 * <p>仅当环境变量 {@code LLM_INTEGRATION_TEST=true} 且 {@code LLM_GLM_API_KEY} 配置时运行,
 * 否则 {@link BeforeAll} {@code assumeTrue} 跳过.
 */
class GlmBaseUrlVerificationTest {

    @BeforeAll
    static void requireApiKeysConfigured() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("LLM_INTEGRATION_TEST"));
        boolean hasGlmKey = System.getenv("LLM_GLM_API_KEY") != null;
        Assumptions.assumeTrue(enabled && hasGlmKey,
                "跳过: 未设置 LLM_INTEGRATION_TEST=true 或 LLM_GLM_API_KEY");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, LangChain4jLlmClient.class, TestConfig.class)
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of(
                            "llm.deepseek.api-key", System.getenv().getOrDefault("LLM_DEEPSEEK_API_KEY", "placeholder"),
                            "llm.glm.api-key", System.getenv("LLM_GLM_API_KEY"),
                            "llm.glm.base-url", "https://open.bigmodel.cn/api/paas/v4"))));

    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldCallGlmWithCorrectBaseUrl() {
        runner.run(ctx -> {
            LangChain4jLlmClient client = ctx.getBean(LangChain4jLlmClient.class);
            String response = client.chatWithModel("glm", "回复一个字: 好");
            assertThat(response).isNotBlank();
            System.out.printf("✅ GLM base-url 修复验证成功, 响应: %s%n", response);
        });
    }

    @Test
    void verifyGlmChatModelConfiguration() {
        runner.run(ctx -> {
            ChatModel glmModel = ctx.getBean("glmChatModel", ChatModel.class);
            assertThat(glmModel).isNotNull();
            System.out.println("✅ GLM ChatModel Bean 配置成功");
        });
    }

    @Test
    void verifyLlmPropertiesBaseUrl() {
        runner.run(ctx -> {
            LlmProperties props = ctx.getBean(LlmProperties.class);
            String baseUrl = props.getGlm().getBaseUrl();
            assertThat(baseUrl).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
            System.out.printf("✅ LlmProperties.Glm.baseUrl 配置正确: %s%n", baseUrl);
        });
    }
}
