package com.choucj.aiaggregator.common.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j LLM 模型配置.
 *
 * <p>注册两个 {@link ChatModel} Bean:
 * <ul>
 *   <li>{@code deepSeekChatModel} — 主路径, DeepSeek 兼容 OpenAI API (modelName 可配置, 默认 {@code deepseek-chat})</li>
 *   <li>{@code glmChatModel} — 备用路径, 智谱 GLM 兼容 OpenAI API (modelName 可配置, 默认 {@code glm-4.7})</li>
 * </ul>
 *
 * <p>调用方 (InnovationFilter / ContentRewriter) 通过 {@link Qualifier} 区分注入.
 * {@link com.choucj.aiaggregator.common.client.LangChain4jLlmClient} 默认走 DeepSeek,
 * 失败后降级 GLM (单次 retry).
 *
 * <p>架构 delta (Story 2.3a): 沿用 LangChain4j 1.16.0 命名 ({@code ChatModel}, 1.x 起
 * {@code ChatLanguageModel} 已重命名为 {@code ChatModel}). 模型超时默认 60s (YAGNI, 不暴露到
 * {@link LlmProperties}).
 *
 * <p>安全约束: api-key 经 {@link LlmProperties} 注入 (来自 {@code api-keys.yml} / 环境变量),
 * 不在日志或异常 message 暴露. {@link LlmProperties} 的嵌套类已 {@code @ToString(exclude="apiKey")}.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * DeepSeek 主路径 ChatModel.
     *
     * @param properties LLM 配置 (deepseek.apiKey / deepseek.baseUrl / deepseek.modelName)
     * @return OpenAI 兼容的 DeepSeek ChatModel
     */
    @Bean(name = "deepSeekChatModel")
    @Qualifier("deepSeekChatModel")
    public ChatModel deepSeekChatModel(LlmProperties properties) {
        LlmProperties.DeepSeek deepseek = properties.getDeepseek();
        return OpenAiChatModel.builder()
                .baseUrl(deepseek.getBaseUrl())
                .apiKey(deepseek.getApiKey())
                .modelName(deepseek.getModelName())
                .timeout(DEFAULT_TIMEOUT)
                .build();
    }

    /**
     * GLM 备用路径 ChatModel.
     *
     * @param properties LLM 配置 (glm.apiKey / glm.baseUrl / glm.modelName)
     * @return OpenAI 兼容的 GLM ChatModel
     */
    @Bean(name = "glmChatModel")
    @Qualifier("glmChatModel")
    public ChatModel glmChatModel(LlmProperties properties) {
        LlmProperties.Glm glm = properties.getGlm();
        return OpenAiChatModel.builder()
                .baseUrl(glm.getBaseUrl())
                .apiKey(glm.getApiKey())
                .modelName(glm.getModelName())
                .timeout(DEFAULT_TIMEOUT)
                .build();
    }
}
