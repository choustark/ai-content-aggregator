package com.choucj.aiaggregator.publish;

import com.choucj.aiaggregator.common.model.Article;

/**
 * 内容发布器抽象接口 — 发布渠道扩展点(PRD FR8, NFR14).
 *
 * <p>实现类:
 * <ul>
 *   <li>{@code WeChatPublisher} — Story 3.3, 微信公众号草稿发布(调用 WxMpService)</li>
 *   <li>{@code MarkdownArchiver} — Story 2.5, Markdown 文件归档(语义虽不同, 但可视为
 *       "发布到文件系统", 复用此接口避免 Pipeline 编排分支)</li>
 * </ul>
 *
 * <p>多发布器并联:Pipeline(Story 2.6)可能同时调用多个 {@code ContentPublisher},
 * 实现 "改写一次, 多渠道发布".
 */
public interface ContentPublisher {

    /**
     * 发布文章到目标渠道.
     *
     * <p>实现要求:
     * <ul>
     *   <li>幂等:同一 {@link Article} 重复调用不应产生重复内容(如微信草稿以 title 去重)</li>
     *   <li>失败抛出 {@code RetryableException} / {@code NonRetryableException}(Story 1.4),
     *       由 Pipeline 决定重试或降级</li>
     *   <li>合规:若 {@link Article#isAiGenerated()} 为 {@code true}, 需在发布内容中追加
     *       AI 辅助生成声明(合规要求 AR8)</li>
     * </ul>
     *
     * @param article 待发布文章(不应为 {@code null})
     */
    void publish(Article article);
}
