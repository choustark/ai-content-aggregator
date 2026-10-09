package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.monitoring.MediaMetrics;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.task.queue.RetryPolicyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Story 8.4: 微信正文图片准备编排器 — 上传本地 X 图片并回写状态到 media.json sidecar.
 *
 * <p>遍历 {@link TweetMedia} 列表，把 PHOTO 且本地已归档 (downloadStatus=DOWNLOADED + 文件存在)
 * 的图片经 {@link WeChatBodyImageUploadProbe} (mediaImgUpload) 上传到微信，取得可用于正文
 * {@code <img src="">} 的微信 URL，通过 {@link TweetMediaArchiveWriter#updateMedia} 增量回写
 * {@code uploadStatus/wechatUrl/failureReason} 三个字段 (本类是这三字段的第一写入方)。
 *
 * <p><b>关键设计 (镜像 TweetMediaArchiver 已验证模式):</b>
 * <ul>
 *   <li><b>单媒体失败降级</b> — 每媒体独立 try-catch，单个失败只回写 FAILED + 截断根因，
 *       不中断循环、不阻塞整篇草稿 (AD-5)</li>
 *   <li><b>幂等跳过</b> — sidecar 中已是 UPLOADED 且 wechatUrl 非空的媒体不再调微信接口
 *       (sidecar 是上传状态唯一权威源，防配额浪费)；Story 10.11: VIDEO 幂等判据为
 *       wechatPrepare=SUCCEEDED + wechatVideoMediaId 非空</li>
 *   <li><b>VIDEO/GIF 不走图片路径</b> — 绝不调用图片上传接口。Story 10.11 起 VIDEO 走独立
 *       {@link WeChatVideoMediaAdapter} 永久素材上传 (Spike 10.9 §8 契约, 配置
 *       {@code wechat.mp.video.enabled} 可关闭回落 10.8 降级语义)；GIF/UNKNOWN 仍 SKIPPED 降级
 *       (Story 8.2 结论: 预览图/原文链接)</li>
 *   <li><b>字段严格分离</b> — PHOTO 只写 wechatUrl (uploadimg 只返回 url 无 media_id)，
 *       不触碰封面 thumb_media_id 链路与 wechatMediaId (AD-4)</li>
 *   <li><b>时间锚定</b> — effectivePublishedAt 一次性锚定，防 null 时多次 now() 跨日目录错位
 *       (Story 7.2 review patch 模式)</li>
 *   <li><b>可选依赖</b> — {@link TweetMediaArchiveWriter} 经 {@code Optional} 注入
 *       (twitter.media.enabled 独立开关)，缺失时显式 fail-fast 而非静默上传不落账 (D6 规则)</li>
 * </ul>
 *
 * <p><b>调用契约 (Story 9.1 Task 3 扩展):</b> 本准备器只允许在<b>媒体感知生成分支</b>
 * 被调用 — {@code PRESERVE_ORIGINAL} (Story 8.5 渲染 gateway / 8.6 发布集成) 与
 * {@code REWRITE_WITH_MEDIA} (Story 9.1 {@code MediaAwareRewriteArticleGenerator});
 * plain {@code REWRITE} 路径禁止调用。模式判定由调用方经
 * {@code ContentGenerationModeResolver} 完成，本类不感知生成模式
 * (publish/wechat 不得反向依赖 processor 或 resolver 包)。
 *
 * <p><b>Story 9.1 上传前防线 (AC 5 / AD-13):</b> 上传前必须跳过
 * {@code publishability=BLOCKED} 的媒体 (不调微信 uploadImg, SKIPPED + 原因落账);
 * 幂等判据 (UPLOADED + wechatUrl 非空) 优先于 BLOCKED 拦截 — 已成功上传的媒体
 * 不重传不改写, BLOCKED 的正文嵌入拒绝由 {@code MarkdownMediaInserter} 谓词承担;
 * 失败回写只更新 {@code uploadStatus/failureReason}, 绝不清空既有成功
 * {@code wechatUrl} (本类是三字段唯一写入方, toBuilder 保留未触碰字段)。
 *
 * <p><b>前置条件:</b> 媒体已经过 {@code TweetMediaArchiver} 归档 (sidecar 存在 + localPath 已回写)；
 * sidecar 缺失视为未归档，全部媒体标记 FAILED，不调微信。
 *
 * <p>引用源: Story 8.4 / ARCHITECTURE-SPINE AD-2(三阶段独立状态) + AD-4(微信 URL) + AD-5(单媒体降级) /
 * Story 8.1 Spike 决策表 / Story 8.2 视频 GIF 降级结论 / TweetMediaArchiver 编排模式复用。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatMediaPreparer {

    /** failureReason / 日志根因截断长度 (N4 + R3-1). */
    private static final int FAILURE_REASON_MAX_LENGTH = 200;

    /** N4 脱敏: 完整 URL 替换为占位符 (镜像 7.5 Gate sanitizeAndTruncateReason，CR 2026-08-28). */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /** Story 8.2 结论: 视频微信正文内嵌未证实，降级为预览图/原文链接 (含 8.2 决策表 degradeReason token). */
    private static final String VIDEO_SKIP_REASON =
            "视频内嵌未证实，降级为预览图+原文链接 (video_embed_unverified, Story 8.2)";

    /** Story 10.11: VIDEO 幂等跳过原因 (sidecar 已有 mediaId 且 wechatPrepare=SUCCEEDED). */
    private static final String VIDEO_IDEMPOTENT_SKIP_REASON = "已上传，幂等跳过";

    /** Story 10.11 (OQ1): 永久素材 title 上限 (码点) — 微信限 640 字节, 中文 64 码点 (192 字节) 安全. */
    private static final int VIDEO_TITLE_MAX_CODE_POINTS = 64;

    /** Story 10.11 (OQ1): 永久素材 description 上限 (码点) — 微信限 1200 字节, 120 码点安全. */
    private static final int VIDEO_DESCRIPTION_MAX_CODE_POINTS = 120;

    /** Story 10.11 (OQ1): article title/digest 缺失时的素材命名兜底前缀. */
    private static final String VIDEO_MATERIAL_FALLBACK_TITLE = "X 原帖视频";

    /** Story 10.11 (OQ1): tweetId 后缀拼接前缀 (素材库人工清理定位锚点). */
    private static final String VIDEO_MATERIAL_TITLE_SUFFIX = " tw-";

    /** Story 8.2 结论: GIF uploadimg 接受度未证实，按不支持处理 (含 8.2 决策表 degradeReason token). */
    private static final String GIF_SKIP_REASON =
            "GIF 接口接受度未证实，按不支持处理，降级为预览图+原文链接 (gif_api_unverified, Story 8.2)";

    /** 未知/空类型媒体不进入图片上传链路. */
    private static final String UNKNOWN_TYPE_SKIP_REASON = "未知媒体类型，跳过微信图片准备";

    /** sidecar 缺失 (媒体未经 TweetMediaArchiver 归档) 时的统一失败原因. */
    private static final String SIDE_CAR_MISSING_REASON =
            "media sidecar missing，媒体未归档，需先执行 TweetMediaArchiver";

    private final WeChatBodyImageUploadProbe uploadProbe;
    private final Optional<TweetMediaArchiveWriter> archiveWriter;
    /** Story 10.8: task.retry.* 重试策略 (重试上限唯一来源, 不新增第二套重试配置). */
    private final RetryPolicyProperties retryPolicy;
    /** Story 10.11: VIDEO 永久素材适配器; null 时 VIDEO 保持 10.8 降级语义 (legacy 测试兼容). */
    private final WeChatVideoMediaAdapter videoAdapter;
    /** Story 10.11: wechat.mp.video.* 配置 (videoAdapter 为 null 时仅 videoPreparationEnabled 兜底消费). */
    private final WeChatProperties weChatProperties;
    /** Story 10.12: 媒体阶段成功率指标 (观测旁路; legacy 测试构造器传 null → 埋点静默关闭). */
    private final MediaMetrics mediaMetrics;

    /**
     * 构造器注入依赖 (Story 10.12 起含 {@link MediaMetrics}).
     *
     * @param uploadProbe      微信正文图片上传探针 (Story 8.1，同 wechat.mp.enabled 开关，必共存)
     * @param archiveWriter    sidecar 写入器 (twitter.media.enabled 独立开关，可能未注册；
     *                         缺失时 prepareMedia 显式 fail-fast)
     * @param retryPolicy      task.retry.* 既有重试策略 (RETRY_SCHEDULED 的 nextRetryAt 退避来源)
     * @param videoAdapter     VIDEO 永久素材上传适配器 (Story 10.11，同 wechat.mp.enabled 开关)
     * @param weChatProperties wechat.mp.* 项目层配置 (wechat.mp.video.* 消费源)
     * @param mediaMetrics     媒体阶段指标 (Story 10.12; 观测旁路, 不改变准备语义)
     */
    @Autowired
    public WeChatMediaPreparer(WeChatBodyImageUploadProbe uploadProbe,
                               Optional<TweetMediaArchiveWriter> archiveWriter,
                               RetryPolicyProperties retryPolicy,
                               WeChatVideoMediaAdapter videoAdapter,
                               WeChatProperties weChatProperties,
                               MediaMetrics mediaMetrics) {
        this.uploadProbe = uploadProbe;
        this.archiveWriter = archiveWriter;
        this.retryPolicy = retryPolicy;
        this.videoAdapter = videoAdapter;
        this.weChatProperties = weChatProperties;
        this.mediaMetrics = mediaMetrics;
    }

    /** Story 10.8 签名 (测试兼容): videoAdapter=null → VIDEO 走 10.8 降级语义 (SKIPPED, 零上传请求). */
    public WeChatMediaPreparer(WeChatBodyImageUploadProbe uploadProbe,
                               Optional<TweetMediaArchiveWriter> archiveWriter,
                               RetryPolicyProperties retryPolicy) {
        this(uploadProbe, archiveWriter, retryPolicy, null, new WeChatProperties(), null);
    }

    /** Story 10.8 前签名 (测试兼容): 默认 task.retry.* (max-attempts=3, 60s-600s 指数退避). */
    public WeChatMediaPreparer(WeChatBodyImageUploadProbe uploadProbe,
                               Optional<TweetMediaArchiveWriter> archiveWriter) {
        this(uploadProbe, archiveWriter, new RetryPolicyProperties());
    }

    /**
     * 为原帖复现草稿准备微信正文图片: 上传本地 PHOTO 图片并回写状态到 media.json sidecar.
     *
     * <p>每个媒体独立 try-catch (AD-5): 单个失败 (微信侧错误/本地文件缺失) 只降级该媒体，
     * 重试信号经 {@link MediaPreparationResult.MediaPreparationStatus#retryable()} 暴露给调用方。
     *
     * <p><b>幂等跳过契约 (CR 2026-08-28):</b> 已是 UPLOADED 且 wechatUrl 非空的媒体在结果对象中
     * 记为 {@code SKIPPED} 但 {@code wechatUrl} 非空 (携带 sidecar 已有 URL)。调用方判断媒体可用性
     * 必须以 {@code wechatUrl 非空} 为准，不能只看 {@code status == UPLOADED} 或 {@code successCount}
     * —— 全部已上传的推文返回 {@code skipCount=N, successCount=0}。
     *
     * @param tweetId     推文 ID (白名单校验由 resolveArchiveDir 承担)
     * @param publishedAt 推文发布时间 (sidecar 目录日期段基准; null 时一次性锚定当前时刻)
     * @param media       媒体列表 (null 视为空)
     * @return 处理结果摘要 (成功数 + 跳过数 + 失败数 + per-media 状态)
     */
    public MediaPreparationResult prepareMedia(String tweetId, LocalDateTime publishedAt,
                                               List<TweetMedia> media) {
        return prepareMedia(tweetId, publishedAt, media, null, null);
    }

    /**
     * 带文章标题/简介的媒体准备 (Story 10.11 OQ1): 永久视频素材 title = 文章标题 + tweetId
     * 后缀 (拼接后截断), description = 文章简介截断 — 兼顾素材库运营可读与人工清理定位。
     *
     * <p>title/digest 为 null 时回退确定性兜底 (X 原帖视频 + tweetId), 适配器对空值 fail-fast。
     *
     * @param tweetId       推文 ID (白名单校验由 resolveArchiveDir 承担)
     * @param publishedAt   推文发布时间 (sidecar 目录日期段基准; null 时一次性锚定当前时刻)
     * @param media         媒体列表 (null 视为空)
     * @param articleTitle  文章标题 (OQ1 素材 title 来源; 可为 null)
     * @param articleDigest 文章简介 (OQ1 素材 description 来源; 可为 null)
     * @return 处理结果摘要 (成功数 + 跳过数 + 失败数 + per-media 状态)
     */
    public MediaPreparationResult prepareMedia(String tweetId, LocalDateTime publishedAt,
                                               List<TweetMedia> media,
                                               String articleTitle, String articleDigest) {
        if (media == null || media.isEmpty()) {
            return MediaPreparationResult.empty();
        }
        TweetMediaArchiveWriter writer = requireArchiveWriter();

        // effectivePublishedAt 一次性锚定 (Story 7.2 review patch: 防 null 时多次 now() 跨日错位目录)
        LocalDateTime effectivePublishedAt = publishedAt != null ? publishedAt : LocalDateTime.now();
        Path archiveDir = writer.resolveArchiveDir(tweetId, effectivePublishedAt);

        Optional<MediaArchiveRecord> sidecar = writer.readSidecar(tweetId, effectivePublishedAt);
        if (sidecar.isEmpty() || sidecar.get().getMedia() == null || sidecar.get().getMedia().isEmpty()) {
            return failAllForMissingSidecar(tweetId, media);
        }
        List<TweetMedia> sidecarMedia = sidecar.get().getMedia();

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        List<MediaPreparationResult.MediaPreparationStatus> statuses = new ArrayList<>();

        int photoIndex = 0;
        for (int index = 0; index < media.size(); index++) {
            TweetMedia m = media.get(index);
            if (m == null) {
                continue;
            }
            boolean isPhoto = m.getType() == TweetMediaType.PHOTO;
            // synthetic id 计数镜像 Archiver: PHOTO 用 photo-only 计数, 其余用全列表下标 (CR 2026-08-28)
            String mediaId = effectiveMediaId(tweetId, m, isPhoto ? photoIndex++ : index);
            // 状态判定与回写目标必须绑定同一个 sidecar 项 (id 优先/下标兜底, CR 2026-08-28 错位修复)
            SidecarRef ref = findSidecarRef(sidecarMedia, m, index);
            TweetMedia sidecarState = ref == null ? null : ref.item();

            try {
                // Story 10.11: VIDEO 幂等预检 — 先于 videoPreparationEnabled() gate (AC3 冻结:
                // video.enabled=false 时重处理已 SUCCEEDED+mediaId 的 VIDEO 也不得落入通用
                // !isPhoto SKIPPED 分支盖写 uploadStatus/failureReason)。sidecar 已有有效 mediaId
                // 且 wechatPrepare=SUCCEEDED → 零上传请求/零写回跳过 (微信永久素材无删除 API,
                // 重复上传会产生无法回收的孤儿素材)。
                if (m.getType() == TweetMediaType.VIDEO && isVideoPrepared(sidecarState)) {
                    log.info("VIDEO 已上传，幂等跳过微信永久素材准备: tweetId={}, mediaId={}", tweetId, mediaId);
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.skipped(
                            mediaId, VIDEO_IDEMPOTENT_SKIP_REASON));
                    skipCount++;
                    continue;
                }

                // Story 10.11: VIDEO 生产分支 — 适配器就绪且配置开启时走永久素材上传 (Spike 10.9 §8
                // 契约: 唯一端点 materialFileUpload(video), 唯一引用方式候选 A mediaId 纯文本直嵌);
                // 未开启时回落到下方 !isPhoto 既有 SKIPPED 降级语义 (10.8 行为零变化)。
                if (m.getType() == TweetMediaType.VIDEO && videoPreparationEnabled()) {
                    // 上传前 publishability=BLOCKED 拦截 (镜像 PHOTO, Story 9.1 语义复用)
                    if ((sidecarState != null && sidecarState.getPublishability() == PublishabilityStatus.BLOCKED)
                            || m.getPublishability() == PublishabilityStatus.BLOCKED) {
                        String blockedReason = "publishability=BLOCKED，gate 阻断，跳过微信上传 (Story 9.1)";
                        tryWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                                original -> original.toBuilder()
                                        .uploadStatus(MediaUploadStatus.SKIPPED)
                                        .failureReason(blockedReason)
                                        .build());
                        log.info("VIDEO 被 publishability gate 阻断，跳过微信永久素材上传: tweetId={}, mediaId={}",
                                tweetId, mediaId);
                        statuses.add(MediaPreparationResult.MediaPreparationStatus.skipped(mediaId, blockedReason));
                        skipCount++;
                        continue;
                    }

                    // 本地就绪预检 (存在/非零 + localPath); 容量由适配器按 wechat.mp.video.size-limit-mb 前置。
                    // 该路径未消耗微信配额, 但 I/O 矩阵要求本地前置失败 → wechatPrepare=FAILED_TERMINAL
                    // (NonRetryable) → 文章 DELIVERY_FAILED + 草稿 0, 故走 safeWriteBackFailed 终态通道。
                    String notReadyReason = localNotReadyReason(sidecarState, archiveDir);
                    if (notReadyReason != null) {
                        safeWriteBackFailed(writer, tweetId, effectivePublishedAt, ref, mediaId,
                                notReadyReason, MediaPreparationResult.FailureClass.PERMANENT,
                                ErrorCode.WECHAT_API_ERROR.name());
                        log.warn("VIDEO 本地文件未就绪，不发起上传: tweetId={}, mediaId={}, reason={}",
                                tweetId, mediaId, notReadyReason);
                        recordPrepareTerminal(m);
                        statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(
                                mediaId, notReadyReason, false));
                        failCount++;
                        continue;
                    }

                    // 上传 + mediaId/wechatPrepare=SUCCEEDED 回写; 失败 (含回写未命中) 由共享
                    // catch 走 classifyFailure/safeWriteBackFailed 既有治理链 (10.8 语义全复用)。
                    uploadVideoAndWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                            sidecarState, archiveDir, articleTitle, articleDigest);
                    // Story 10.12: wechat_prepare 阶段成功 (观测旁路)
                    recordPrepareSucceeded(m);
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.uploadedVideo(mediaId));
                    successCount++;
                    continue;
                }

                if (!isPhoto) {
                    // VIDEO/GIF/UNKNOWN/null: 一律 SKIPPED，不走图片上传路径 (Story 8.2 边界, AC5)。
                    // 回写软失败: 不消耗配额的操作不应因回写 miss 变成 FAILED (CR 2026-08-28)
                    String reason = skipReasonForType(m.getType());
                    tryWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                            original -> original.toBuilder()
                                    .uploadStatus(MediaUploadStatus.SKIPPED)
                                    .failureReason(reason)
                                    .build());
                    log.info("媒体跳过微信图片准备: tweetId={}, mediaId={}, type={}, reason={}",
                            tweetId, mediaId, m.getType(), reason);
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.skipped(mediaId, reason));
                    skipCount++;
                    continue;
                }

                // 幂等预检: sidecar 是上传状态唯一权威源 (AC7)
                if (isUploaded(sidecarState)) {
                    log.info("媒体已上传，幂等跳过微信图片准备: tweetId={}, mediaId={}", tweetId, mediaId);
                    statuses.add(new MediaPreparationResult.MediaPreparationStatus(
                            mediaId, MediaUploadStatus.SKIPPED,
                            sidecarState.getWechatUrl(), "已上传，幂等跳过", false, null));
                    skipCount++;
                    continue;
                }

                // Story 9.1 Task 3 (AC 5): 上传前 publishability=BLOCKED 拦截 — gate 判 BLOCKED
                // 的媒体绝不调微信 uploadImg, SKIPPED + 原因落账。幂等预检在其之前:
                // 已 UPLOADED 的 BLOCKED 媒体不重传不改写, 嵌入侧由 MarkdownMediaInserter
                // 谓词 (publishability != BLOCKED) 拒绝进正文。
                if ((sidecarState != null && sidecarState.getPublishability() == PublishabilityStatus.BLOCKED)
                        || m.getPublishability() == PublishabilityStatus.BLOCKED) {
                    String blockedReason = "publishability=BLOCKED，gate 阻断，跳过微信上传 (Story 9.1)";
                    tryWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                            original -> original.toBuilder()
                                    .uploadStatus(MediaUploadStatus.SKIPPED)
                                    .failureReason(blockedReason)
                                    .build());
                    log.info("媒体被 publishability gate 阻断，跳过微信图片上传: tweetId={}, mediaId={}",
                            tweetId, mediaId);
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.skipped(mediaId, blockedReason));
                    skipCount++;
                    continue;
                }

                // 本地就绪校验 (Story 8.1 Spike 决策表: 本地缺失 → FAILED, 不调微信)。
                // 回写软失败: 未调微信的操作不应因回写 miss 抛异常 (CR 2026-08-28)
                String notReadyReason = localNotReadyReason(sidecarState, archiveDir);
                if (notReadyReason != null) {
                    tryWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                            original -> original.toBuilder()
                                    .uploadStatus(MediaUploadStatus.FAILED)
                                    .failureReason(notReadyReason)
                                    .build());
                    log.warn("微信正文图片本地文件未就绪: tweetId={}, mediaId={}, reason={}",
                            tweetId, mediaId, notReadyReason);
                    recordPrepareTerminal(m);
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(
                            mediaId, notReadyReason, false));
                    failCount++;
                    continue;
                }

                String wechatUrl = uploadAndWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                        resolveLocalFile(archiveDir, sidecarState.getLocalPath()));
                // Story 10.12: wechat_prepare 阶段成功 (观测旁路)
                recordPrepareSucceeded(m);
                statuses.add(MediaPreparationResult.MediaPreparationStatus.uploaded(mediaId, wechatUrl));
                successCount++;
            } catch (Exception e) {
                // AD-5: 单媒体失败降级 — 只回写该媒体 FAILED，继续处理后续媒体。
                // Story 10.8: 分类升级为四分类并写 wechatPrepare 阶段证据 (RETRY_SCHEDULED 或终态),
                // 供生成器分流: 可重试 → 任务级延迟重试; 终态 → 立即四层收敛。
                String reason = truncateReason(e);
                MediaPreparationResult.FailureClass failureClass = classifyFailure(e);
                String errorCode = errorCodeOf(e);
                log.warn("微信正文图片准备失败(单媒体降级): tweetId={}, mediaId={}, failureClass={}, errorCode={}, reason={}",
                        tweetId, mediaId, failureClass, errorCode, reason);
                safeWriteBackFailed(writer, tweetId, effectivePublishedAt, ref, mediaId,
                        reason, failureClass, errorCode);
                // Story 10.12: 按四分类计数 — RETRYABLE/RATE_LIMITED=retry_scheduled,
                // ENVIRONMENT_BLOCKED=blocked(立即终态零重试), PERMANENT=failed_terminal (观测旁路)
                recordPrepareFailure(m, failureClass);
                statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(
                        mediaId, reason, failureClass));
                failCount++;
            }
        }

        log.info("微信正文图片准备完成: tweetId={}, 成功={}, 跳过={}, 失败={}",
                tweetId, successCount, skipCount, failCount);
        return new MediaPreparationResult(successCount, skipCount, failCount, List.copyOf(statuses));
    }

    /** writer 缺失 (twitter.media.enabled=false) 时显式 fail-fast: 上传不落账比报错更糟 (T1.7). */
    private TweetMediaArchiveWriter requireArchiveWriter() {
        return archiveWriter.orElseThrow(() -> new NonRetryableException(
                ErrorCode.NON_RETRYABLE_ERROR,
                "twitter.media.enabled 未开启或 TweetMediaArchiveWriter 未注册，无法回写微信上传状态到 media.json sidecar"));
    }

    /** sidecar 缺失: 媒体未归档，全部 FAILED，不调微信 (前置条件是 Story 7.2 已归档). */
    private MediaPreparationResult failAllForMissingSidecar(String tweetId, List<TweetMedia> media) {
        List<MediaPreparationResult.MediaPreparationStatus> statuses = new ArrayList<>();
        int photoIndex = 0;
        for (int index = 0; index < media.size(); index++) {
            TweetMedia m = media.get(index);
            if (m == null) {
                continue;
            }
            // synthetic id 计数与 prepareMedia 主循环保持一致 (photo-only 计数)
            boolean isPhoto = m.getType() == TweetMediaType.PHOTO;
            // Story 10.12: sidecar 缺失属准备阶段终态失败 (不调微信, 零重试), 按类型计数 (观测旁路)
            recordPrepareTerminal(m);
            statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(
                    effectiveMediaId(tweetId, m, isPhoto ? photoIndex++ : index),
                    SIDE_CAR_MISSING_REASON, false));
        }
        log.warn("微信正文图片准备中止: sidecar 缺失或媒体列表为空，全部媒体标记 FAILED: tweetId={}, mediaCount={}",
                tweetId, statuses.size());
        return new MediaPreparationResult(0, 0, statuses.size(), List.copyOf(statuses));
    }

    /**
     * 上传单个 PHOTO 并回写 UPLOADED + wechatUrl，成功时清空 failureReason (AC2).
     *
     * <p>W11: 成功日志含 tweetId/mediaId/fileName/sizeBytes/urlHost/elapsedMs 摘要，
     * 不含完整微信 URL / token / 绝对路径 (N4)。
     */
    private String uploadAndWriteBack(TweetMediaArchiveWriter writer, String tweetId,
                                      LocalDateTime effectivePublishedAt, SidecarRef ref,
                                      String mediaId, Path localFile) {
        long startNanos = System.nanoTime();
        WeChatBodyImageUploadProbe.UploadedBodyImage uploaded = uploadProbe.upload(localFile);
        String wechatUrl = uploaded.url().trim();
        requireWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                original -> original.toBuilder()
                        .uploadStatus(MediaUploadStatus.UPLOADED)
                        .wechatUrl(wechatUrl)
                        .failureReason(null)
                        .build());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("微信正文图片上传回写完成: tweetId={}, mediaId={}, fileName={}, sizeBytes={}, urlHost={}, status=UPLOADED, elapsedMs={}",
                tweetId, mediaId, uploaded.fileName(), uploaded.sizeBytes(), uploaded.urlHost(), elapsedMs);
        return wechatUrl;
    }

    /**
     * Story 10.11: VIDEO 生产准备总开关 — 适配器 Bean 存在且 {@code wechat.mp.video.enabled=true}。
     * false 时 VIDEO 保持 Story 10.8 降级语义 (SKIPPED + 预览图/原文链接文案), 零上传请求。
     */
    private boolean videoPreparationEnabled() {
        return videoAdapter != null
                && (weChatProperties == null || weChatProperties.getVideo().isEnabled());
    }

    /**
     * Story 10.11: VIDEO 幂等判据 — sidecar 已有有效 mediaId 且 wechatPrepare=SUCCEEDED。
     * <p>幂等键即 sidecar mediaId 本身 (微信永久素材无删除 API, 不做"先查后传"的服务器端去重)。
     */
    private static boolean isVideoPrepared(TweetMedia sidecarState) {
        return sidecarState != null
                && sidecarState.getWechatPrepare() != null
                && sidecarState.getWechatPrepare().getStatus() == MediaPhaseStatus.SUCCEEDED
                && hasText(sidecarState.getWechatVideoMediaId());
    }

    /**
     * Story 10.11: 上传 VIDEO 永久素材并回写 mediaId + {@code wechatPrepare=SUCCEEDED}。
     *
     * <p>OQ1: 素材 title = 文章标题 + tweetId 后缀 (截断至 64 码点), description = 文章简介
     * (截断至 120 码点); 缺失时确定性兜底。回读 (materialVideoInfo) 已在适配器内 best-effort
     * 完成, 失败仅 warn, 不影响成功语义。回写经 requireWriteBack: 未命中抛 Retryable
     * (已消耗微信配额取得的 mediaId 不能被放弃), 由共享 catch 记 RETRY_SCHEDULED 供下轮重试
     * (重复上传产生的微信后台孤儿素材需人工清理, 为 spec 已接受代价, 不做状态权威性妥协)。
     */
    private void uploadVideoAndWriteBack(TweetMediaArchiveWriter writer, String tweetId,
                                         LocalDateTime effectivePublishedAt, SidecarRef ref,
                                         String mediaId, TweetMedia sidecarState, Path archiveDir,
                                         String articleTitle, String articleDigest) {
        long startNanos = System.nanoTime();
        String title = videoMaterialTitle(articleTitle, tweetId);
        String description = videoMaterialDescription(articleDigest, tweetId);
        String localPath = sidecarState != null ? sidecarState.getLocalPath() : null;
        Path localFile = resolveLocalFile(archiveDir, localPath);
        WeChatVideoMediaAdapter.UploadedVideo uploaded =
                videoAdapter.uploadPermanentVideo(localFile, title, description);
        String videoMediaId = uploaded.mediaId().trim();
        requireWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                original -> {
                    MediaPhaseState prev = original.getWechatPrepare() != null
                            ? original.getWechatPrepare() : MediaPhaseState.notStarted();
                    MediaPhaseState succeeded = MediaPhaseState.builder()
                            .status(MediaPhaseStatus.SUCCEEDED)
                            .attempt(Math.max(prev.getAttempt(), 0) + 1)
                            .nextRetryAt(null)
                            .errorClass(null)
                            .errorCode(null)
                            .errorSummary(null)
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return original.toBuilder()
                            .uploadStatus(MediaUploadStatus.UPLOADED)
                            .wechatVideoMediaId(videoMediaId)
                            .failureReason(null)
                            .wechatPrepare(succeeded)
                            .build();
                });
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("微信永久视频素材上传回写完成: tweetId={}, mediaId={}, fileName={}, sizeBytes={}, "
                        + "videoMediaIdPrefix={}, readbackSucceeded={}, status=UPLOADED, elapsedMs={}",
                tweetId, mediaId, uploaded.fileName(), uploaded.sizeBytes(),
                uploaded.mediaId().length() <= 6 ? uploaded.mediaId() : uploaded.mediaId().substring(0, 6),
                uploaded.readbackSucceeded(), elapsedMs);
    }

    /**
     * Story 10.11 (OQ1): 素材 title = 文章标题 + tweetId 后缀, 总长 ≤64 码点; 缺失时确定性兜底.
     * <p>先把标题截到 64-suffix 码点再追加完整后缀 — 保证 {@code tw-{tweetId}} 幂等锚点
     * 永远完整保留, 长标题不会截出残段 (suffix 动态长度, 防负取 max(0, ...))。
     */
    private static String videoMaterialTitle(String articleTitle, String tweetId) {
        String suffix = VIDEO_MATERIAL_TITLE_SUFFIX + tweetId;
        int baseMax = Math.max(0, VIDEO_TITLE_MAX_CODE_POINTS - suffix.length());
        String base = hasText(articleTitle) ? singleLine(articleTitle) : VIDEO_MATERIAL_FALLBACK_TITLE;
        return TextTruncateUtil.truncateByCodePoints(base, baseMax) + suffix;
    }

    /** Story 10.11 (OQ1): 素材 description = 文章简介截断至 120 码点; 缺失时确定性兜底. */
    private static String videoMaterialDescription(String articleDigest, String tweetId) {
        String base = hasText(articleDigest) ? singleLine(articleDigest)
                : VIDEO_MATERIAL_FALLBACK_TITLE + VIDEO_MATERIAL_TITLE_SUFFIX + tweetId;
        return TextTruncateUtil.truncateByCodePoints(base, VIDEO_DESCRIPTION_MAX_CODE_POINTS);
    }

    /** 换行/制表符压成空格 (素材命名不得含换行, 镜像 sanitizeReason 单行化口径, 不脱敏 URL — 素材库需可读). */
    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    /**
     * 回写并要求命中 (仅上传成功路径): 未命中抛 Retryable —— 已消耗微信配额取得的 URL
     * 不能被分类为不可重试而放弃 (CR 2026-08-28 D2 决策)，交由上层单媒体 catch 降级并暴露
     * retryable=true 供下轮重试。
     */
    private void requireWriteBack(TweetMediaArchiveWriter writer, String tweetId,
                                  LocalDateTime effectivePublishedAt, SidecarRef ref,
                                  String mediaId, UnaryOperator<TweetMedia> mutation) {
        boolean updated = writeBack(writer, tweetId, effectivePublishedAt, ref, mutation);
        if (!updated) {
            throw new RetryableException(ErrorCode.RETRYABLE_ERROR,
                    "微信上传状态 sidecar 回写未命中: tweetId=" + tweetId + " mediaId=" + mediaId);
        }
    }

    /**
     * skip / 本地未就绪路径的软失败回写: 这些路径未消耗微信配额，回写 miss 或异常只告警，
     * 不改变该媒体本来的 SKIPPED/FAILED 语义、不虚增 failCount (CR 2026-08-28)。
     */
    private void tryWriteBack(TweetMediaArchiveWriter writer, String tweetId,
                              LocalDateTime effectivePublishedAt, SidecarRef ref,
                              String mediaId, UnaryOperator<TweetMedia> mutation) {
        try {
            boolean updated = writeBack(writer, tweetId, effectivePublishedAt, ref, mutation);
            if (!updated) {
                log.warn("微信上传状态 sidecar 回写未命中(状态按本媒体结果记录，未落账): tweetId={}, mediaId={}",
                        tweetId, mediaId);
            }
        } catch (Exception e) {
            log.warn("回写状态到 sidecar 失败: tweetId={}, mediaId={}, cause={}",
                    tweetId, mediaId, truncateReason(e));
        }
    }

    /**
     * sidecar 回写分流: 按定位到的 sidecar 项回写 —— 项 id 非空按 id 回写，否则按项在 sidecar
     * 中的实际下标回写。决策依据是 {@link SidecarRef} 定位结果 (与状态判定绑定同一项，
     * CR 2026-08-28 错位修复；sidecar 项可能已由 Archiver 写入 synthetic id)。
     */
    private boolean writeBack(TweetMediaArchiveWriter writer, String tweetId,
                              LocalDateTime effectivePublishedAt, SidecarRef ref,
                              UnaryOperator<TweetMedia> mutation) {
        if (ref == null) {
            return false;
        }
        if (hasText(ref.item().getId())) {
            return writer.updateMedia(tweetId, effectivePublishedAt, ref.item().getId(), mutation);
        }
        return writer.updateMediaAtIndex(tweetId, effectivePublishedAt, ref.actualIndex(), mutation);
    }

    /**
     * 失败路径的 FAILED 回写软失败: 回写异常只告警，不掩盖原始失败原因 (镜像 Archiver.logFailedDownload)。
     *
     * <p>Story 10.8: 同时直写 {@code wechatPrepare} 阶段证据 — 可重试分类写
     * {@code RETRY_SCHEDULED}(attempt/nextRetryAt/errorClass=RETRYABLE), 终态分类写
     * {@code FAILED_TERMINAL}/{@code ENVIRONMENT_BLOCKED}(errorClass=TERMINAL/nextRetryAt=null)。
     */
    private void safeWriteBackFailed(TweetMediaArchiveWriter writer, String tweetId,
                                     LocalDateTime effectivePublishedAt, SidecarRef ref,
                                     String mediaId, String reason,
                                     MediaPreparationResult.FailureClass failureClass, String errorCode) {
        try {
            writeBack(writer, tweetId, effectivePublishedAt, ref,
                    original -> original.toBuilder()
                            .uploadStatus(MediaUploadStatus.FAILED)
                            .failureReason(reason)
                            .wechatPrepare(applyPrepareFailurePhase(original, failureClass,
                                    errorCode, reason))
                            .build());
        } catch (Exception e) {
            log.warn("回写失败状态到 sidecar 失败: tweetId={}, mediaId={}, cause={}",
                    tweetId, mediaId, truncateReason(e));
        }
    }

    /** Story 10.8: 异常 → 四分类 (Retryable→RETRYABLE/仅 45009→RATE_LIMITED; NonRetryable 仅 40164→ENVIRONMENT_BLOCKED; 其余→PERMANENT). */
    private static MediaPreparationResult.FailureClass classifyFailure(Exception e) {
        if (e instanceof RetryableException r) {
            return r.getErrorCode() == ErrorCode.WECHAT_RATE_LIMITED
                    ? MediaPreparationResult.FailureClass.RATE_LIMITED
                    : MediaPreparationResult.FailureClass.RETRYABLE;
        }
        if (e instanceof NonRetryableException n) {
            return n.getErrorCode() == ErrorCode.WECHAT_ENVIRONMENT_BLOCKED
                    ? MediaPreparationResult.FailureClass.ENVIRONMENT_BLOCKED
                    : MediaPreparationResult.FailureClass.PERMANENT;
        }
        return MediaPreparationResult.FailureClass.PERMANENT;
    }

    // ===== Story 10.12: wechat_prepare 阶段指标 (观测旁路; mediaMetrics=null 时静默关闭) =====

    /** wechat_prepare 阶段成功 (PHOTO uploadimg / VIDEO materialFileUpload). */
    private void recordPrepareSucceeded(TweetMedia media) {
        MediaMetrics.MediaType type = metricTypeOf(media);
        if (mediaMetrics != null && type != null) {
            mediaMetrics.recordSucceeded(type, MediaMetrics.MediaPhase.WECHAT_PREPARE);
        }
    }

    /** wechat_prepare 阶段终态失败 (PERMANENT 分类: 本地未就绪/不可重试异常). */
    private void recordPrepareTerminal(TweetMedia media) {
        recordPrepareFailure(media, MediaPreparationResult.FailureClass.PERMANENT);
    }

    /**
     * 按四分类计数 wechat_prepare 失败: RETRYABLE/RATE_LIMITED → {@code retry_scheduled}
     * (既有重试钩子实际排期), ENVIRONMENT_BLOCKED → {@code blocked} (立即终态零重试),
     * PERMANENT → {@code failed_terminal}。
     */
    private void recordPrepareFailure(TweetMedia media, MediaPreparationResult.FailureClass failureClass) {
        MediaMetrics.MediaType type = metricTypeOf(media);
        if (mediaMetrics == null || type == null || failureClass == null) {
            return;
        }
        switch (failureClass) {
            case RETRYABLE -> mediaMetrics.record(type, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                    MediaMetrics.MediaOutcome.RETRY_SCHEDULED, MediaMetrics.MediaErrorClass.RETRYABLE);
            case RATE_LIMITED -> mediaMetrics.record(type, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                    MediaMetrics.MediaOutcome.RETRY_SCHEDULED, MediaMetrics.MediaErrorClass.RATE_LIMITED);
            case ENVIRONMENT_BLOCKED -> mediaMetrics.record(type, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                    MediaMetrics.MediaOutcome.BLOCKED, MediaMetrics.MediaErrorClass.ENVIRONMENT_BLOCKED);
            case PERMANENT -> mediaMetrics.record(type, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                    MediaMetrics.MediaOutcome.FAILED_TERMINAL, MediaMetrics.MediaErrorClass.PERMANENT);
            default -> log.warn("未纳入指标矩阵的失败分类, 计数丢弃 (观测旁路不抛错): type={}, failureClass={}",
                    type, failureClass);
        }
    }

    /** 指标类型映射 — 仅 PHOTO/VIDEO 入指标, GIF/UNKNOWN 恒为 null (Story 10.12 Never). */
    private static MediaMetrics.MediaType metricTypeOf(TweetMedia media) {
        if (media == null) {
            return null;
        }
        return switch (media.getType()) {
            case PHOTO -> MediaMetrics.MediaType.PHOTO;
            case VIDEO -> MediaMetrics.MediaType.VIDEO;
            default -> null;
        };
    }

    /** Story 10.8: 阶段证据 errorCode 来源 — AggregatorException 取枚举名, 其余取异常类简名 (不含原始 message, N4). */
    private static String errorCodeOf(Exception e) {
        if (e instanceof AggregatorException a && a.getErrorCode() != null) {
            return a.getErrorCode().name();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * Story 10.8: 基于既有阶段状态计算失败后的 {@code wechatPrepare} 证据.
     *
     * <p>attempt 单调递增 (max(prev,0)+1); 可重试分类 (RETRYABLE/RATE_LIMITED) 写
     * {@code RETRY_SCHEDULED} + {@code nextRetryAt=now+delayForAttempt(attempt)} (退避来源
     * {@code task.retry.*}, 不新增第二套配置); 终态分类 (ENVIRONMENT_BLOCKED/PERMANENT) 写
     * {@code ENVIRONMENT_BLOCKED}/{@code FAILED_TERMINAL} + nextRetryAt=null + errorClass=TERMINAL。
     */
    private MediaPhaseState applyPrepareFailurePhase(TweetMedia original,
                                                     MediaPreparationResult.FailureClass failureClass,
                                                     String errorCode, String safeSummary) {
        MediaPhaseState prev = original.getWechatPrepare() != null
                ? original.getWechatPrepare() : MediaPhaseState.notStarted();
        int attempt = Math.max(prev.getAttempt(), 0) + 1;
        LocalDateTime now = LocalDateTime.now();
        MediaPhaseState.MediaPhaseStateBuilder builder = MediaPhaseState.builder()
                .attempt(attempt)
                .errorCode(errorCode)
                .errorSummary(safeSummary)
                .updatedAt(now);
        if (failureClass == MediaPreparationResult.FailureClass.RETRYABLE
                || failureClass == MediaPreparationResult.FailureClass.RATE_LIMITED) {
            return builder.status(MediaPhaseStatus.RETRY_SCHEDULED)
                    .nextRetryAt(now.plus(retryPolicy.delayForAttempt(attempt), java.time.temporal.ChronoUnit.MILLIS))
                    .errorClass("RETRYABLE")
                    .build();
        }
        return builder.status(failureClass == MediaPreparationResult.FailureClass.ENVIRONMENT_BLOCKED
                        ? MediaPhaseStatus.ENVIRONMENT_BLOCKED
                        : MediaPhaseStatus.FAILED_TERMINAL)
                .nextRetryAt(null)
                .errorClass("TERMINAL")
                .build();
    }

    /**
     * Story 10.8: 生成器级 {@code RETRY_SCHEDULED} 阶段证据回写 (静态, 供两个媒体感知生成器复用).
     *
     * <p>覆盖 preparer 逐媒体 catch 之外的路径 (如 preparer 级崩溃异常逃逸): 读取 canonical
     * sidecar, 对所有「PHOTO/VIDEO 且 wechatPrepare 尚未进入 SUCCEEDED/终态」的媒体软失败回写
     * RETRY_SCHEDULED 证据。Story 10.11: VIDEO 加入同一驱动源 (耗尽判定在
     * {@code ContentScheduler.convergeExhaustedPrepare}, 不另起重试循环)。回写异常只告警不抛出
     * — 证据落账不得阻断任务级延迟重试本身。
     *
     * @return 是否至少回写了一个媒体 (测试与调度器观测用)
     */
    public static boolean markSidecarPrepareRetryScheduled(TweetMediaArchiveWriter writer,
                                                           String tweetId, LocalDateTime publishedAt,
                                                           RetryPolicyProperties retryPolicy,
                                                           String errorCode, String rawSummary) {
        LocalDateTime effectivePublishedAt = publishedAt != null ? publishedAt : LocalDateTime.now();
        String safeSummary = sanitizeReason(rawSummary);
        try {
            Optional<MediaArchiveRecord> sidecar = writer.readSidecar(tweetId, effectivePublishedAt);
            if (sidecar.isEmpty() || sidecar.get().getMedia() == null) {
                return false;
            }
            boolean mutated = false;
            for (TweetMedia m : sidecar.get().getMedia()) {
                if (m == null || !hasText(m.getId())
                        || !(m.getType() == TweetMediaType.PHOTO || m.getType() == TweetMediaType.VIDEO)) {
                    continue;
                }
                MediaPhaseState prev = m.getWechatPrepare() != null
                        ? m.getWechatPrepare() : MediaPhaseState.notStarted();
                // 已是 RETRY_SCHEDULED 的媒体跳过: preparer 已写证据且更具体,
                // 重写会 attempt 双计、nextRetryAt 重置并覆盖细粒度 failureReason
                if (prev.getStatus() == MediaPhaseStatus.SUCCEEDED
                        || prev.getStatus() == MediaPhaseStatus.RETRY_SCHEDULED
                        || prev.getStatus() == MediaPhaseStatus.FAILED_TERMINAL
                        || prev.getStatus() == MediaPhaseStatus.ENVIRONMENT_BLOCKED) {
                    continue;
                }
                MediaPhaseState phase = retryPhaseFor(m, retryPolicy, errorCode, safeSummary);
                boolean updated = writer.updateMedia(tweetId, effectivePublishedAt, m.getId(),
                        original -> original.toBuilder()
                                .uploadStatus(MediaUploadStatus.FAILED)
                                .failureReason(safeSummary)
                                .wechatPrepare(phase)
                                .build());
                mutated = mutated || updated;
            }
            return mutated;
        } catch (Exception e) {
            log.warn("RETRY_SCHEDULED 阶段证据回写失败(不阻断任务级重试): tweetId={}, cause={}",
                    tweetId, TextTruncateUtil.getRootMessage(e));
            return false;
        }
    }

    /** Story 10.8: 无实例状态的 RETRY_SCHEDULED 阶段计算 (静态 helper 复用, 退避来源 task.retry.*). */
    private static MediaPhaseState retryPhaseFor(TweetMedia original, RetryPolicyProperties retryPolicy,
                                                 String errorCode, String safeSummary) {
        MediaPhaseState prev = original.getWechatPrepare() != null
                ? original.getWechatPrepare() : MediaPhaseState.notStarted();
        int attempt = Math.max(prev.getAttempt(), 0) + 1;
        LocalDateTime now = LocalDateTime.now();
        return MediaPhaseState.builder()
                .status(MediaPhaseStatus.RETRY_SCHEDULED)
                .attempt(attempt)
                .nextRetryAt(now.plus(retryPolicy.delayForAttempt(attempt), java.time.temporal.ChronoUnit.MILLIS))
                .errorClass("RETRYABLE")
                .errorCode(errorCode)
                .errorSummary(safeSummary)
                .updatedAt(now)
                .build();
    }

    /**
     * 从 sidecar 定位当前媒体权威状态与回写目标: 传入对象 id 非空时按 id 匹配，否则按下标兜底。
     * 状态判定 (幂等/本地就绪) 与回写目标必须使用同一定位结果，防止输入列表与 sidecar
     * 错位时跨媒体误写 (CR 2026-08-28)。未命中返回 null (PHOTO 按未就绪 FAILED 处理，
     * 非 PHOTO 软失败跳过落账, D3: 返回值需 null-check)。
     */
    private static SidecarRef findSidecarRef(List<TweetMedia> sidecarMedia, TweetMedia media, int index) {
        if (hasText(media.getId())) {
            for (int i = 0; i < sidecarMedia.size(); i++) {
                TweetMedia candidate = sidecarMedia.get(i);
                if (candidate != null && media.getId().equals(candidate.getId())) {
                    return new SidecarRef(candidate, i);
                }
            }
        }
        if (index >= 0 && index < sidecarMedia.size() && sidecarMedia.get(index) != null) {
            return new SidecarRef(sidecarMedia.get(index), index);
        }
        return null;
    }

    /** sidecar 定位结果: 命中项 + 该项在 sidecar 列表中的实际下标 (回写目标与状态判定绑定). */
    private record SidecarRef(TweetMedia item, int actualIndex) {
    }

    /** 幂等判据: UPLOADED 且 wechatUrl 非空 (D3: sidecar 读出对象经 backfillDefaults 保证枚举非 null). */
    private static boolean isUploaded(TweetMedia sidecarState) {
        return sidecarState != null
                && sidecarState.getUploadStatus() == MediaUploadStatus.UPLOADED
                && hasText(sidecarState.getWechatUrl());
    }

    /**
     * PHOTO 本地就绪校验 (Story 8.1 Spike 决策表 + 7.4 shouldSkipDownload 四重校验模式)。
     *
     * @return null 表示就绪；非 null 为 FAILED 原因 (紧凑, 不含完整路径, N4)
     */
    private String localNotReadyReason(TweetMedia sidecarState, Path archiveDir) {
        if (sidecarState == null) {
            return "local file missing (sidecar 未命中该媒体)";
        }
        if (sidecarState.getDownloadStatus() != MediaDownloadStatus.DOWNLOADED) {
            // D3: 外部构造对象枚举可能为 null, null 时按非 DOWNLOADED 报原因
            return "local file missing (downloadStatus="
                    + (sidecarState.getDownloadStatus() == null ? "null" : sidecarState.getDownloadStatus().name())
                    + ")";
        }
        if (!hasText(sidecarState.getLocalPath())) {
            return "local file missing (localPath 为空)";
        }
        Path file = resolveLocalFile(archiveDir, sidecarState.getLocalPath());
        if (file == null) {
            return "local file missing (localPath 非法)";
        }
        try {
            if (!Files.isRegularFile(file) || Files.size(file) <= 0) {
                return "local file missing (文件不存在或为空)";
            }
        } catch (Exception e) {
            return "local file missing (文件校验失败: " + truncateReason(e) + ")";
        }
        return null;
    }

    /**
     * 解析 localPath 到归档目录下的实际文件: localPath 形如
     * {@code media/twitter/{date}/{tweetId}/{filename}}，文件名段解析到 archiveDir，
     * normalize + startsWith 防路径穿越 (复用 Writer 防御模式)。非法返回 null。
     */
    private static Path resolveLocalFile(Path archiveDir, String localPath) {
        try {
            Path fileName = Path.of(localPath).getFileName();
            if (fileName == null) {
                return null;
            }
            Path normalizedDir = archiveDir.normalize();
            Path file = normalizedDir.resolve(fileName).normalize();
            return file.startsWith(normalizedDir) ? file : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 降级原因按类型区分并携带 8.2 决策表 degradeReason token，供 8.5 渲染器机器可读消费 (AC5). */
    private static String skipReasonForType(TweetMediaType type) {
        if (type == TweetMediaType.VIDEO) {
            return VIDEO_SKIP_REASON;
        }
        if (type == TweetMediaType.GIF) {
            return GIF_SKIP_REASON;
        }
        return UNKNOWN_TYPE_SKIP_REASON;
    }

    /** provider id 优先，缺失时 synthetic {@code tweetId:index:type} (镜像 Archiver.effectiveMediaId). */
    private static String effectiveMediaId(String tweetId, TweetMedia media, int index) {
        if (hasText(media.getId())) {
            return media.getId();
        }
        TweetMediaType type = media.getType();
        String suffix = type == TweetMediaType.VIDEO ? "video"
                : type == TweetMediaType.GIF ? "gif" : "photo";
        return tweetId + ":" + index + ":" + suffix;
    }

    private static String truncateReason(Exception e) {
        String message = e instanceof RetryableException || e instanceof NonRetryableException
                ? e.getMessage()
                : TextTruncateUtil.getRootMessage(e);
        return sanitizeReason(message);
    }

    /**
     * N4 脱敏 + 单行化后截断 (CR 2026-08-28): 完整 URL 替换为 {@code <url>}
     * (镜像 7.5 Gate sanitizeAndTruncateReason)，换行/制表符压成空格防日志伪造
     * (外部异常 message 可能含多行内容)。防止 failureReason 把完整微信 URL 带进 sidecar 与日志。
     */
    private static String sanitizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String singleLine = reason.replaceAll("[\\r\\n\\t]", " ");
        return TextTruncateUtil.truncateForLog(
                URL_PATTERN.matcher(singleLine).replaceAll("<url>"), FAILURE_REASON_MAX_LENGTH);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
