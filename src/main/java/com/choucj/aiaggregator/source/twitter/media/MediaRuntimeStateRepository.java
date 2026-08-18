package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeState;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Story 7.4: 媒体运行时状态 Redis 快照仓库 — {@code tweet:{id}:media} 读写.
 *
 * <p>写入运行时状态快照供断点恢复: 重启后 {@link MediaRuntimeRecoveryService} 读取快照识别
 * 已下载/失败/跳过/待重试媒体项. Redis 快照是<b>运行时快速恢复源</b>; media.json sidecar 是
 * <b>长期审计权威源</b> (AD-6) — 写入顺序「先 sidecar 后 Redis」, Redis 写失败不丢本地归档.
 *
 * <p><b>软失败/透传策略 (AC5, 复用 ArticleStatusService 模式, lessons-learned §1.6):</b>
 * <ul>
 *   <li>{@link #saveSnapshot} <b>软失败</b> — try-catch Retryable/NonRetryable, log.warn 吞掉,
 *       不阻塞 archiveMedia 主流程 (sidecar 已先写, 本地归档信息不丢)</li>
 *   <li>{@link #getSnapshot} <b>透传异常</b> — 不软失败, 调用方 (RecoveryService) 需区分
 *       「查询失败」和「未找到」以决定 fallback sidecar</li>
 * </ul>
 *
 * <p><b>TTL 决策:</b> 每次 saveSnapshot 重设 30 天 (与 ArticleStatusService.PENDING_TTL 一致,
 * 覆盖出差 + 周末审核恢复窗口). 不用 KEEPTTL — 媒体归档重处理即刷新恢复窗口是合理语义,
 * 无文章状态「首次 PENDING 锚定 TTL」的约束.
 *
 * <p><b>引用源:</b> Story 7.4 (创建) / ARCHITECTURE-SPINE AD-6 + 一致性约定表 (tweet:{id}:media)
 * / Story 3.5 ArticleStatusService 状态机模式复用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class MediaRuntimeStateRepository {

    /** 快照 TTL = 30 天, 与 ArticleStatusService.PENDING_TTL 一致 (恢复窗口). */
    private static final Duration SNAPSHOT_TTL = Duration.ofDays(30);

    /** 日志中根因截断长度 (N4 + R3-1). */
    private static final int LOG_MSG_MAX_LENGTH = 200;

    private final RedisRepository redisRepository;

    /**
     * 写入媒体运行时状态快照 (AC1, 软失败).
     *
     * <p>值只含状态摘要 (mediaId/type/downloadStatus/localPath/failureReason/retryable),
     * 不含 variant URL (N4, MediaRuntimeItem 编译期保证). Redis 写失败 log.warn 吞掉,
     * 不影响调用方 (sidecar 已先写).
     *
     * @param tweetId 推文 ID (非 blank)
     * @param mediaStates 每媒体状态摘要列表
     */
    public void saveSnapshot(String tweetId, List<MediaRuntimeItem> mediaStates) {
        if (tweetId == null || tweetId.isBlank()) {
            log.warn("媒体运行时快照写入跳过: tweetId 为空");
            return;
        }
        List<MediaRuntimeItem> safeStates = mediaStates == null ? List.of() : List.copyOf(mediaStates);
        MediaRuntimeState state = MediaRuntimeState.builder()
                .tweetId(tweetId)
                .generatedAt(LocalDateTime.now())
                .mediaStates(safeStates)
                .build();
        String key = RedisKeys.tweetMedia(tweetId);
        long start = System.nanoTime();
        try {
            redisRepository.setObject(key, state, SNAPSHOT_TTL);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // W11: 仅输出标识符 + 状态计数, 不输出 Redis JSON value 全文 (N4)
            log.info("媒体运行时快照写入成功: tweetId={}, mediaCount={}, downloaded={}, skipped={}, failed={}, pending={}, 耗时={}ms",
                    tweetId, safeStates.size(),
                    countByStatus(safeStates, MediaDownloadStatus.DOWNLOADED),
                    countByStatus(safeStates, MediaDownloadStatus.SKIPPED),
                    countByStatus(safeStates, MediaDownloadStatus.FAILED),
                    countByStatus(safeStates, MediaDownloadStatus.PENDING),
                    elapsedMs);
        } catch (RetryableException | NonRetryableException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // AC5 软失败: sidecar 已先写, Redis 快照丢失只降低恢复速度, 不丢归档信息
            log.warn("媒体运行时快照写入失败, 跳过 (不阻塞归档): tweetId={}, mediaCount={}, 耗时={}ms, cause={}",
                    tweetId, safeStates.size(), elapsedMs,
                    TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
        }
    }

    /**
     * 读取媒体运行时状态快照 (AC3, 异常透传).
     *
     * <p>不软失败 — Redis 异常透传给调用方, 由 {@link MediaRuntimeRecoveryService} 决定
     * fallback sidecar (查询失败 ≠ 未找到, 两者语义不同).
     *
     * @param tweetId 推文 ID
     * @return 快照; 键不存在/过期返回 empty
     * @throws RetryableException Redis 连接失败
     * @throws NonRetryableException Redis 数据/反序列化错误
     */
    public Optional<MediaRuntimeState> getSnapshot(String tweetId) {
        if (tweetId == null || tweetId.isBlank()) {
            return Optional.empty();
        }
        String key = RedisKeys.tweetMedia(tweetId);
        long start = System.nanoTime();
        MediaRuntimeState state = redisRepository.getObject(key, MediaRuntimeState.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        List<MediaRuntimeItem> states = state == null || state.getMediaStates() == null
                ? List.of() : state.getMediaStates();
        log.info("媒体运行时快照读取完成: tweetId={}, hit={}, mediaCount={}, downloaded={}, skipped={}, failed={}, pending={}, 耗时={}ms",
                tweetId, state != null, states.size(),
                countByStatus(states, MediaDownloadStatus.DOWNLOADED),
                countByStatus(states, MediaDownloadStatus.SKIPPED),
                countByStatus(states, MediaDownloadStatus.FAILED),
                countByStatus(states, MediaDownloadStatus.PENDING),
                elapsedMs);
        return Optional.ofNullable(state);
    }

    private static long countByStatus(List<MediaRuntimeItem> items, MediaDownloadStatus status) {
        if (items == null) {
            return 0;
        }
        return items.stream()
                .filter(java.util.Objects::nonNull)
                .filter(item -> status.equals(item.getDownloadStatus()))
                .count();
    }
}
