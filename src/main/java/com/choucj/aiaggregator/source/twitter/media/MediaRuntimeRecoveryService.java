package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeState;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Story 7.4: 媒体断点恢复查询服务 — 重启后识别媒体处理状态 + 幂等下载判断.
 *
 * <p><b>双源策略 (AC3, AD-6):</b> Redis 快照 ({@code tweet:{id}:media}) 优先 (快, 重启后秒级判断);
 * Redis 异常/miss 时 fallback 读 media.json sidecar 重建 (权威源). 双源都 miss → 空列表
 * (调用方按全部待处理).
 *
 * <p><b>只读查询 (AC9, AD-10):</b> 本服务不写任何状态、不调用 LLM、不绕过任务队列/异常体系/
 * 成本 gate — 为未来受控 Agent 决策预留只读边界.
 *
 * <p><b>容错 (AC5):</b> getMediaStates 内部捕获 Redis 异常并 fallback sidecar, <b>不抛</b>
 * (恢复查询必须容错); sidecar 读取失败也 fail-open 返回空列表 (与
 * {@link TweetMediaArchiveWriter#readSidecar} 的 fail-open 哲学一致).
 *
 * <p><b>引用源:</b> Story 7.4 (创建) / ARCHITECTURE-SPINE AD-6 + AD-10 / Story 7.2
 * effectivePublishedAt 防跨日模式复用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class MediaRuntimeRecoveryService {

    /** 日志中根因/localPath 截断长度 (N4 + R3-1). */
    private static final int LOG_MSG_MAX_LENGTH = 200;

    private final MediaRuntimeStateRepository stateRepository;
    private final TweetMediaArchiveWriter archiveWriter;

    /**
     * 查询推文的媒体运行时状态 (AC3, 双源容错).
     *
     * <p>Redis 优先; Redis 异常或 miss 时 fallback 读 sidecar 重建; 双源都 miss 返回空列表.
     * 本方法不抛异常 (恢复查询必须容错).
     *
     * @param tweetId    推文 ID
     * @param publishedAt 推文发布时间 (用于 sidecar 目录解析; null 时 fallback 当前时刻,
     *                   与 archiveMedia effectivePublishedAt 一致防跨日)
     * @return 每媒体状态摘要列表 (可能为空, 不为 null)
     */
    public List<MediaRuntimeItem> getMediaStates(String tweetId, LocalDateTime publishedAt) {
        if (tweetId == null || tweetId.isBlank()) {
            return List.of();
        }
        long start = System.nanoTime();

        // 源 1: Redis 快照 (优先, 快)
        try {
            Optional<MediaRuntimeState> snapshot = stateRepository.getSnapshot(tweetId);
            if (snapshot.isPresent() && snapshot.get().getMediaStates() != null) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                List<MediaRuntimeItem> states = snapshot.get().getMediaStates();
                log.info("媒体状态恢复完成: tweetId={}, source=redis, mediaCount={}, downloaded={}, skipped={}, failed={}, pending={}, 耗时={}ms",
                        tweetId, states.size(),
                        countByStatus(states, MediaDownloadStatus.DOWNLOADED),
                        countByStatus(states, MediaDownloadStatus.SKIPPED),
                        countByStatus(states, MediaDownloadStatus.FAILED),
                        countByStatus(states, MediaDownloadStatus.PENDING),
                        elapsedMs);
                return states;
            }
        } catch (RetryableException | NonRetryableException e) {
            // AC5: Redis 异常 → fallback sidecar, 不抛 (恢复查询必须容错)
            log.warn("Redis 快照读取失败, fallback sidecar 重建: tweetId={}, cause={}",
                    tweetId, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
        } catch (RuntimeException e) {
            // W1+W2: Redis/proxy/Jackson unchecked 异常同样 fail-open fallback sidecar.
            log.warn("Redis 快照读取发生未预期异常, fallback sidecar 重建: tweetId={}, cause={}",
                    tweetId, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
        }

        // 源 2: sidecar 重建 (权威源, fail-open)
        List<MediaRuntimeItem> rebuilt = rebuildFromSidecar(tweetId, publishedAt);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (rebuilt.isEmpty()) {
            log.info("媒体状态恢复完成: tweetId={}, source=empty, mediaCount=0, 耗时={}ms", tweetId, elapsedMs);
        } else {
            log.info("媒体状态恢复完成: tweetId={}, source=sidecar, mediaCount={}, downloaded={}, skipped={}, failed={}, pending={}, 耗时={}ms",
                    tweetId, rebuilt.size(),
                    countByStatus(rebuilt, MediaDownloadStatus.DOWNLOADED),
                    countByStatus(rebuilt, MediaDownloadStatus.SKIPPED),
                    countByStatus(rebuilt, MediaDownloadStatus.FAILED),
                    countByStatus(rebuilt, MediaDownloadStatus.PENDING),
                    elapsedMs);
        }
        return rebuilt;
    }

    /**
     * 幂等下载判断 (AC4, 状态 + 文件双校验).
     *
     * <p>返回 true (跳过 HTTP 下载) 当且仅当: 该媒体在快照/sidecar 中 {@code downloadStatus==DOWNLOADED}
     * <b>且</b> localPath 非空 <b>且</b> 本地文件存在且非零字节. 任一不满足 → false (需重新下载).
     * 媒体不在快照中 → false (待处理).
     *
     * <p>本方法是查询 API, 供未来调用方 (Epic 8 Pipeline) 在调 archiveMedia 前判断;
     * archiveMedia 自身的幂等性由 Story 7.2 文件存在检查兜底, 二者不重复实现.
     *
     * @param tweetId    推文 ID
     * @param publishedAt 推文发布时间 (localPath 相对路径基准; null 时 fallback 当前时刻)
     * @param mediaId     媒体 ID (与快照/sidecar 中的 mediaId 对齐)
     * @return true 表示该媒体已成功归档且文件校验存在, 可跳过下载
     */
    public boolean shouldSkipDownload(String tweetId, LocalDateTime publishedAt, String mediaId) {
        if (tweetId == null || tweetId.isBlank() || mediaId == null || mediaId.isBlank()) {
            return false;
        }
        LocalDateTime effectivePublishedAt = publishedAt != null ? publishedAt : LocalDateTime.now();
        List<MediaRuntimeItem> states = getMediaStates(tweetId, effectivePublishedAt);
        Optional<MediaRuntimeItem> matched = states.stream()
                .filter(java.util.Objects::nonNull)
                .filter(item -> mediaId.equals(item.getMediaId()))
                .findFirst();
        if (matched.isEmpty()) {
            log.debug("媒体幂等判断: tweetId={}, mediaId={}, skip=false, reason=not-in-snapshot", tweetId, mediaId);
            return false;
        }
        MediaRuntimeItem item = matched.get();
        if (item.getDownloadStatus() != MediaDownloadStatus.DOWNLOADED) {
            log.debug("媒体幂等判断: tweetId={}, mediaId={}, skip=false, reason=status={}",
                    tweetId, mediaId, item.getDownloadStatus());
            return false;
        }
        if (item.getLocalPath() == null || item.getLocalPath().isBlank()) {
            log.debug("媒体幂等判断: tweetId={}, mediaId={}, skip=false, reason=localPath-null", tweetId, mediaId);
            return false;
        }
        boolean fileExists = fileExistsAndNonEmpty(tweetId, effectivePublishedAt, item.getLocalPath());
        // N4: 日志只输出 filename, 不输出完整路径
        log.debug("媒体幂等判断: tweetId={}, mediaId={}, skip={}, reason=fileExists={}, file={}",
                tweetId, mediaId, fileExists, safeFileName(item.getLocalPath()));
        return fileExists;
    }

    /** 从 sidecar 重建媒体状态摘要 (fail-open: sidecar miss/损坏返回空列表). */
    private List<MediaRuntimeItem> rebuildFromSidecar(String tweetId, LocalDateTime publishedAt) {
        try {
            Optional<MediaArchiveRecord> record = archiveWriter.readSidecar(tweetId, publishedAt);
            if (record.isEmpty() || record.get().getMedia() == null) {
                return List.of();
            }
            return record.get().getMedia().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(m -> MediaRuntimeItem.builder()
                            .mediaId(m.getId())
                            .type(m.getType())
                            .downloadStatus(m.getDownloadStatus())
                            .localPath(m.getLocalPath())
                            .failureReason(m.getFailureReason())
                            // sidecar 重建时无可重试信号, 默认 false; Story 7.5 gate 再精判
                            .retryable(false)
                            .build())
                    .toList();
        } catch (RetryableException | NonRetryableException e) {
            // AC5 容错: sidecar 读取失败 (如 tweetId 非法) fail-open 返回空列表
            log.warn("sidecar 重建媒体状态失败, 按空处理: tweetId={}, cause={}",
                    tweetId, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return List.of();
        }
    }

    /** 校验 localPath 文件存在且非零字节 (Story 7.2 幂等检查同款, AC4 双校验). */
    private boolean fileExistsAndNonEmpty(String tweetId, LocalDateTime publishedAt, String localPath) {
        try {
            // localPath 形如 media/twitter/{date}/{tweetId}/{filename}, 相对归档根目录 {base};
            // resolveArchiveDir 返回 {base}/media/twitter/{date}/{tweetId} (4 段), 上溯 4 级回到 {base}.
            // 用 nameCount 而非硬编码 getParent 链: 相对 baseDirectory 时段数可变, 统计 media/twitter
            // 之后的段数 (datePart + tweetId + [子目录...]) 逐级上溯更稳健.
            Path archiveDir = archiveWriter.resolveArchiveDir(tweetId, publishedAt);
            // archiveDir 相对路径时以 media/twitter 为锚; 绝对路径时 base = archiveDir 向上
            // (nameCount - 相对于 base 的层数). 直接定位: baseDir = archiveDir 上溯到「media」的父目录.
            Path baseDir = findBaseDir(archiveDir);
            if (baseDir == null) {
                return false;
            }
            Path file = baseDir.resolve(localPath).normalize();
            // 防御: 解析后路径不得逃逸 baseDir (路径穿越双重防御, 复用 Writer 模式)
            if (!file.startsWith(baseDir.normalize())) {
                return false;
            }
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException | RuntimeException e) {
            log.warn("媒体文件校验失败, 按需重新下载处理: tweetId={}, file={}, cause={}",
                    tweetId, safeFileName(localPath),
                    TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return false;
        }
    }

    private static String safeFileName(String path) {
        if (path == null || path.isBlank()) {
            return "<blank>";
        }
        try {
            Path fileName = Path.of(path).getFileName();
            return fileName == null ? "<unknown>" : fileName.toString();
        } catch (RuntimeException e) {
            return "<invalid-path>";
        }
    }

    /**
     * 从 archiveDir ({base}/media/twitter/{date}/{tweetId}) 定位归档根目录 {base}:
     * 从末尾向上找到名为 "twitter" 且其父为 "media" 的段, 返回 media 的父目录.
     * 找不到 (畸形路径) 返回 null.
     */
    private static Path findBaseDir(Path archiveDir) {
        Path cursor = archiveDir;
        while (cursor != null && cursor.getParent() != null) {
            if ("twitter".equals(cursor.getFileName() == null ? "" : cursor.getFileName().toString())
                    && "media".equals(cursor.getParent().getFileName() == null
                        ? "" : cursor.getParent().getFileName().toString())) {
                return cursor.getParent().getParent();
            }
            cursor = cursor.getParent();
        }
        return null;
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
