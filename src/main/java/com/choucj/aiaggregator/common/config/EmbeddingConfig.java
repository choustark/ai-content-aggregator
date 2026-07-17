package com.choucj.aiaggregator.common.config;

import com.choucj.aiaggregator.content.embedding.EmbeddingServiceImpl;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Story 5.1 Embedding 配置 — 仅在 RAG 开启时注册 Embedding 模型.
 *
 * <p>本类独立于 {@link LlmConfig}, 因为 ChatModel 与 EmbeddingModel 生命周期、开关和 Redis
 * 依赖不同。GLM embedding-3 走 OpenAI 兼容协议；Redis/Jedis 基础设施由
 * {@link RagEmbeddingInfrastructureConfig} 独立管理，保持 AR4 单一职责。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
@ConditionalOnProperty(prefix = "features.rag", name = "enabled", havingValue = "true")
@Import({EmbeddingServiceImpl.class, RagEmbeddingInfrastructureConfig.class})
public class EmbeddingConfig {

    private static final String MODEL_NAME = "langchain4j.open-ai.embedding-model.model-name";
    private static final String DIMENSIONS = "langchain4j.open-ai.embedding-model.dimensions";
    private static final String TIMEOUT = "langchain4j.open-ai.embedding-model.timeout";

    /**
     * GLM embedding-3 模型 Bean.
     *
     * @param properties LLM 密钥配置
     * @param env        Spring 环境配置
     * @return OpenAI 兼容 EmbeddingModel
     */
    @Bean(name = "embeddingModel")
    @Qualifier("embeddingModel")
    public EmbeddingModel embeddingModel(LlmProperties properties, Environment env) {
        LlmProperties.Glm glm = properties.getGlm();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(glm.getBaseUrl())
                .apiKey(glm.getApiKey())
                .modelName(env.getProperty(MODEL_NAME, "embedding-3"))
                .dimensions(env.getProperty(DIMENSIONS, Integer.class, 1024))
                .timeout(parseDuration(env.getProperty(TIMEOUT), Duration.ofSeconds(60)))
                .build();
    }

    private static Duration parseDuration(String raw, Duration defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return DurationStyle.detectAndParse(raw);
    }
}
