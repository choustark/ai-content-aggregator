package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Story 7.2/7.3: X 推文媒体下载与归档编排器.
 *
 * <p>PHOTO 类型下载图片到本地 + sidecar 回写 (Story 7.2); VIDEO/GIF 类型只归档元数据到 sidecar
 * (previewImageUrl + variants + width/height + 码率摘要), 标记 {@link MediaDownloadStatus#SKIPPED},
 * <b>不下载二进制</b> (复现路径待 Story 8.2 spike 决定); UNKNOWN 类型直接 SKIPPED (Story 7.3).
 * 每个媒体独立 try-catch, 单个失败不阻塞其他媒体处理 (AD-5 单媒体失败降级).
 *
 * <p><b>关键设计:</b>
 * <ul>
 *   <li><b>确定性文件名</b> — {@code {sanitizedMediaId}.{extFromUrl}}, ext 从 sourceUrl 路径段提取, fallback jpg</li>
 *   <li><b>幂等跳过</b> — 文件已存在且非零字节时跳过 HTTP 下载, 仍回写 DOWNLOADED (AD-6)</li>
 *   <li><b>sidecar 回写</b> — 通过 {@link TweetMediaArchiveWriter#updateMedia} 增量回写, 不覆盖其他字段</li>
 *   <li><b>VIDEO/GIF 元数据归档</b> — 写 previewImageUrl/variants/width/height/order/码率摘要,
 *       downloadStatus=SKIPPED, 不调用 MediaDownloadClient (Story 7.3, AD-6)</li>
 *   <li><b>集成开关</b> — 复用 {@code twitter.media.enabled}, 与 TweetMediaArchiveWriter 同生同灭</li>
 *   <li><b>Redis 运行时快照</b> — 归档完成后聚合状态摘要写入 {@code tweet:{id}:media} (Story 7.4,
 *       软失败不阻塞归档; sidecar 是权威源, Redis 是运行时恢复辅助源)</li>
 * </ul>
 *
 * <p><b>引用源:</b>
 * Story 7.2 / Story 7.3 / Story 7.4 / ARCHITECTURE-SPINE AD-2(三阶段独立状态) + AD-5(单媒体降级) + AD-6(本地归档+sidecar).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class TweetMediaArchiver {

    /** 日志中 sourceUrl 截断长度 (N4). */
    private static final int URL_LOG_MAX_LENGTH = 100;
    private static final int MEDIA_ID_MAX_LENGTH = 80;
    private static final int HASH_LENGTH = 8;

    /** Story 7.3: VIDEO/GIF 复现路径待 Spike 决定的固定跳过原因. */
    private static final String VIDEO_GIF_SKIP_REASON = "视频/GIF 复现路径待 Story 8.2 spike 决定，暂不下载";

    /** Story 7.3: variant 摘要 code point 上限 (N2 + R3-1). */
    private static final int VARIANT_SUMMARY_MAX_LENGTH = 200;

    private final MediaDownloadClient downloadClient;
    private final TweetMediaArchiveWriter archiveWriter;
    private final MediaRuntimeStateRepository stateRepository;
    private final DateTimeFormatter dateFormatter;

    /**
     * 构造器注入依赖,提取 TweetMediaArchiveWriter 的 dateFormatter 用于统一日期格式.
     *
     * @param downloadClient 媒体下载客户端
     * @param archiveWriter  归档写入器(提供 dateFormatter)
     * @param stateRepository 媒体运行时状态仓库 (Story 7.4, Redis 快照; 内部软失败, 不阻塞归档)
     */
    public TweetMediaArchiver(MediaDownloadClient downloadClient,
                                TweetMediaArchiveWriter archiveWriter,
                                MediaRuntimeStateRepository stateRepository) {
        this.downloadClient = downloadClient;
        this.archiveWriter = archiveWriter;
        this.stateRepository = stateRepository;
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
            saveRuntimeSnapshot(tweetId, List.of());
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

            if (m.getType() == TweetMediaType.VIDEO || m.getType() == TweetMediaType.GIF) {
                // Story 7.3: VIDEO/GIF 归档元数据到 sidecar, 不下载二进制
                String mediaId = effectiveMediaId(tweetId, m, mediaIndex);
                try {
                    archiveVideoOrGifMetadata(tweetId, effectivePublishedAt, m, mediaId, mediaIndex);
                    int variantCount = sanitizedVariants(m.getVariants()).size();
                    String reason = m.getType().name().toLowerCase()
                            + " 元数据已归档，variantCount=" + variantCount + "，下载待 Story 8.2 spike";
                    statuses.add(MediaArchiveStatus.skipped(mediaId, reason));
                    skipCount.incrementAndGet();
                    saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
                } catch (RuntimeException e) {
                    // AC5: 单个 VIDEO/GIF 元数据写入失败不阻塞同推文其他媒体 (AD-5)
                    log.warn("VIDEO/GIF 元数据归档失败, 继续处理后续媒体: tweetId={}, mediaId={}, reason={}",
                            tweetId, mediaId,
                            TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), 200));
                    statuses.add(MediaArchiveStatus.failed(mediaId,
                            TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), 200),
                            e instanceof RetryableException));
                    failCount.incrementAndGet();
                    saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
                }
                continue;
            }

            if (m.getType() != TweetMediaType.PHOTO) {
                // UNKNOWN 等类型: 直接跳过, 不归档元数据 (无 variants/preview 可写)
                String mediaId = effectiveMediaId(tweetId, m, mediaIndex);
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "未知媒体类型，跳过归档");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "未知媒体类型，跳过归档"));
                skipCount.incrementAndGet();
                saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
                continue;
            }

            int currentPhotoIndex = photoIndex++;
            String mediaId = effectiveMediaId(tweetId, m, currentPhotoIndex);

            if (!m.isAllowDownload()) {
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "allowDownload=false");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "allowDownload=false"));
                skipCount.incrementAndGet();
                saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
                continue;
            }

            if (!StringUtils_hasText(m.getSourceUrl())) {
                skipMedia(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, "sourceUrl 为空");
                statuses.add(MediaArchiveStatus.skipped(mediaId, "sourceUrl 为空"));
                skipCount.incrementAndGet();
                saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
                continue;
            }

            try {
                String localPath = downloadAndSave(tweetId, effectivePublishedAt, archiveDir, m, mediaId,
                        currentPhotoIndex, mediaIndex);
                statuses.add(MediaArchiveStatus.downloaded(mediaId, localPath));
                successCount.incrementAndGet();
                saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
            } catch (Exception e) {
                int beforeCount = failCount.get();
                failCount.incrementAndGet(); // 先累加失败计数
                int afterCount = failCount.get();
                log.warn("媒体处理失败计数: tweetId={}, mediaId={}, before={}, after={}",
                        tweetId, mediaId, beforeCount, afterCount);
                String reason = logFailedDownload(tweetId, effectivePublishedAt, m, mediaId, mediaIndex, e); // 再记录日志
                statuses.add(MediaArchiveStatus.failed(mediaId, reason, e instanceof RetryableException));
                saveRuntimeSnapshot(tweetId, buildRuntimeItems(tweetId, media, statuses));
            }
        }

        log.info("媒体归档完成: tweetId={}, 成功={}, 跳过={}, 失败={}",
                tweetId, successCount.get(), skipCount.get(), failCount.get());

        return new ArchiveResult(successCount.get(), skipCount.get(), failCount.get(), List.copyOf(statuses));
    }

    private void saveRuntimeSnapshot(String tweetId, List<MediaRuntimeItem> items) {
        // saveSnapshot 内部软失败; 此处再包一层 try-catch 双保险 (AC9: 绝不让 Redis 失败
        // 中断归档或回滚已写 sidecar), 防御 Repository 软失败契约被改坏的场景.
        try {
            stateRepository.saveSnapshot(tweetId, items);
        } catch (RuntimeException e) {
            log.warn("Redis 运行时快照回写异常 (双保险捕获), 归档结果不受影响: tweetId={}, cause={}",
                    tweetId, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), 200));
        }
    }

    /**
     * Story 7.4: 把归档结果投影为 Redis 快照状态摘要 (N4: 不含 variant URL/sourceUrl/previewImageUrl).
     *
     * <p>从 {@code mediaStatuses} 取 mediaId/downloadStatus/localPath/failureReason/retryable
     * (已含 effectiveMediaId 兜底 + 失败截断), 从原始 {@code media} 列表按 mediaId 对齐补 type;
     * 对齐失败的 item type 为 null (防御, 不抛).
     */
    private List<MediaRuntimeItem> buildRuntimeItems(String tweetId, List<TweetMedia> media,
                                                     List<MediaArchiveStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, TweetMediaType> typeById = new java.util.HashMap<>();
        if (media != null) {
            int photoIndex = 0;
            for (int mediaIndex = 0; mediaIndex < media.size(); mediaIndex++) {
                TweetMedia m = media.get(mediaIndex);
                if (m == null) {
                    continue;
                }
                int effectiveIndex = m.getType() == TweetMediaType.PHOTO ? photoIndex++ : mediaIndex;
                typeById.put(effectiveMediaId(tweetId, m, effectiveIndex), m.getType());
            }
        }
        List<MediaRuntimeItem> items = new ArrayList<>(statuses.size());
        for (MediaArchiveStatus status : statuses) {
            if (status == null) {
                continue;
            }
            items.add(MediaRuntimeItem.builder()
                    .mediaId(status.mediaId())
                    .type(typeById.get(status.mediaId()))
                    .downloadStatus(status.status())
                    .localPath(status.localPath())
                    .failureReason(status.failureReason())
                    .retryable(status.retryable())
                    .build());
        }
        return items;
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
     * Story 7.3: 归档 VIDEO/GIF 元数据到 sidecar.
     *
     * <p>构造 mutation 写入 previewImageUrl/variants/width/height/order/providerRawSummary(码率摘要) +
     * downloadStatus=SKIPPED + failureReason (Story 8.2 spike 决定复现路径). 不调用 MediaDownloadClient,
     * 不下载二进制. 通过 {@link TweetMediaArchiveWriter#updateMedia}/{@code updateMediaAtIndex}
     * read-modify-write 回写, 保留其他字段不变 (AC7 幂等性).
     *
     * @param publishedAt 推文发布时间,可为 null(null 时使用当前时刻)
     */
    private void archiveVideoOrGifMetadata(String tweetId, LocalDateTime publishedAt,
                                            TweetMedia media, String mediaId, int mediaIndex) {
        String variantSummary = buildVariantSummary(media.getType(), media.getVariants());
        int variantCount = media.getVariants() == null ? 0 : media.getVariants().size();
        Long maxBitrate = maxBitrate(media.getVariants());
        boolean hasPreview = StringUtils_hasText(media.getPreviewImageUrl());

        boolean updated = updateSidecar(tweetId, publishedAt, media, mediaId, mediaIndex,
                original -> original.toBuilder()
                        .id(mediaId)
                        .type(media.getType())
                        .previewImageUrl(media.getPreviewImageUrl())
                        .variants(media.getVariants() != null ? media.getVariants() : List.of())
                        .width(media.getWidth())
                        .height(media.getHeight())
                        .order(media.getOrder())
                        .providerRawSummary(variantSummary)
                        .downloadStatus(MediaDownloadStatus.SKIPPED)
                        .failureReason(VIDEO_GIF_SKIP_REASON)
                        .build());
        if (!updated) {
            throw new NonRetryableException("VIDEO/GIF sidecar 回写未命中: tweetId=" + tweetId
                    + " mediaId=" + mediaId + " mediaIndex=" + mediaIndex, null);
        }

        // W11: 仅输出标识符 + 摘要, 不含完整 variant URL / previewImageUrl 全文 (AC8, N4)
        log.info("VIDEO/GIF 元数据已归档: tweetId={}, mediaId={}, type={}, variantCount={}, maxBitrate={}, hasPreview={}",
                tweetId, mediaId, media.getType(), variantCount,
                maxBitrate != null ? maxBitrate : 0L, hasPreview);
    }

    /**
     * Story 7.3: 构造 variants 的紧凑码率/格式摘要, 绝不含完整 variant URL (N4).
     *
     * <p>格式: {@code "video:variants=N,maxBitrate=X,formats=ct1/ct2"}, contentType 缺失用 {@code unknown};
     * 截断到 {@value #VARIANT_SUMMARY_MAX_LENGTH} code points (N2 + R3-1).
     */
    private String buildVariantSummary(TweetMediaType type, List<TweetMediaVariant> variants) {
        List<TweetMediaVariant> safeVariants = sanitizedVariants(variants);
        int count = safeVariants.size();
        String prefix = type.name().toLowerCase() + ":variants=" + count;
        if (count == 0) {
            return prefix;
        }
        long maxBitrate = safeVariants.stream()
                .map(TweetMediaVariant::getBitrate)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .max().orElse(0L);
        String formats = safeVariants.stream()
                .map(v -> v.getContentType() != null ? v.getContentType() : "unknown")
                .collect(Collectors.joining("/"));
        String summary = prefix + ",maxBitrate=" + maxBitrate + ",formats=" + formats;
        return TextTruncateUtil.truncateByCodePoints(summary, VARIANT_SUMMARY_MAX_LENGTH);
    }

    private List<TweetMediaVariant> sanitizedVariants(List<TweetMediaVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        return variants.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /** 提取 variants 中的最大码率, 全部 null 时返回 null (D3: 不拆箱). */
    private Long maxBitrate(List<TweetMediaVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        Long max = null;
        for (TweetMediaVariant v : variants) {
            if (v != null && v.getBitrate() != null) {
                if (max == null || v.getBitrate() > max) {
                    max = v.getBitrate();
                }
            }
        }
        return max;
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

    private String effectiveMediaId(String tweetId, TweetMedia media, int index) {
        if (StringUtils_hasText(media.getId())) {
            return media.getId();
        }
        // Story 7.3: 兜底 suffix 类型感知 (photo/video/gif), 仍满足 sidecar id 唯一性契约
        TweetMediaType type = media.getType();
        String suffix = type == TweetMediaType.VIDEO ? "video"
                : type == TweetMediaType.GIF ? "gif" : "photo";
        return tweetId + ":" + index + ":" + suffix;
    }

    private boolean updateSidecar(String tweetId, LocalDateTime publishedAt, TweetMedia media, String mediaId,
                                  int mediaIndex, java.util.function.UnaryOperator<TweetMedia> mutation) {
        if (StringUtils_hasText(media.getId())) {
            return archiveWriter.updateMedia(tweetId, publishedAt, mediaId, mutation);
        } else {
            return archiveWriter.updateMediaAtIndex(tweetId, publishedAt, mediaIndex, mutation);
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
