package com.choucj.aiaggregator.content.rag.service;

import com.choucj.aiaggregator.content.rag.model.ReferenceArticle;

import java.util.List;

/**
 * 检索与当前内容相似的历史参考文章，为 RAG 改写提供可选质量增强上下文.
 *
 * <p>引用源: Story 5.2 创建。检索失败由实现类降级为空列表，避免可选 RAG 层阻断主改写流程。
 */
public interface ReferenceRetriever {

    /**
     * 根据当前内容生成查询向量并检索历史参考文章，因为后续改写需要稳定、相似且可降级的上下文。
     *
     * <p>{@code sourceId} 使用规范文章 ID: Twitter 为 {@code tw-{tweetId}},
     * GitHub 为 {@code gh-{normalizedOwnerRepo}}。实现必须排除与该 ID 精确相等的历史 embedding，
     * 防止当前文章自身作为参考注入 prompt。
     *
     * @param sourceId 当前内容的稳定业务标识，用于 embedding 查询、安全日志定位和 self-exclusion
     * @param text 当前内容的可 embedding 文本，不允许为空白
     * @return 按 similarity 从高到低排序的参考文章列表，检索失败时返回空列表
     * @throws IllegalArgumentException 当 sourceId 或 text 为空白时抛出，表示调用方参数错误
     */
    List<ReferenceArticle> retrieveReferences(String sourceId, String text);
}
