package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.config.LlmConfig;
import com.choucj.aiaggregator.common.config.LlmProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.3a {@link LangChain4jLlmClient} 集成测试 — 调用真实 DeepSeek / GLM API.
 *
 * <p>仅当环境变量 {@code LLM_INTEGRATION_TEST=true} 且 {@code LLM_DEEPSEEK_API_KEY} /
 * {@code LLM_GLM_API_KEY} 均配置时运行, 否则 {@link BeforeAll} {@code assumeTrue} 跳过.
 * 默认在 CI / 未配置 API key 的开发机上跳过, 避免产生真实 API 调用费用.
 */
class LlmIntegrationTest {

    @BeforeAll
    static void requireApiKeysConfigured() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("LLM_INTEGRATION_TEST"));
        boolean hasKeys = System.getenv("LLM_DEEPSEEK_API_KEY") != null
                && System.getenv("LLM_GLM_API_KEY") != null;
        Assumptions.assumeTrue(enabled && hasKeys,
                "跳过: 未设置 LLM_INTEGRATION_TEST=true 或未配置 LLM_DEEPSEEK_API_KEY / LLM_GLM_API_KEY");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, LangChain4jLlmClient.class, TestConfig.class)
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of(
                            "llm.deepseek.api-key", System.getenv("LLM_DEEPSEEK_API_KEY"),
                            "llm.glm.api-key", System.getenv("LLM_GLM_API_KEY")))));

    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldCallRealDeepSeekApi() {
        runner.run(ctx -> {
            LlmClient client = ctx.getBean(LlmClient.class);
            String response = client.chat("回复一个字: 好");
            assertThat(response).isNotBlank();
            System.out.printf("DeepSeek 集成测试响应: %s%n", response);
        });
    }

    @Test
    void shouldCallRealGlmApi() {
        runner.run(ctx -> {
            LlmClient client = ctx.getBean(LlmClient.class);
            String response = client.chatWithModel("glm", "回复一个字: 好");
            assertThat(response).isNotBlank();
            System.out.printf("GLM 集成测试响应: %s%n", response);
        });
    }
}
