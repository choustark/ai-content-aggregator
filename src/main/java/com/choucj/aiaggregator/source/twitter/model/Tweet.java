package com.choucj.aiaggregator.source.twitter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Twitter 推文领域模型.
 *
 * <p>引用源:
 * <ul>
 *   <li>Story 2.1 — RSSHub 返回(基础字段: id / author / summary / url / publishedAt)</li>
 *   <li>Story 2.2a — FxTwitter 补全(content / replyCount / retweetCount / likeCount / imageUrls)</li>
 *   <li>Story 2.3 — {@code ContentFilter} 输入(replyCount 是评论数筛选依据)</li>
 *   <li>Story 2.3b — {@code InnovationFilter} 写入 {@code innovationScore}(AI 创新度评分, 1-10)</li>
 *   <li>Story 2.4 — {@code ContentRewriter} 输入(content + summary 是改写源文本)</li>
 *   <li>Story 2.6 — Pipeline 流转的核心载体</li>
 *   <li>Story 3.2 — imageUrls 用于微信草稿图片转换</li>
 *   <li>Story 6.3 — rawText/formattedText/media/links/mentions/quotedTweetUrl 用于原帖保真</li>
 * </ul>
 *
 * <p><b>toBuilder 决策(Story 2.2a delta):</b> 开启 {@code @Builder(toBuilder=true)},
 * 让 {@code TwitterSource} 在合并 RSSHub 部分字段(id/author/summary/url/publishedAt) +
 * FxTwitter 补全字段(content/互动数/imageUrls)时, 用 {@code partial.toBuilder().content(...).build()}
 * 保留 RSSHub 已填字段, 避免 FxTwitter 覆盖 summary/url 等.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Tweet {

    /** 推文 ID(必填, Story 2.1 RSSHub 返回). */
    private String id;

    /** 作者 handle({@code @username}). */
    private String author;

    /** 完整文本(Story 2.2 通过 FxTwitter 补全)，继续作为 REWRITE 路径的兼容输入。 */
    private String content;

    /** Provider 原始文本；provider 未返回原始形态时为 null。 */
    private String rawText;

    /** 展开链接/mention 后的可读文本；provider 未返回格式化形态时为 null。 */
    private String formattedText;

    /** 摘要(Story 2.1 RSSHub 提供, 长推文的简介). */
    private String summary;

    /** 推文链接. */
    private String url;

    /** 发布时间. */
    private LocalDateTime publishedAt;

    /** 回复数(Story 2.3 评论数筛选的核心指标). */
    private int replyCount;

    /** 转发数. */
    private int retweetCount;

    /** 点赞数. */
    private int likeCount;

    /** 图片 URL 列表(Story 3.2 转换需要)，Story 6.3 后只包含 PHOTO sourceUrl 投影。 */
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    /** 原帖权威媒体单元列表；无媒体时为空列表。 */
    @Builder.Default
    private List<TweetMedia> media = new ArrayList<>();

    /** 原帖中解析出的链接；provider 未返回或无链接时为空列表。 */
    @Builder.Default
    private List<String> links = new ArrayList<>();

    /** 原帖中解析出的 mention；provider 未返回或无 mention 时为空列表。 */
    @Builder.Default
    private List<String> mentions = new ArrayList<>();

    /** 引用推 URL；无引用或 provider 无法解析时为 null。 */
    private String quotedTweetUrl;

    /** 引用推可读摘要文本；provider 未返回完整引用上下文时为 null。 */
    private String quotedTweetText;

    /** 源访问或字段级降级提示；正常可访问时为 null。 */
    private String sourceAccessNote;

    /**
     * AI 创新度评分 (1-10, nullable).
     *
     * <p>由 {@code InnovationFilter} (Story 2.3b) 调 LLM 评分后写入. {@code null} 表示尚未评分
     * (CommentFilter 通过但未进入 InnovationFilter, 或评分失败被跳过).
     * {@code InnovationFilter#filter} 内部按 {@code innovationScore >= innovationThreshold} 筛选.
     */
    @Builder.Default
    private Double innovationScore = null;
}
