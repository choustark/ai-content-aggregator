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

    /** 正文(Markdown 格式). */
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
}
