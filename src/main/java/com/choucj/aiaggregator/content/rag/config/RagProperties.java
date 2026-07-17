package com.choucj.aiaggregator.content.rag.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 参考检索配置 (Story 5.2).
 */
@ConfigurationProperties(prefix = "features.rag")
@Validated
@Data
public class RagProperties {

    /**
     * RAG 总开关. 默认关闭, 开启后注册 ReferenceRetriever.
     */
    private boolean enabled = false;

    /**
     * 历史文章相似度阈值. 直接传递给 EmbeddingService.searchSimilar 的 minScore.
     */
    @DecimalMin(value = "0.0", message = "features.rag.similarity-threshold must be >= 0.0")
    @DecimalMax(value = "1.0", message = "features.rag.similarity-threshold must be <= 1.0")
    private double similarityThreshold = 0.75;

    /**
     * 返回参考文章数量上限. 上限 10 用于约束 prompt 长度与 LLM 成本.
     */
    @Min(value = 1, message = "features.rag.max-references must be >= 1")
    @Max(value = 10, message = "features.rag.max-references must be <= 10")
    private int maxReferences = 3;

    @AssertTrue(message = "similarityThreshold must be finite")
    public boolean isSimilarityThresholdFinite() {
        return Double.isFinite(similarityThreshold);
    }
}
