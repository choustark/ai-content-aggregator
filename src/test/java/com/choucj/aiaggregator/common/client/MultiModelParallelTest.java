package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.config.LlmConfig;
import com.choucj.aiaggregator.common.config.LlmProperties;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike 5.1 §4 — 多模型并行验证 (服务 Story 5.4 Multi-Model Voting).
 *
 * <p><b>当前实现: DeepSeek self-parallel</b> (同一 ChatModel bean 并发调用), 验证:
 * <ul>
 *   <li><b>H4</b>: 单个 {@code deepSeekChatModel} bean 多线程并发调用无线程安全问题
 *       (OpenAiChatModel 无状态 HTTP client, 并行调同一 bean 是 Story 5.4 投票的核心并发场景).</li>
 *   <li><b>H5</b>: 记录并行延迟与串行 baseline，用于人工观察真实网络收益，不作为 CI 硬断言.</li>
 * </ul>
 *
 * <p><b>GLM 暂跳过的原因 (副产品发现, 见 spike §4.4):</b>
 * ① GLM base-url 配置 bug — langchain4j 从 {@code https://open.bigmodel.cn} 拼
 * {@code /chat/completions} 打到 GLM 根路径被阿里云网关 405; 正确应是
 * {@code https://open.bigmodel.cn/api/paas/v4} (Story 2.3a LlmProperties.Glm.baseUrl 缺路径,
 * LlmIntegrationTest 从未真跑故未暴露, 需 Story 2.3a follow-up 修复);
 * ② GLM 新 key 429 限流. 待两项修复后补两模型投票测试.
 *
 * <p>仅当 {@code LLM_INTEGRATION_TEST=true} + {@code LLM_DEEPSEEK_API_KEY} 配置时运行, 否则跳过.
 */
@org.junit.jupiter.api.Tag("external")
class MultiModelParallelTest {

    @BeforeAll
    static void requireApiKeysConfigured() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("LLM_INTEGRATION_TEST"));
        boolean hasDeepSeek = System.getenv("LLM_DEEPSEEK_API_KEY") != null;
        Assumptions.assumeTrue(enabled && hasDeepSeek,
                "跳过: 未设置 LLM_INTEGRATION_TEST=true 或 LLM_DEEPSEEK_API_KEY");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class, TestConfig.class)
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of(
                            "llm.deepseek.api-key", System.getenv("LLM_DEEPSEEK_API_KEY"),
                            "llm.deepseek.base-url", "https://api.deepseek.com",
                            // GLM base-url bug 修复: 使用正确的完整路径 (含 /api/paas/v4)
                            "llm.glm.api-key", System.getenv().getOrDefault("LLM_GLM_API_KEY", "placeholder"),
                            "llm.glm.base-url", "https://open.bigmodel.cn/api/paas/v4"))));

    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    @Test
    void parallelVsSerialLatency() {
        runner.run(ctx -> {
            ChatModel ds = ctx.getBean("deepSeekChatModel", ChatModel.class);
            String prompt = "用一句话(不超过30字)介绍什么是 AI Agent";

            // 串行 baseline (同 bean 调两次)
            try {
                long s0 = System.nanoTime();
                String dsRes1 = ds.chat(prompt);
                long t1 = (System.nanoTime() - s0) / 1_000_000;
                long s1 = System.nanoTime();
                String dsRes2 = ds.chat(prompt);
                long t2 = (System.nanoTime() - s1) / 1_000_000;
                long serial = t1 + t2;

                // 并行 (同 bean, 两线程并发调 — H4 核心)
                ExecutorService exec = Executors.newFixedThreadPool(2);
                try {
                    long p0 = System.nanoTime();
                    Future<String> f1 = exec.submit(() -> ds.chat(prompt));
                    Future<String> f2 = exec.submit(() -> ds.chat(prompt));
                    String parRes1 = f1.get();
                    String parRes2 = f2.get();
                    long parallel = (System.nanoTime() - p0) / 1_000_000;

                    System.out.printf("%n=== H4/H5 并行验证 (DeepSeek self-parallel) ===%n");
                    System.out.printf("串行两次 RTT: %dms + %dms = %dms%n", t1, t2, serial);
                    System.out.printf("并行实测:     %dms  (理论 max=%dms)%n", parallel, Math.max(t1, t2));
                    System.out.printf("加速比: %.2fx, 并行省 %dms%n", serial * 1.0 / Math.max(parallel, 1), serial - parallel);
                    System.out.printf("响应1: %s%n", truncate(parRes1, 40));
                    System.out.printf("响应2: %s%n", truncate(parRes2, 40));

                    assertThat(parRes1).isNotBlank();
                    assertThat(parRes2).isNotBlank();
                    assertThat(parallel).isLessThanOrEqualTo(Math.max(serial * 2, serial + 1_000));
                    System.out.printf("%n>>> H4 ✅ 同 bean 并发调用无异常/无串扰%n");
                    System.out.printf(">>> H5 观测: 并行 %dms, 串行基线 %dms, 理论 max=%dms%n",
                            parallel, serial, Math.max(t1, t2));
                } finally {
                    exec.shutdown();
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnresolvedModelServerException) {
                    Assumptions.abort("跳过: 当前网络无法解析/连接 DeepSeek 模型服务");
                }
                throw e;
            } catch (UnresolvedModelServerException e) {
                Assumptions.abort("跳过: 当前网络无法解析/连接 DeepSeek 模型服务");
            }
        });
    }

    @Test
    void parallelStabilityMultipleRounds() {
        runner.run(ctx -> {
            ChatModel ds = ctx.getBean("deepSeekChatModel", ChatModel.class);
            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                int ok = 0;
                for (int i = 0; i < 5; i++) {
                    Future<String> f1 = exec.submit(() -> ds.chat("回复:ok"));
                    Future<String> f2 = exec.submit(() -> ds.chat("回复:ok"));
                    if (!f1.get().isBlank() && !f2.get().isBlank()) ok++;
                }
                System.out.printf("%n=== H4 多轮稳定性: %d/5 轮并发成功 ===%n", ok);
                assertThat(ok).isEqualTo(5);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof UnresolvedModelServerException) {
                    Assumptions.abort("跳过: 当前网络无法解析/连接 DeepSeek 模型服务");
                }
                throw e;
            } finally {
                exec.shutdown();
            }
        });
    }

    private static String truncate(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
