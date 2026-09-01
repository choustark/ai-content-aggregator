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

    /**
     * 派生型 sourceAccessNote 的历史值: "provider 未返回文本" 快照标记.
     *
     * <p><b>已废弃写入 (2026-08-30 终局清理)</b> — 三个 provider client 曾在文本为空时写入
     * 本 note, 但它只是调用时快照, 跨源合并后会与最终文本矛盾, 导致 {@code TweetPublishabilityGate}
     * 误判推文级 BLOCKED (生产故障: 媒体全 PUBLISHABLE 但推文 BLOCKED)。文本缺失判定权现归
     * gate T1 (三文本字段全 blank) 独占, 本 note 不再有任何写入端; 常量仅供
     * {@code TwitterSource.mergeTweet} 净化 Redis 存量缓存 (24h TTL) 中的历史毒数据。
     * {@code sourceAccessNote} 字段现仅保留给审计兼容读取，不再承担发布性主决策职责。
     */
    public static final String SOURCE_TEXT_MISSING_NOTE = "源文本为空或 provider 未返回文本";

    /**
     * 历史遗留 article note。
     *
     * <p>它本质是内容类型标签，不是访问受限信号；仅供兼容迁移旧缓存/旧序列化数据。
     */
    public static final String SOURCE_ARTICLE_TYPE_NOTE = "x-author-scraper article";

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

    /** 推文内容类型；缺省为 UNKNOWN。 */
    @Builder.Default
    private TweetContentType contentType = TweetContentType.UNKNOWN;

    /** 推文源访问状态；缺省按可访问处理。 */
    @Builder.Default
    private TweetAccessStatus accessStatus = TweetAccessStatus.ACCESSIBLE;

    /** 结构化受限原因；缺省为 NONE。 */
    @Builder.Default
    private TweetRestrictionReason restrictionReason = TweetRestrictionReason.NONE;

    /** 受限原因补充细节；仅在结构化原因不足以表达时使用。 */
    private String restrictionDetail;

    /** 历史兼容/审计 note；正常可访问时为 null，不参与发布性主决策。 */
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

    /**
     * 结构化访问状态是否表示需阻断自动发布。
     */
    public boolean hasStructuredAccessBlock() {
        if (accessStatus == null) {
            return false;
        }
        return accessStatus == TweetAccessStatus.RESTRICTED
                || accessStatus == TweetAccessStatus.DELETED
                || accessStatus == TweetAccessStatus.WITHHELD;
    }

    /**
     * 结构化阻断原因，缺省退化为访问状态默认文案。
     */
    public String structuredAccessBlockReason() {
        if (!hasStructuredAccessBlock()) {
            return null;
        }
        if (restrictionDetail != null && !restrictionDetail.isBlank()) {
            return restrictionDetail;
        }
        if (restrictionReason != null && restrictionReason != TweetRestrictionReason.NONE) {
            return switch (restrictionReason) {
                case ACCESS_RESTRICTED -> "访问受限";
                case DELETED_BY_AUTHOR -> "推文已被作者删除";
                case WITHHELD_BY_PLATFORM -> "推文被平台限制展示";
                case LEGACY_OTHER -> "历史受限原因";
                case NONE -> null;
            };
        }
        return switch (accessStatus) {
            case RESTRICTED -> "访问受限";
            case DELETED -> "推文已被作者删除";
            case WITHHELD -> "推文被平台限制展示";
            case UNKNOWN, ACCESSIBLE -> "访问状态未知";
        };
    }
}
