package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.source.twitter.model.Tweet;

/**
 * 内容改写器抽象接口 — AI 改写扩展点(PRD FR4, NFR15).
 *
 * <p>实现类:
 * <ul>
 *   <li>{@code SingleModelRewriter} — Story 2.4, DeepSeek 单模型改写(MVP 默认)</li>
 *   <li>{@code MultiModelRewriter} — Story 5.4, 多模型投票(提升质量)</li>
 *   <li>{@code RagEnhancedRewriter} — Story 5.3, RAG 增强(检索相关知识后改写)</li>
 * </ul>
 *
 * <p><b>签名设计:</b>
 * 当前 PRD 只有 Twitter → Article 一条改写路径. 若 Story 4.4 GitHub 集成时需要直接改写
 * {@code GitHubRepo}, 可在此接口添加 {@code default Article rewrite(GitHubRepo repo)}
 * 方法并默认抛出 {@code UnsupportedOperationException}, 由需要的实现类覆盖.
 */
public interface ContentRewriter {

    /**
     * 将推文改写为可发布的文章.
     *
     * <p>改写流程(由实现类完成):
     * <ol>
     *   <li>从 {@link Tweet#getContent()} / {@link Tweet#getSummary()} 提取源文本</li>
     *   <li>调用 LLM(DeepSeek / GLM)生成 Markdown 正文 + 标题 + 摘要</li>
     *   <li>填充 {@link Article} 的 {@code aiGenerated=true} 标识(合规要求 AR8)</li>
     *   <li>记录 {@code innovationScore}(若由 InnovationFilter 传递, 否则默认 0)</li>
     * </ol>
     *
     * @param tweet 源推文(不应为 {@code null})
     * @return 改写后的 Article(包含 UUID / 标题 / 正文 / digest / source / 时间戳)
     */
    Article rewrite(Tweet tweet);
}
