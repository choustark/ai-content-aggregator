package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.media.model.MediaPublishabilityDecision;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Story 7.5: 推文可发布性判定门 — 纯决策组件，不执行下载/上传/发布动作.
 *
 * <p><b>核心职责 (AC1-AC10):</b>
 * <ul>
 *   <li>推文级判定: 基于 Tweet 文本字段 (content/rawText/formattedText) + accessStatus 聚合状态</li>
 *   <li>媒体级判定: 基于决策表（M1-M9 优先级）判断每个媒体项的可发布性</li>
 *   <li>sidecar 回写: 将媒体级判定结果回写 media.json publishability 字段（软失败，不阻塞返回）</li>
 *   <li>输出结构化决策: 返回 {@link PublishabilityResult} 供调用方（Epic 8 Pipeline）消费</li>
 * </ul>
 *
 * <p><b>关键设计决策:</b>
 * <ul>
 *   <li><b>纯决策组件 (AD-10)</b> — 不调用 LLM / 不写 Redis / 不修改 downloadStatus/uploadStatus /
 *       不调用 MediaDownloadClient / 不绕过任务队列/异常体系/人工审核语义</li>
 *   <li><b>副作用受控 (AC5)</b> — 唯一副作用是 sidecar publishability 回写（经 TweetMediaArchiveWriter.updateMedia），
 *       回写失败软失败不阻塞 evaluate 返回</li>
 *   <li><b>推文级 BLOCKED 边界 (AC3)</b> — 只有源文本不可用（文本三字段全 blank）或访问状态受限触发整条 BLOCKED；
 *       媒体级合规受限（availability 非 Available）按单媒体降级（该媒体 BLOCKED + 推文 DEGRADED）</li>
 *   <li><b>聚合优先级 (AC3)</b> — BLOCKED > DEGRADED > UNKNOWN > PUBLISHABLE</li>
 *   <li><b>双源输入容错 (AC7)</b> — 媒体状态输入经 MediaRuntimeRecoveryService.getMediaStates（Redis 优先 + sidecar fallback）</li>
 * </ul>
 *
 * <p>引用源: Story 7.5 (实现) / ARCHITECTURE-SPINE AD-5/AD-6/AD-7/AD-10 / Story 7.4 双源查询 / Story 7.1 updateMedia.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class TweetPublishabilityGate {

    /** 日志中根因截断长度 (N4 + R3-1). */
    private static final int LOG_MSG_MAX_LENGTH = 200;

    /** 合规受限前缀常量 (M1 判定，Story 6.1 契约，ApifyDiscoveryClient.mediaFailureReason 写入格式). */
    private static final String AVAILABILITY_PREFIX = "provider media availability:";

    /** URL 脱敏模式 (N4): gateReason 不保留完整 variant/source/preview URL. */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final MediaRuntimeRecoveryService recoveryService;
    private final TweetMediaArchiveWriter archiveWriter;

    /**
     * 评估推文可发布性 (AC1-AC10).
     *
     * <p>入口方法：tweetId blank → UNKNOWN 容错；否则查询媒体运行时状态 → 推文级 + 媒体级判定 → 回写 sidecar → 返回结果.
     *
     * @param tweet 推文领域模型；为 null 时返回 UNKNOWN 结果
     * @return 结构化判定结果，包含推文级状态、媒体级决策及可读原因
     */
    public PublishabilityResult evaluate(Tweet tweet) {
        if (tweet == null || !StringUtils.hasText(tweet.getId())) {
            log.warn("推文为 null 或 ID 为空, 返回 UNKNOWN 状态: tweetId={}", tweet != null ? tweet.getId() : "null");
            return PublishabilityResult.unknown(tweet != null ? tweet.getId() : null);
        }

        String tweetId = tweet.getId();
        long start = System.nanoTime();

        // effectivePublishedAt 模式（复用 Story 7.2/7.4 防跨日）
        LocalDateTime effectivePublishedAt = tweet.getPublishedAt() != null ? tweet.getPublishedAt() : LocalDateTime.now();

        // 查询媒体运行时状态（双源容错，AC7）
        List<MediaRuntimeItem> mediaStates = recoveryService.getMediaStates(tweetId, effectivePublishedAt);

        // 核心判定逻辑
        PublishabilityResult result = evaluate(tweet, mediaStates);

        // 回写 sidecar（软失败，不阻塞返回，AC6）
        writeBackToSidecar(tweetId, effectivePublishedAt, result.getMediaDecisions());

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        logEvaluateCompletion(tweetId, result, elapsedMs);

        return result;
    }

    /**
     * 核心评估方法（包内可见，测试与调用方显式传状态，避免 mock RecoveryService 才能测决策表）.
     *
     * @param tweet        推文模型
     * @param mediaStates  媒体运行时状态列表（可能为空，由调用方构造或 getMediaStates 返回）
     * @return 判定结果
     */
    PublishabilityResult evaluate(Tweet tweet, List<MediaRuntimeItem> mediaStates) {
        if (tweet == null) {
            return PublishabilityResult.unknown(null);
        }

        String tweetId = tweet.getId();
        List<MediaPublishabilityDecision> mediaDecisions = new ArrayList<>();

        // 媒体级判定（AC2 决策表，逐个媒体评估）
        if (mediaStates != null) {
            LocalDateTime effectivePublishedAt = tweet.getPublishedAt() != null ? tweet.getPublishedAt() : LocalDateTime.now();
            for (MediaRuntimeItem item : mediaStates) {
                if (item != null) {
                    MediaPublishabilityDecision decision = evaluateMedia(tweetId, effectivePublishedAt, item);
                    mediaDecisions.add(decision);
                    logMediaDecisionIfNeeded(tweetId, decision);
                }
            }
        }

        appendMissingTweetMediaDecisions(tweet, mediaDecisions);

        // 推文级判定（AC3 聚合优先级）
        PublishabilityStatus tweetStatus = aggregateTweetStatus(tweet, mediaDecisions);
        String tweetReason = buildTweetReason(tweet, mediaDecisions, tweetStatus);

        return PublishabilityResult.builder()
                .tweetId(tweetId)
                .tweetStatus(tweetStatus)
                .tweetReason(tweetReason)
                .mediaDecisions(mediaDecisions)
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 推文级 BLOCKED 判定（T2.3）.
     *
     * <p>源文本不可用（content/rawText/formattedText 全 blank）或访问状态受限 → BLOCKED.
     */
    private boolean isTweetBlocked(Tweet tweet) {
        if (tweet == null) {
            return false;
        }

        // T2: 结构化访问受限 → BLOCKED
        if (tweet.hasStructuredAccessBlock()) {
            return true;
        }

        // T1: 文本三字段全 blank → BLOCKED
        boolean textBlank = !StringUtils.hasText(tweet.getContent()) &&
                !StringUtils.hasText(tweet.getRawText()) &&
                !StringUtils.hasText(tweet.getFormattedText());

        return textBlank;
    }

    /**
     * 推文级状态聚合（T2.4）.
     *
     * <p>优先级: BLOCKED > DEGRADED > UNKNOWN > PUBLISHABLE（AC3）.
     */
    private PublishabilityStatus aggregateTweetStatus(Tweet tweet, List<MediaPublishabilityDecision> mediaDecisions) {
        // 优先级 1: 推文级 BLOCKED（文本不可用或访问状态受限）
        if (isTweetBlocked(tweet)) {
            return PublishabilityStatus.BLOCKED;
        }

        if (mediaDecisions == null || mediaDecisions.isEmpty()) {
            // 无媒体且文本可用 → PUBLISHABLE
            return PublishabilityStatus.PUBLISHABLE;
        }

        // 优先级 2: DEGRADED（任一媒体 DEGRADED 或 BLOCKED）
        boolean hasDegradedOrBlocked = mediaDecisions.stream()
                .filter(Objects::nonNull)
                .anyMatch(d -> d.getStatus() == PublishabilityStatus.DEGRADED || d.getStatus() == PublishabilityStatus.BLOCKED);

        if (hasDegradedOrBlocked) {
            return PublishabilityStatus.DEGRADED;
        }

        // 优先级 3: UNKNOWN（任一媒体 UNKNOWN 且无降级/阻止信号）
        boolean hasUnknown = mediaDecisions.stream()
                .filter(Objects::nonNull)
                .anyMatch(d -> d.getStatus() == PublishabilityStatus.UNKNOWN);

        if (hasUnknown) {
            return PublishabilityStatus.UNKNOWN;
        }

        // 优先级 4: PUBLISHABLE（全部媒体 PUBLISHABLE 或无媒体）
        return PublishabilityStatus.PUBLISHABLE;
    }

    /**
     * 构建推文级判定原因（reviewer-readable，AC4）.
     */
    private String buildTweetReason(Tweet tweet, List<MediaPublishabilityDecision> mediaDecisions, PublishabilityStatus tweetStatus) {
        if (tweetStatus == PublishabilityStatus.PUBLISHABLE) {
            return null; // PUBLISHABLE 状态无原因
        }

        if (isTweetBlocked(tweet)) {
            // T1/T2: 源文本不可用或访问状态受限
            if (tweet.hasStructuredAccessBlock()) {
                return sanitizeAndTruncateReason("源访问受限: " + accessBlockReason(tweet) + "，需人工确认");
            }
            return "源文本不可用（provider 未返回文本），需人工确认";
        }

        if (tweetStatus == PublishabilityStatus.DEGRADED) {
            // T3: 存在降级/受限媒体，文本草稿仍可发布
            long degradedCount = countByStatus(mediaDecisions, PublishabilityStatus.DEGRADED);
            long blockedCount = countByStatus(mediaDecisions, PublishabilityStatus.BLOCKED);
            long total = degradedCount + blockedCount;
            return String.format("存在 %d 个降级/受限媒体，文本草稿仍可发布", total);
        }

        if (tweetStatus == PublishabilityStatus.UNKNOWN) {
            // T4: 存在未归档媒体，无法评估
            long unknownCount = countByStatus(mediaDecisions, PublishabilityStatus.UNKNOWN);
            return String.format("存在 %d 个未归档媒体，无法评估", unknownCount);
        }

        return null;
    }

    private String accessBlockReason(Tweet tweet) {
        if (tweet == null) {
            return "未知原因";
        }
        if (tweet.getAccessStatus() == null) {
            return "访问状态未知";
        }
        String structuredReason = tweet.structuredAccessBlockReason();
        return structuredReason != null ? structuredReason : "访问状态未知";
    }

    /**
     * 媒体级判定决策表（T3, AC2）.
     *
     * <p>按优先级从高到低取第一个命中（高优先级信号覆盖低优先级）:
     * <ul>
     *   <li>M1: failureReason 前缀 "provider media availability:" → BLOCKED（合规受限）</li>
     *   <li>M2: DOWNLOADED + 文件四重校验通过 → PUBLISHABLE</li>
     *   <li>M3: DOWNLOADED + 文件缺失 → DEGRADED</li>
     *   <li>M4: SKIPPED + VIDEO/GIF → DEGRADED（固定 reason，Story 8.2 spike 决定复现路径）</li>
     *   <li>M5: SKIPPED + 其他 → DEGRADED（沿用 failureReason）</li>
     *   <li>M6: FAILED + retryable → DEGRADED（failureReason + "（可重试）"）</li>
     *   <li>M7: FAILED + 不可重试 → DEGRADED（沿用 failureReason）</li>
     *   <li>M8: PENDING → UNKNOWN（媒体尚未归档）</li>
     *   <li>M9: 不在状态源中（downloadStatus == null） → UNKNOWN</li>
     * </ul>
     */
    private MediaPublishabilityDecision evaluateMedia(String tweetId, LocalDateTime effectivePublishedAt, MediaRuntimeItem item) {
        String mediaId = item.getMediaId();
        if (mediaId == null || mediaId.isBlank()) {
            return createUnknownDecision(mediaId, item.getType(), "媒体 ID 为空，无法评估");
        }

        // M1: 合规判定（BLOCKED）
        if (StringUtils.hasText(item.getFailureReason()) && item.getFailureReason().startsWith(AVAILABILITY_PREFIX)) {
            String reason = sanitizeAndTruncateReason(item.getFailureReason()); // 脱敏 + 截断处理（N4）
            return MediaPublishabilityDecision.builder()
                    .mediaId(mediaId)
                    .type(item.getType())
                    .status(PublishabilityStatus.BLOCKED)
                    .reason(reason)
                    .retryable(item.isRetryable())
                    .build();
        }

        MediaDownloadStatus downloadStatus = item.getDownloadStatus();

        // M9: 不在状态源中（downloadStatus == null）→ UNKNOWN
        if (downloadStatus == null) {
            return createUnknownDecision(mediaId, item.getType(), "媒体不在运行时状态中，无法评估");
        }

        // M2/M3: DOWNLOADED 文件校验
        if (downloadStatus == MediaDownloadStatus.DOWNLOADED) {
            if (item.getLocalPath() != null && item.getLocalPath().isBlank() == false) {
                // 内联文件校验（复用 RecoveryService 的 fileExistsAndNonEmpty 逻辑）
                boolean fileExists = checkFileExists(tweetId, effectivePublishedAt, item.getLocalPath());
                if (fileExists) {
                    // M2: 文件存在且非零字节 → PUBLISHABLE
                    return MediaPublishabilityDecision.builder()
                            .mediaId(mediaId)
                            .type(item.getType())
                            .status(PublishabilityStatus.PUBLISHABLE)
                            .reason(null) // PUBLISHABLE 无原因
                            .retryable(item.isRetryable())
                            .build();
                } else {
                    // M3: 文件缺失 → DEGRADED
                    String fileName = safeFileName(item.getLocalPath());
                    String reason = sanitizeAndTruncateReason("已下载文件缺失，需重新归档: " + fileName);
                    return MediaPublishabilityDecision.builder()
                            .mediaId(mediaId)
                            .type(item.getType())
                            .status(PublishabilityStatus.DEGRADED)
                            .reason(reason)
                            .retryable(item.isRetryable())
                            .build();
                }
            } else {
                // localPath 为空 → 视为文件缺失
                return MediaPublishabilityDecision.builder()
                        .mediaId(mediaId)
                        .type(item.getType())
                        .status(PublishabilityStatus.DEGRADED)
                        .reason(sanitizeAndTruncateReason("已下载文件本地路径为空，需重新归档"))
                        .retryable(item.isRetryable())
                        .build();
            }
        }

        // M4/M5: SKIPPED 分支
        if (downloadStatus == MediaDownloadStatus.SKIPPED) {
            if (item.getType() == TweetMediaType.VIDEO || item.getType() == TweetMediaType.GIF) {
                // M4: VIDEO/GIF → DEGRADED（固定 reason，Story 8.2 spike 决定复现路径）
                String reason = "视频/GIF 复现路径待 Story 8.2 spike 决定，当前以封面/链接降级呈现";
                return MediaPublishabilityDecision.builder()
                        .mediaId(mediaId)
                        .type(item.getType())
                        .status(PublishabilityStatus.DEGRADED)
                        .reason(reason)
                        .retryable(item.isRetryable())
                        .build();
            } else {
                // M5: 其他 SKIPPED → DEGRADED（沿用 failureReason，截断处理）
                String baseReason = StringUtils.hasText(item.getFailureReason()) ? item.getFailureReason() : "媒体下载跳过，原因未知";
                String reason = sanitizeAndTruncateReason(baseReason);
                return MediaPublishabilityDecision.builder()
                        .mediaId(mediaId)
                        .type(item.getType())
                        .status(PublishabilityStatus.DEGRADED)
                        .reason(reason)
                        .retryable(item.isRetryable())
                        .build();
            }
        }

        // M6/M7: FAILED 分支
        if (downloadStatus == MediaDownloadStatus.FAILED) {
            String baseReason = StringUtils.hasText(item.getFailureReason()) ? item.getFailureReason() : "媒体下载失败";
            String reason = sanitizeAndTruncateReason(baseReason + (item.isRetryable() ? "（可重试）" : ""));
            return MediaPublishabilityDecision.builder()
                    .mediaId(mediaId)
                    .type(item.getType())
                    .status(PublishabilityStatus.DEGRADED)
                    .reason(reason)
                    .retryable(item.isRetryable())
                    .build();
        }

        // M8: PENDING → UNKNOWN
        if (downloadStatus == MediaDownloadStatus.PENDING) {
            return createUnknownDecision(mediaId, item.getType(), "媒体尚未归档，无法评估");
        }

        // 兜底：未知状态 → UNKNOWN
        return createUnknownDecision(mediaId, item.getType(), "媒体状态未知，无法评估");
    }

    /** 内联文件校验（复用 RecoveryService 逻辑，避免 N 媒体 N 次双源查询）. */
    private boolean checkFileExists(String tweetId, LocalDateTime publishedAt, String localPath) {
        try {
            var archiveDir = archiveWriter.resolveArchiveDir(tweetId, publishedAt);
            var baseDir = findBaseDir(archiveDir);
            if (baseDir == null) {
                return false;
            }
            var file = baseDir.resolve(localPath).normalize();
            // 路径穿越防御
            if (!file.startsWith(baseDir.normalize())) {
                return false;
            }
            return java.nio.file.Files.isRegularFile(file) && java.nio.file.Files.size(file) > 0;
        } catch (Exception e) {
            log.warn("媒体文件校验失败: tweetId={}, file={}, cause={}",
                    tweetId, safeFileName(localPath),
                    TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return false;
        }
    }

    /** 从 archiveDir 定位归档根目录（复用 RecoveryService 逻辑）. */
    private static java.nio.file.Path findBaseDir(java.nio.file.Path archiveDir) {
        java.nio.file.Path cursor = archiveDir;
        while (cursor != null && cursor.getParent() != null) {
            if ("twitter".equals(cursor.getFileName() == null ? "" : cursor.getFileName().toString())
                    && "media".equals(cursor.getParent().getFileName() == null ? "" : cursor.getParent().getFileName().toString())) {
                return cursor.getParent().getParent();
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /** 创建 UNKNOWN 状态的决策. */
    private MediaPublishabilityDecision createUnknownDecision(String mediaId, TweetMediaType type, String reason) {
        return MediaPublishabilityDecision.builder()
                .mediaId(mediaId)
                .type(type)
                .status(PublishabilityStatus.UNKNOWN)
                .reason(sanitizeAndTruncateReason(reason))
                .retryable(false)
                .build();
    }

    /**
     * 补齐 Tweet.media 中存在但运行时状态缺失的媒体 (T3.6).
     *
     * <p>Redis 快照缺项或调用方显式传入的 mediaStates 不完整时，不能把缺失媒体当作无媒体。
     * 缺项媒体按 UNKNOWN 进入聚合，防止未归档媒体被误判为 PUBLISHABLE。
     */
    private void appendMissingTweetMediaDecisions(Tweet tweet, List<MediaPublishabilityDecision> mediaDecisions) {
        if (tweet == null || tweet.getMedia() == null || tweet.getMedia().isEmpty()) {
            return;
        }
        Set<String> existingIds = new HashSet<>();
        for (MediaPublishabilityDecision decision : mediaDecisions) {
            if (decision != null && StringUtils.hasText(decision.getMediaId())) {
                existingIds.add(decision.getMediaId());
            }
        }

        for (int i = 0; i < tweet.getMedia().size(); i++) {
            TweetMedia media = tweet.getMedia().get(i);
            if (media == null) {
                continue;
            }
            String mediaId = effectiveMediaId(tweet.getId(), media, i);
            if (!existingIds.contains(mediaId)) {
                MediaPublishabilityDecision decision = createUnknownDecision(
                        mediaId, media.getType(), "媒体不在运行时状态中，无法评估");
                mediaDecisions.add(decision);
                logMediaDecisionIfNeeded(tweet.getId(), decision);
            }
        }
    }

    /** 与 TweetMediaArchiver 的兜底 ID 契约保持一致: provider id 优先，否则 tweetId:index:type. */
    private String effectiveMediaId(String tweetId, TweetMedia media, int index) {
        if (media != null && StringUtils.hasText(media.getId())) {
            return media.getId();
        }
        TweetMediaType type = media != null && media.getType() != null ? media.getType() : TweetMediaType.UNKNOWN;
        return tweetId + ":" + index + ":" + type.name().toLowerCase();
    }

    /** sidecar 回写（AC6，软失败不阻塞返回）. */
    private void writeBackToSidecar(String tweetId, LocalDateTime effectivePublishedAt, List<MediaPublishabilityDecision> mediaDecisions) {
        if (mediaDecisions == null || mediaDecisions.isEmpty()) {
            return;
        }

        for (MediaPublishabilityDecision decision : mediaDecisions) {
            if (decision == null || decision.getMediaId() == null) {
                continue;
            }

            try {
                boolean updated = archiveWriter.updateMedia(tweetId, effectivePublishedAt, decision.getMediaId(), original -> {
                    // 回写 publishability 字段，不覆盖 failureReason/downloadStatus/localPath
                    return original.toBuilder()
                            .publishability(decision.getStatus())
                            .build();
                });

                if (!updated) {
                    log.warn("sidecar 回写未命中 mediaId: tweetId={}, mediaId={}", tweetId, decision.getMediaId());
                }
            } catch (Exception e) {
                // 软失败：log.warn 不抛，不阻塞 evaluate 返回与后续媒体处理（AC6）
                log.warn("sidecar 回写失败（软失败）: tweetId={}, mediaId={}, cause={}",
                        tweetId, decision.getMediaId(),
                        TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            }
        }
    }

    /** 日志：评估完成（AC9，含 tweetId + tweetStatus + 四状态计数 + 耗时）. */
    private void logEvaluateCompletion(String tweetId, PublishabilityResult result, long elapsedMs) {
        long publishableCount = countByStatus(result.getMediaDecisions(), PublishabilityStatus.PUBLISHABLE);
        long degradedCount = countByStatus(result.getMediaDecisions(), PublishabilityStatus.DEGRADED);
        long blockedCount = countByStatus(result.getMediaDecisions(), PublishabilityStatus.BLOCKED);
        long unknownCount = countByStatus(result.getMediaDecisions(), PublishabilityStatus.UNKNOWN);

        log.info("推文可发布性评估完成: tweetId={}, tweetStatus={}, 媒体状态=（PUBLISHABLE={}, DEGRADED={}, BLOCKED={}, UNKNOWN={}）, 耗时={}ms",
                tweetId, result.getTweetStatus(), publishableCount, degradedCount, blockedCount, unknownCount, elapsedMs);
    }

    /** AC9: 单媒体降级/阻止日志含 tweetId + mediaId + status + 截断 reason. */
    private void logMediaDecisionIfNeeded(String tweetId, MediaPublishabilityDecision decision) {
        if (decision == null) {
            return;
        }
        if (decision.getStatus() == PublishabilityStatus.DEGRADED || decision.getStatus() == PublishabilityStatus.BLOCKED) {
            log.warn("单媒体可发布性降级: tweetId={}, mediaId={}, status={}, reason={}",
                    tweetId, decision.getMediaId(), decision.getStatus(),
                    sanitizeAndTruncateReason(decision.getReason()));
        }
    }

    /** 统计指定状态的媒体数量. */
    private long countByStatus(List<MediaPublishabilityDecision> decisions, PublishabilityStatus status) {
        if (decisions == null) {
            return 0;
        }
        return decisions.stream()
                .filter(Objects::nonNull)
                .filter(d -> status.equals(d.getStatus()))
                .count();
    }

    /** 安全提取文件名（N4：日志只输出 filename，不输出完整路径）. */
    private static String safeFileName(String path) {
        if (path == null || path.isBlank()) {
            return "<blank>";
        }
        try {
            java.nio.file.Path fileName = java.nio.file.Path.of(path).getFileName();
            return fileName == null ? "<unknown>" : fileName.toString();
        } catch (Exception e) {
            return "<invalid-path>";
        }
    }

    /** 脱敏并截断 reason 到指定长度（N4 + R3-1，防止 URL/过长字符串进入日志/序列化）. */
    private String sanitizeAndTruncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return TextTruncateUtil.truncateForLog(URL_PATTERN.matcher(reason).replaceAll("<url>"), LOG_MSG_MAX_LENGTH);
    }
}
