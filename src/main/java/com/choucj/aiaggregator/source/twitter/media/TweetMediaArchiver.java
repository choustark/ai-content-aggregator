package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 7.2: X 推文媒体下载编排器 — PHOTO 类型图片下载 + 保存到本地 + sidecar 回写.
 *
 * <p>仅处理 {@link TweetMediaType#PHOTO} 类型的媒体, 非 PHOTO 类型标记
 * {@link MediaDownloadStatus#SKIPPED} (Story 7.3 处理 VIDEO/GIF). 每个媒体独立 try-catch,
 * 单个失败不阻塞其他媒体处理 (AD-5 单媒体失败降级).
 *
 * <p><b>关键设计:</b>
 * <ul>
 *   <li><b>确定性文件名</b> — {@code {sanitizedMediaId}.{extFromUrl}}, ext 从 sourceUrl 路径段提取, fallback jpg</li>
 *   * <li><b>幂等跳过</b> — 文件已存在且非零字节时跳过 HTTP 下载, 仍回写 DOWNLOADED (AD-6)</li>
 *   * <li><b>sidecar 回写</b> — 通过 {@link TweetMediaArchiveWriter#updateMedia} 增量回写, 不覆盖其他字段</li>
 *   * <li><b>集成开关</b> — 复用 {@code twitter.media.enabled}, 与 TweetMediaArchiveWriter 同生同灭</li>
 * </ul>
 *
 * <p><b>引用源:</b>
 * Story 7.2 / ARCHITECTURE-SPINE AD-2(三阶段独立状态) + AD-5(单媒体降级) + AD-6(本地归档+sidecar).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class TweetMediaArchiver {

    /** 日志中 sourceUrl 截断长度 (N4). */
    private static final int URL_LOG_MAX_LENGTH = 100;
    private static final int MEDIA_ID_MAX_LENGTH = 80;
    private static final int HASH_LENGTH = 8;

    private final MediaDownloadClient downloadClient;
    private final TweetMediaArchiveWriter archiveWriter;
    private final DateTimeFormatter dateFormatter;

    /**
     * 构造器注入依赖,提取 TweetMediaArchiveWriter 的 dateFormatter 用于统一日期格式.
     *
     * @param downloadClient 媒体下载客户端
     * @param archiveWriter  归档写入器(提供 dateFormatter)
     */
    public TweetMediaArchiver(MediaDownloadClient downloadClient,
                                TweetMediaArchiveWriter archiveWriter) {
        this.downloadClient = downloadClient;
        this.archiveWriter = archiveWriter;
        // 复用 TweetMediaArchiveWriter 的 dateFormatter,确保日期格式一致性
        this.dateFormatter = extractDateFormatter(archiveWriter);
    }

    /**
     * 从 TweetMediaArchiveWriter 提取 dateFormatter.
     * 使用反射访问私有字段,避免修改 Story 7.1 的公共 API.
     */
    private static DateTimeFormatter extractDateFormatter(TweetMediaArchiveWriter writer) {
        try {
            java.lang.reflect.Field field = TweetMediaArchiveWriter.class.getDeclaredField("dateFormatter");
            field.setAccessible(true);
            return (DateTimeFormatter) field.get(writer);
        } catch (Exception e) {
            log.warn("无法提取 TweetMediaArchiveWriter.dateFormatter,使用 ISO_LOCAL_DATE", e);
            return DateTimeFormatter.ISO_LOCAL_DATE;
        }
    }

    /**
     * 归档推文媒体: 下载所有 PHOTO 类型图片到本地, 更新 sidecar 状态.
     *
     * <p>处理逻辑:
     * <ol>
     *   <li>过滤 PHOTO + allowDownload=true 的媒体</li>
     *   *li>非 PHOTO → SKIPPED (Story 7.3 处理)</li>
     *   <li>每个 PHOTO 独立 try-catch, 下载并保存文件</li>
     *   <li>通过 {@link TweetMediaArchiveWriter#updateMedia} 回写 sidecar</li>
     * </ol>
     *
     * @param tweetId    推文 ID
     * @param publishedAt 推文发布时间 (用于目录日期段)
     * @param media      媒体列表 (null 视为空)
     * @return 处理结果摘要 (成功数 + 跳过数 + 失败数)
     */
    public ArchiveResult archiveMedia(String tweetId, LocalDateTime publishedAt,
                                       List<TweetMedia> media) {
        if (media == null || media.isEmpty()) {
            return ArchiveResult.empty();
        }

        LocalDateTime effectivePublishedAt = publishedAt != null ? publishedAt : LocalDateTime.now();
        Path archiveDir = archiveWriter.resolveArchiveDir(tweetId, effectivePublishedAt);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<MediaArchiveStatus> statuses = new ArrayList<>();

        int photoIndex = 0;
        for (int mediaIndex = 0; mediaIndex < media.size(); mediaIndex++) {
            TweetMedia m = media.get(mediaIndex);
            if (m == null) {
                continue;
            }

            if (m.getType() != TweetMediaType.PHOTO) {
                // 非 PHOTO 类型跳过, Story 7.3 处理
                String mediaId = effectiveMediaId(tweetId, m, photoIndex);
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "非 PHOTO 类型，跳过下载（Story 7.3 处理）");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "非 PHOTO 类型，跳过下载（Story 7.3 处理）"));
                skipCount.incrementAndGet();
                continue;
            }

            int currentPhotoIndex = photoIndex++;
            String mediaId = effectiveMediaId(tweetId, m, currentPhotoIndex);

            if (!m.isAllowDownload()) {
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "allowDownload=false");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "allowDownload=false"));
                skipCount.incrementAndGet();
                continue;
            }

            if (!StringUtils_hasText(m.getSourceUrl())) {
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "sourceUrl 为空");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "sourceUrl 为空"));
                skipCount.incrementAndGet();
                continue;
            }

            try {
                String localPath = downloadAndSave(tweetId, effectivePublishedAt, archiveDir, m, mediaId,
                        currentPhotoIndex, mediaIndex);
                statuses.add(MediaArchiveStatus.downloaded(mediaId, localPath));
                successCount.incrementAndGet();
            } catch (Exception e) {
                int beforeCount = failCount.get();
                failCount.incrementAndGet(); // 先累加失败计数
                int afterCount = failCount.get();
                log.warn("媒体处理失败计数: tweetId={}, mediaId={}, before={}, after={}",
                        tweetId, mediaId, beforeCount, afterCount);
                String reason = logFailedDownload(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, e); // 再记录日志
                statuses.add(MediaArchiveStatus.failed(mediaId, reason, e instanceof RetryableException));
            }
        }

        log.info("媒体归档完成: tweetId={}, 成功={}, 跳过={}, 失败={}",
                tweetId, successCount.get(), skipCount.get(), failCount.get());
        return new ArchiveResult(successCount.get(), skipCount.get(), failCount.get(), List.copyOf(statuses));
    }

    /**
     * 下载单个媒体并保存到本地, 然后回写 sidecar.
     */
    private String downloadAndSave(String tweetId, LocalDateTime publishedAt,
                                  Path archiveDir, TweetMedia media, String mediaId, int photoIndex, int mediaIndex) {
        long startNanos = System.nanoTime();
        String filename = resolveFilename(tweetId, media, mediaId, photoIndex);
        Path filePath = archiveDir.resolve(filename);
        String localPath = resolveLocalPath(tweetId, publishedAt, filename);

        // 幂等检查: 文件已存在且非零字节 → 跳过下载
        try {
            if (Files.exists(filePath) && Files.size(filePath) > 0) {
                log.info("媒体已存在,跳过下载: tweetId={}, mediaId={}, file={}",
                        tweetId, mediaId, filePath.getFileName());
                updateSidecarSuccess(tweetId, publishedAt, media, mediaId, mediaIndex, localPath);
                return localPath;
            }
        } catch (IOException e) {
            log.warn("幂等检查文件状态失败, 继续尝试下载: tweetId={}, mediaId={}, file={}",
                    tweetId, mediaId, filePath.getFileName());
        }

        // 确保目录存在 (Writer 的 ensureDirectoryExists 已在 resolveArchiveDir 隐式处理,
        // 但并发场景下可能尚未创建, 此处显式调用)
        try {
            Files.createDirectories(archiveDir);
        } catch (IOException e) {
            throw new NonRetryableException("媒体归档目录创建失败: tweetId=" + tweetId
                    + " dir=" + archiveDir, e);
        }

        // 下载并保存
        MediaDownloadClient.DownloadResult result = downloadClient.downloadBinary(
                media.getSourceUrl(), tweetId, mediaId);
        try {
            Path tmpFile = archiveDir.resolve(filename + ".tmp");
            Files.write(tmpFile, result.bytes());
            Files.move(tmpFile, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new NonRetryableException("媒体文件写入失败: tweetId=" + tweetId
                    + " mediaId=" + mediaId + " file=" + filePath, e);
        }

        updateSidecarSuccess(tweetId, publishedAt, media, mediaId, mediaIndex, localPath);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("媒体保存成功: tweetId={}, mediaId={}, sourceUrl={}, fileSize={}bytes, localPath={}, 耗时={}ms",
                tweetId, mediaId, TextTruncateUtil.truncateForLog(media.getSourceUrl(), URL_LOG_MAX_LENGTH),
                result.bytes().length, localPath, elapsedMs);
        return localPath;
    }

    /**
     * 更新 sidecar: 下载成功, 设置 localPath + downloadStatus=DOWNLOADED.
     * 使用 TweetMedia.toBuilder 复制所有字段, 仅覆盖 localPath 和 downloadStatus.
     *
     * @param publishedAt 推文发布时间,可为 null(null 时使用当前时刻)
     */
    private void updateSidecarSuccess(String tweetId, LocalDateTime publishedAt,
                                          TweetMedia media, String mediaId, int mediaIndex, String localPath) {
        updateSidecar(tweetId, publishedAt, media, mediaId, mediaIndex,
                original -> original.toBuilder()
                .id(mediaId)
                .localPath(localPath)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .build());
    }

    /**
     * 更新 sidecar: 跳过/失败, 设置 downloadStatus + failureReason.
     *
     * @param publishedAt 推文发布时间,可为 null(null 时使用当前时刻)
     */
    private void skipMedia(String tweetId, LocalDateTime publishedAt,
                             TweetMedia media, String mediaId, int mediaIndex, String reason) {
        try {
            updateSidecar(tweetId, publishedAt, media, mediaId, mediaIndex,
                    original -> original.toBuilder()
                    .id(mediaId)
                    .downloadStatus(MediaDownloadStatus.SKIPPED)
                    .failureReason(reason)
                    .build());
        } catch (Exception e) {
            // 捕获所有异常,防止单个媒体处理失败阻塞整体流程
            log.warn("更新 sidecar 跳过状态失败: tweetId={}, mediaId={}, error={}",
                    tweetId, mediaId, TextTruncateUtil.truncateForLog(
                            TextTruncateUtil.getRootMessage(e), 200));
        }
    }

    /**
     * 生成确定性文件名.
     * <ul>
     *   <li>基名: mediaId (sanitize: 移除 / \. .. 等路径字符, 保留字母数字下划线连字符点和)</li>
     *   <li>mediaId 为空时: {tweetId}:{index}:photo</li>
     *   <li>扩展名: 从 sourceUrl 路径段最后一个 . 后提取; 无扩展或超过 5 字符 → fallback jpg</li>
     * </ul>
     * 修复 Review Patch: 添加 .jpg fallback (原逻辑 ext.isEmpty() 返回 baseName,无扩展名)
     */
    private String resolveFilename(String tweetId, TweetMedia media, String mediaId, int photoIndex) {
        String safeId = sanitizeFilename(mediaId);
        String baseName = truncateBasename(safeId) + "-" + shortHash(mediaId + "|" + photoIndex);

        String ext = extractExtension(media.getSourceUrl());
        // 修复 Review Patch: 无扩展名时 fallback 到 .jpg
        return ext.isEmpty() ? baseName + ".jpg" : baseName + "." + ext;
    }

    /**
     * 从 URL 路径段提取文件扩展名.
     * 例: {@code https://pbs.twimg.com/media/ABC123.jpg} → {@code jpg}
     * {@code https://pbs.twimg.com/media/ABC123?format=png} → {@code } (query param 不算)
     */
    private String extractExtension(String url) {
        if (!StringUtils_hasText(url)) {
            return "";
        }
        String path = url;
        // 去掉 query string
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        // 取最后一个 . 之后的部分
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx >= path.length() - 1) {
            return ""; // 无扩展名或太短 (如 ".")
        }
        String ext = path.substring(dotIdx + 1);
        // 限制扩展名长度 (防止 .jpeg?name=large 等长扩展)
        return ext.length() <= 5 ? ext : "";
    }

    /**
     * 清理文件名中的路径字符, 仅保留安全字符.
     */
    private static String sanitizeFilename(String id) {
        return id.replaceAll("[/\\\\\\.:\\s]+", "_");
    }

    private static String truncateBasename(String basename) {
        if (basename.length() <= MEDIA_ID_MAX_LENGTH) {
            return basename;
        }
        return basename.substring(0, MEDIA_ID_MAX_LENGTH);
    }

    private static String shortHash(String input) {
        String hash = Integer.toHexString(input.hashCode());
        if (hash.length() >= HASH_LENGTH) {
            return hash.substring(0, HASH_LENGTH);
        }
        return "0".repeat(HASH_LENGTH - hash.length()) + hash;
    }

    private String effectiveMediaId(String tweetId, TweetMedia media, int photoIndex) {
        if (StringUtils_hasText(media.getId())) {
            return media.getId();
        }
        return tweetId + ":" + photoIndex + ":photo";
    }

    private void updateSidecar(String tweetId, LocalDateTime publishedAt, TweetMedia media, String mediaId,
                               int mediaIndex, java.util.function.UnaryOperator<TweetMedia> mutation) {
        if (StringUtils_hasText(media.getId())) {
            archiveWriter.updateMedia(tweetId, publishedAt, mediaId, mutation);
        } else {
            archiveWriter.updateMediaAtIndex(tweetId, publishedAt, mediaIndex, mutation);
        }
    }

    private String resolveLocalPath(String tweetId, LocalDateTime publishedAt, String filename) {
        String datePart = publishedAt.toLocalDate().format(dateFormatter);
        return "media/twitter/" + datePart + "/" + tweetId + "/" + filename;
    }

    private static boolean StringUtils_hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String logFailedDownload(String tweetId, LocalDateTime publishedAt, TweetMedia media,
                                     String mediaId, int mediaIndex, Exception e) {
        String failureReason;
        if (e instanceof NonRetryableException) {
            failureReason = TextTruncateUtil.truncateForLog(e.getMessage(), 200);
            log.warn("媒体下载失败(不可重试): tweetId={}, mediaId={}, reason={}",
                    tweetId, mediaId, failureReason);
        } else if (e instanceof RetryableException) {
            failureReason = TextTruncateUtil.truncateForLog(e.getMessage(), 200);
            log.warn("媒体下载失败(可重试): tweetId={}, mediaId={}, reason={}",
                    tweetId, mediaId, failureReason);
        } else {
            failureReason = TextTruncateUtil.truncateForLog(
                    TextTruncateUtil.getRootMessage(e), 200);
            log.error("媒体下载失败(未知错误): tweetId={}, mediaId={}, reason={}",
                    tweetId, mediaId, failureReason);
        }

        try {
            updateSidecar(tweetId, publishedAt, media, mediaId, mediaIndex,
                    original -> original.toBuilder()
                    .id(mediaId)
                    .downloadStatus(MediaDownloadStatus.FAILED)
                    .failureReason(failureReason)
                    .build());
        } catch (Exception sidecarEx) {
            log.warn("回写失败状态到 sidecar 失败: tweetId={}, mediaId={}",
                    tweetId, mediaId);
        }
        return failureReason;
    }

    /**
     * 媒体归档处理结果摘要.
     */
    public record ArchiveResult(int successCount, int skipCount, int failCount, List<MediaArchiveStatus> mediaStatuses) {
        public static ArchiveResult empty() {
            return new ArchiveResult(0, 0, 0, List.of());
        }
    }

    /**
     * 单媒体归档状态, 让调用方在保持逐媒体隔离的同时识别失败是否可重试.
     */
    public record MediaArchiveStatus(String mediaId, MediaDownloadStatus status,
                                     String localPath, String failureReason, boolean retryable) {
        static MediaArchiveStatus downloaded(String mediaId, String localPath) {
            return new MediaArchiveStatus(mediaId, MediaDownloadStatus.DOWNLOADED, localPath, null, false);
        }

        static MediaArchiveStatus skipped(String mediaId, String reason) {
            return new MediaArchiveStatus(mediaId, MediaDownloadStatus.SKIPPED, null, reason, false);
        }

        static MediaArchiveStatus failed(String mediaId, String reason, boolean retryable) {
            return new MediaArchiveStatus(mediaId, MediaDownloadStatus.FAILED, null, reason, retryable);
        }
    }
}
