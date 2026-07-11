package com.choucj.aiaggregator.publish;

import com.choucj.aiaggregator.common.model.Article;

/**
 * 内容发布器抽象接口 — 发布渠道扩展点(PRD FR8, NFR14).
 *
 * <p>实现类(由 Spring 自动收集到 {@code List<ContentPublisher>} 注入到 {@code TwitterProcessor}
 * / 未来 {@code GitHubProcessor}):
 * <ul>
 *   <li>{@code PublishingModeDecider} — Story 3.4, 微信公众号实时/批量发布决策入口</li>
 *   <li>{@code MarkdownArchiver} — Story 2.5, Markdown 文件归档(语义虽不同, 但可视为
 *       "发布到文件系统", 复用此接口避免 Pipeline 编排分支)</li>
 * </ul>
 *
 * <p>多发布器并联:Pipeline(Story 2.6)可能同时调用多个 {@code ContentPublisher},
 * 实现 "改写一次, 多渠道发布".
 *
 * <p><b>delegate 模式警示(Story 3.4 架构演进, retro-3 C2):</b>
 * 具体平台 Publisher(如 {@code WeChatPublisher})<b>不直接实现</b>本接口, 而是作为
 * {@code PublishingModeDecider} 的 delegate 被调用. 这样设计的两个原因:
 * <ol>
 *   <li>防微信双发: 若 {@code WeChatPublisher} 同时实现 {@code ContentPublisher},
 *       {@code TwitterProcessor} 会既调 raw {@code WeChatPublisher} 又调
 *       {@code PublishingModeDecider} 分流, 造成重复草稿.</li>
 *   <li>防绕过批量入队: 新数据源(Epic 4 {@code GitHubProcessor})若直接持有平台 Publisher,
 *       会绕过 {@code PublishingModeDecider} 的实时/批量分流逻辑.</li>
 * </ol>
 * 复用 {@code List<ContentPublisher>} 模式时(Epic 4+), 平台 Publisher 同样应走 delegate 路径,
 * 不直接实现本接口.
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
