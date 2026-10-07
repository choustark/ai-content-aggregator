package com.choucj.aiaggregator.source.twitter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

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

    /**
     * 微信永久视频素材 media_id；仅 VIDEO 且 wechatPrepare=SUCCEEDED 时有值 (Story 10.11 填)。
     * <p>不复用 PHOTO 的 {@code wechatUrl}（值语义不同：mediaId vs 正文图片 URL），装配嵌入谓词
     * 按本字段判定，避免误伤 PHOTO。旧 sidecar 缺该字段时反序列化为 null（向后兼容，读取容错）。
     */
    private String wechatVideoMediaId;

    /** 下载阶段权威状态；旧 sidecar 缺失时由 Writer 兼容回填。 */
    @Builder.Default
    private MediaPhaseState download = MediaPhaseState.notStarted();

    /** 微信准备阶段权威状态；GIF/UNKNOWN 不进入该执行链。 */
    @Builder.Default
    private MediaPhaseState wechatPrepare = MediaPhaseState.notStarted();

    /** 文章引用阶段权威状态；成功仅表示本地引用完整，不表示微信已接收草稿。 */
    @Builder.Default
    private MediaPhaseState articleReference = MediaPhaseState.notStarted();

    /**
     * 下载成功后的本地文件大小 (bytes)；仅 download=SUCCEEDED 时有值 (Story 10.10 VIDEO 证据字段)。
     * <p>旧 sidecar 缺失该字段时反序列化为 null (向后兼容，读取容错)。
     */
    private Long fileSizeBytes;

    /**
     * 下载成功后的 HTTP 响应实际 Content-Type；仅 download=SUCCEEDED 时有值 (Story 10.10 证据字段)。
     * <p>旧 sidecar 缺失该字段时反序列化为 null (向后兼容，读取容错)。
     */
    private String downloadedContentType;

    /** GIF 延后处理时保留原文链接，非 GIF 时为 null。 */
    private String originalPostUrl;

    /** GIF 延后处理或 UNKNOWN 阻断时的人工说明。 */
    private String manualInstruction;

    /** 首次初始化三阶段 schema，禁止 GIF/UNKNOWN 误入自动执行链。 */
    public void initializeDeliveryPhases(String postUrl) {
        LocalDateTime now = LocalDateTime.now();
        if (type == TweetMediaType.GIF) {
            download = deferred(now);
            wechatPrepare = deferred(now);
            articleReference = deferred(now);
            originalPostUrl = postUrl;
            manualInstruction = "GIF 暂不自动下载、上传或嵌入，请按原文链接人工处理";
        } else {
            download = MediaPhaseState.notStarted();
            wechatPrepare = MediaPhaseState.notStarted();
            articleReference = MediaPhaseState.notStarted();
            if (type == TweetMediaType.UNKNOWN) {
                manualInstruction = "未知媒体类型，已阻断自动发布";
            }
        }
    }

    private static MediaPhaseState deferred(LocalDateTime now) {
        return MediaPhaseState.builder().status(MediaPhaseStatus.DEFERRED).attempt(0).updatedAt(now).build();
    }

    /** 将下载失败固定终态化，后续两阶段保持 NOT_STARTED 且禁止自动重试。 */
    public void markDownloadFailed(String errorClass, String errorCode, String safeSummary) {
        LocalDateTime now = LocalDateTime.now();
        download = MediaPhaseState.builder()
                .status(MediaPhaseStatus.FAILED_TERMINAL)
                .attempt(1)
                .nextRetryAt(null)
                .errorClass(errorClass)
                .errorCode(errorCode)
                .errorSummary(safeSummary)
                .updatedAt(now)
                .build();
        wechatPrepare = MediaPhaseState.notStarted();
        articleReference = MediaPhaseState.notStarted();
        downloadStatus = MediaDownloadStatus.FAILED;
        uploadStatus = MediaUploadStatus.PENDING;
    }
}
