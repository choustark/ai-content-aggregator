package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import lombok.extern.slf4j.Slf4j;
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
 *       (sidecar 是上传状态唯一权威源，防配额浪费)</li>
 *   <li><b>VIDEO/GIF 不走图片路径</b> — 一律 SKIPPED + 降级原因 (Story 8.2 结论: 内嵌未证实，
 *       按预览图/原文链接降级)，绝不调用图片上传接口</li>
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

    /**
     * 构造器注入依赖.
     *
     * @param uploadProbe   微信正文图片上传探针 (Story 8.1，同 wechat.mp.enabled 开关，必共存)
     * @param archiveWriter sidecar 写入器 (twitter.media.enabled 独立开关，可能未注册；
     *                      缺失时 prepareMedia 显式 fail-fast)
     */
    public WeChatMediaPreparer(WeChatBodyImageUploadProbe uploadProbe,
                               Optional<TweetMediaArchiveWriter> archiveWriter) {
        this.uploadProbe = uploadProbe;
        this.archiveWriter = archiveWriter;
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
                            sidecarState.getWechatUrl(), "已上传，幂等跳过", false));
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
                    statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(
                            mediaId, notReadyReason, false));
                    failCount++;
                    continue;
                }

                String wechatUrl = uploadAndWriteBack(writer, tweetId, effectivePublishedAt, ref, mediaId,
                        resolveLocalFile(archiveDir, sidecarState.getLocalPath()));
                statuses.add(MediaPreparationResult.MediaPreparationStatus.uploaded(mediaId, wechatUrl));
                successCount++;
            } catch (Exception e) {
                // AD-5: 单媒体失败降级 — 只回写该媒体 FAILED，继续处理后续媒体
                String reason = truncateReason(e);
                boolean retryable = e instanceof RetryableException;
                log.warn("微信正文图片准备失败(单媒体降级): tweetId={}, mediaId={}, retryable={}, reason={}",
                        tweetId, mediaId, retryable, reason);
                safeWriteBackFailed(writer, tweetId, effectivePublishedAt, ref, mediaId, reason);
                statuses.add(MediaPreparationResult.MediaPreparationStatus.failed(mediaId, reason, retryable));
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

    /** 失败路径的 FAILED 回写软失败: 回写异常只告警，不掩盖原始失败原因 (镜像 Archiver.logFailedDownload). */
    private void safeWriteBackFailed(TweetMediaArchiveWriter writer, String tweetId,
                                     LocalDateTime effectivePublishedAt, SidecarRef ref,
                                     String mediaId, String reason) {
        try {
            writeBack(writer, tweetId, effectivePublishedAt, ref,
                    original -> original.toBuilder()
                            .uploadStatus(MediaUploadStatus.FAILED)
                            .failureReason(reason)
                            .build());
        } catch (Exception e) {
            log.warn("回写失败状态到 sidecar 失败: tweetId={}, mediaId={}, cause={}",
                    tweetId, mediaId, truncateReason(e));
        }
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
