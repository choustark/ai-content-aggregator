package com.choucj.aiaggregator.content.rag.model;

import lombok.Builder;

/**
 * 承载历史向量库检索出的参考文章摘要，因为 RAG 改写只需要不可变、可安全拼接的上下文字段.
 *
 * @param articleId 历史文章稳定 ID，不允许为空白
 * @param title 历史文章标题；源文本缺失标题时为 {@code (无标题)}
 * @param summary 历史文章摘要；源文本缺失摘要时为 {@code (无摘要)}
 * @param styleFeatures 轻量风格特征；源文本缺失风格时为 {@code (无风格特征)}
 * @param similarityScore similarity 分数，范围为 {@code [0, 1]}
 */
@Builder
public record ReferenceArticle(
        String articleId,
        String title,
        String summary,
        String styleFeatures,
        double similarityScore) {

    public ReferenceArticle {
        if (isBlank(articleId)) {
            throw new IllegalArgumentException("articleId must not be blank");
        }
        if (!Double.isFinite(similarityScore) || similarityScore < 0.0 || similarityScore > 1.0) {
            throw new IllegalArgumentException("similarityScore must be finite and between 0.0 and 1.0");
        }
        title = defaultIfBlank(title, "(无标题)");
        summary = defaultIfBlank(summary, "(无摘要)");
        styleFeatures = defaultIfBlank(styleFeatures, "(无风格特征)");
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
