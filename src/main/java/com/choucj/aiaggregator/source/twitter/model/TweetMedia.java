package com.choucj.aiaggregator.source.twitter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示 X/Twitter 原帖中的一个权威媒体单元，统一承载图片、视频和 GIF 元数据。
 *
 * <p>引用源: Story 6.3(创建) / architecture spine AD-1。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TweetMedia {

    /** 媒体 ID；provider 未返回时可由 {@code tweetId:index:type} 生成。 */
    private String id;

    /** 媒体类型；无法识别时使用 {@link TweetMediaType#UNKNOWN}。 */
    @Builder.Default
    private TweetMediaType type = TweetMediaType.UNKNOWN;

    /** 可下载或可展示的源 URL；provider 未返回时为 null。 */
    private String sourceUrl;

    /** 预览图 URL；图片可与 sourceUrl 相同，视频/GIF 通常为缩略图。 */
    private String previewImageUrl;

    /** 视频/GIF 的候选 variants；图片或 provider 未返回 variants 时为空列表。 */
    @Builder.Default
    private List<TweetMediaVariant> variants = new ArrayList<>();

    /** 原帖中的媒体顺序，从 0 开始；未知时为 null。 */
    private Integer order;

    /** 媒体宽度；provider 未返回时为 null。 */
    private Integer width;

    /** 媒体高度；provider 未返回时为 null。 */
    private Integer height;

    /** provider 可用性信号，例如 Available；provider 未返回时为 null。 */
    private String availability;

    /** 是否允许下载；PHOTO 的 provider null 语义由解析器映射为 true。 */
    private boolean allowDownload;

    /** 字段来源 provider，例如 apify、fxtwitter、twscrape。 */
    private String provider;

    /** 原始 provider 字段的紧凑摘要；不得包含完整响应体或完整 variants URL。 */
    private String providerRawSummary;

    /** 字段级失败或降级原因；无失败时为 null。 */
    private String failureReason;

    // ===== Story 7.1: 媒体归档与发布生命周期状态字段 =====

    /**
     * 本地归档文件相对/绝对路径；未下载时为 null（Story 7.2/7.3 填）。
     * <p>路径以确定性归档目录 {@code media/twitter/{yyyy-MM-dd}/{tweetId}/} 为基准。
     */
    private String localPath;

    /**
     * 下载状态；默认 {@link MediaDownloadStatus#PENDING}（Story 7.2/7.3 填）。
     */
    @Builder.Default
    private MediaDownloadStatus downloadStatus = MediaDownloadStatus.PENDING;

    /**
     * 微信上传状态；默认 {@link MediaUploadStatus#PENDING}（Story 8.4 填）。
     */
    @Builder.Default
    private MediaUploadStatus uploadStatus = MediaUploadStatus.PENDING;

    /**
     * 可发布性状态；默认 {@link PublishabilityStatus#UNKNOWN}（Story 7.5 gate 填）。
     */
    @Builder.Default
    private PublishabilityStatus publishability = PublishabilityStatus.UNKNOWN;

    /**
     * 微信正文图片上传后的 URL；未上传时为 null（Story 8.4 填）。
     */
    private String wechatUrl;

    /**
     * 微信媒体 ID；未上传时为 null（Story 8.4 填）。
     */
    private String wechatMediaId;
}
