package com.choucj.aiaggregator.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 改写后的文章模型 — 跨模块核心载体.
 *
 * <p>引用源:
 * <ul>
 *   <li>Story 2.4 — {@code ContentRewriter} 输出</li>
 *   <li>Story 2.5 — {@code MarkdownArchiver} 输入(归档到本地文件)</li>
 *   <li>Story 3.2 — {@code ArticleConverter} 输入(转换为微信草稿 DTO)</li>
 *   <li>Story 3.3 — {@code WeChatPublisher} 输入(发布到微信草稿箱)</li>
 *   <li>Story 3.5 — 状态查询的返回类型</li>
 * </ul>
 *
 * <p>合规要求 AR8: AI 生成内容必须通过 {@link #aiGenerated} 字段标识,
 * 微信草稿发布时需在文末添加 "本文由 AI 辅助生成" 声明。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article {

    /** 文章 ID(由 Rewriter 基于源内容生成的确定性标识, 例如 {@code tw-{tweetId}}). */
    private String id;

    /** 标题. */
    private String title;

    /**
     * 正文(三模式语义, Story 8.6 D-A + Story 9.1 扩展):
     * <ul>
     *   <li>{@code generationMode=REWRITE} — Markdown 格式 (ContentRewriter 产出)</li>
     *   <li>{@code generationMode=PRESERVE_ORIGINAL} — 已转义的安全 HTML
     *       (OriginalPostRenderer 产出, 含 footer; 发布侧 ArticleToWxArticleConverter
     *       按本字段分流, 跳过 commonmark 二次渲染)</li>
     *   <li>{@code generationMode=REWRITE_WITH_MEDIA} — Markdown 格式:
     *       LLM 改写正文 + MarkdownMediaInserter 追加的媒体 Markdown image syntax
     *       (URL 仅来自 sidecar wechatUrl; Story 9.1 AD-3/AD-12)</li>
     * </ul>
     */
    private String content;

    /** 摘要(微信草稿摘要字段, 限制 120 字). */
    private String digest;

    /** 来源标注({@code "来源:@username"} 或 {@code "GitHub Repo:owner/repo"}). */
    private String source;

    /**
     * AI 生成标识(合规要求 AR8).
     *
     * <p>本系统所有文章均由 AI 改写生成, 因此安全默认值为 {@code true};
     * 仅在极少数非 AI 内容(如人工编辑补丁)场景下显式设为 {@code false}.
     * 此默认值由 {@link lombok.Builder.Default} 保证, 无论通过 {@code new Article()} 还是
     * {@code Article.builder().build()} 构造, 均得到 {@code true}.
     */
    @Builder.Default
    private boolean aiGenerated = true;

    /** 创建时间. */
    private LocalDateTime createdAt;

    /** AI 创新评分(0=未评分, 1-10 有效值, Story 2.3 输出, 用于筛选高质量内容). */
    private int innovationScore;

    /** 原始推文 / 仓库链接. */
    private String originalUrl;

    /**
     * 内容生成模式 (Story 8.6 D-A + Story 9.1 AD-1 扩展).
     *
     * <p>{@code REWRITE} = AI 改写 (默认, content 为 Markdown, 无媒体副作用);
     * {@code PRESERVE_ORIGINAL} = 原帖复现 (content 为已转义安全 HTML, renderer 产出);
     * {@code REWRITE_WITH_MEDIA} = AI 改写 + 原帖 PHOTO 媒体嵌入 (content 为
     * LLM Markdown 正文 + 媒体 Markdown, aiGenerated 恒为 {@code true})。
     *
     * <p><b>批量队列模式感知根因:</b> PublishingModeDecider → Redis JSON 队列 →
     * BatchPublishingScheduler → WeChatPublisher 反序列化后只剩 Article (无 Tweet/模式上下文),
     * 不加字段则批量路径永远无法感知生成模式。
     *
     * <p><b>旧 JSON 兼容:</b> 历史 Redis Article JSON 无本字段 → Jackson 走 noargs 构造 +
     * setter, field initializer 生效 → REWRITE, 向后兼容 (Story 9.1 AC1 保持)。
     * {@code @Builder.Default} + field initializer 双路径保证: 无论 {@code new Article()}
     * 还是 {@code Article.builder().build()}, 默认均为 REWRITE。
     */
    @Builder.Default
    private ContentGenerationMode generationMode = ContentGenerationMode.REWRITE;

    /**
     * 媒体审计 Markdown 表 (Story 8.6 D-E + Story 9.1 扩展, nullable).
     *
     * <p>REWRITE 模式恒为 {@code null}; PRESERVE_ORIGINAL 与 REWRITE_WITH_MEDIA 模式由
     * 各自 generator ({@code PreserveOriginalArticleGenerator} /
     * {@code MediaAwareRewriteArticleGenerator}) 从 media.json sidecar 权威状态生成
     * (每媒体: 类型/uploadStatus/publishability/wechatUrl/相对路径/failureReason 截断),
     * MarkdownArchiver 归档块 verbatim 插入, 不进入微信草稿正文 (Story 9.1 AD-12)。
     * 脱敏边界: 不含 access token/AppSecret/完整响应体/本地绝对路径。
     */
    private String mediaAuditMarkdown;
}
