package com.choucj.aiaggregator.content.embedding;

import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.data.segment.TextSegment;

import java.util.concurrent.CompletableFuture;

/**
 * EmbeddingService 生成并存储文章向量，为 RAG 语义检索提供基础能力.
 *
 * <p>Story 5.1 将 GLM embedding-3 和 standalone Redis-Stack 隔离为可选质量增强层；
 * 调用方无需直接感知 LangChain4j 或 RediSearch 细节。
 */
public interface EmbeddingService {

    /**
     * 为文本生成向量.
     *
     * @param text 需要 embedding 的文本，不为 blank
     * @return embedding 向量
     */
    float[] embedText(String text);

    /**
     * 为指定文章文本生成向量，并在日志中携带 articleId 便于追踪.
     *
     * @param articleId 文章 ID
     * @param text      需要 embedding 的文本
     * @return embedding 向量
     */
    float[] embedText(String articleId, String text);

    /**
     * 异步为文本生成向量，用于历史文章批量处理.
     *
     * @param text 需要 embedding 的文本
     * @return embedding 向量 future
     */
    CompletableFuture<float[]> embedTextAsync(String text);

    /**
     * 异步为指定文章文本生成向量.
     *
     * @param articleId 文章 ID
     * @param text      需要 embedding 的文本
     * @return embedding 向量 future
     */
    CompletableFuture<float[]> embedTextAsync(String articleId, String text);

    /**
     * 将文章向量写入 RediSearch.
     *
     * @param articleId 文章 ID
     * @param text      原始文本摘要
     * @param vector    embedding 向量
     */
    void storeEmbedding(String articleId, String text, float[] vector);

    /**
     * 按向量检索相似文本片段，并使用 LangChain4j 对外归一化后的 similarity 分数过滤结果.
     *
     * <p>Redis RediSearch 原始 KNN 返回值是 COSINE distance；LangChain4j
     * {@code RedisEmbeddingStore} 会转换为 {@code (2 - distance) / 2} 后再与
     * {@code minScore} 比较。因此调用方传入的是 similarity 阈值，不是 raw distance 阈值。
     *
     * @param vector     查询向量
     * @param maxResults 最大返回数量
     * @param minScore   最小相似度，范围 [0, 1]
     * @return 检索结果
     */
    EmbeddingSearchResult<TextSegment> searchSimilar(float[] vector, int maxResults, double minScore);
}
