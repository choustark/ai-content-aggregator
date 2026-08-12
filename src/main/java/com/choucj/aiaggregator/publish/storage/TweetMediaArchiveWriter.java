package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Story 7.1: X 推文媒体 sidecar 写入器 — 确定性目录 + media.json 序列化/读取/回写.
 *
 * <p>为每条推文生成目录 {@code {base-directory}/media/twitter/{yyyy-MM-dd}/{tweetId}/},
 * 同目录写入 {@code media.json} sidecar (字段见 {@link MediaArchiveRecord}). 仅负责元数据
 * 写入与回写, 不下载媒体文件 (Story 7.2/7.3), 不写 Redis (Story 7.4), 不算 publishability (Story 7.5).
 *
 * <p><b>关键设计决策:</b>
 * <ul>
 *   <li><b>IO 异常映射 NonRetryable</b> — 文件系统错误通常永久 (磁盘满/权限拒绝), 重试无意义,
 *       与 {@code MarkdownArchiver} 一致 (Story 2.5).</li>
 *   <li><b>路径穿越双重防御</b> — tweetId 白名单 {@code [A-Za-z0-9_-]+} + 解析后
 *       {@code .normalize()} + {@code startsWith(baseDirectory)} 断言.</li>
 *   <li><b>日期用 publishedAt 而非 now()</b> — 跨日重试时归档到原日期, 不污染新日期目录
 *       (与 MarkdownArchiver 一致); publishedAt 为 null 时 fallback now() 并 log.warn.</li>
 *   <li><b>base-directory fallback</b> — {@code twitter.media.base-directory} 为空时复用
 *       {@code archive.base-directory} (Story 2.5), 共享 Docker 卷挂载点.</li>
 *   <li><b>read-modify-write 单媒体回写</b> — {@link #updateMedia} 只改目标 mediaId, 不覆盖其他项,
 *       支持 Story 7.2-7.5/8.4 增量回写.</li>
 * </ul>
 *
 * <p><b>Story 2.4/2.5 review lessons 复用:</b>
 * <ul>
 *   <li>N4 — 异常 message 只含 tweetId + 路径 + 截断根因, 不含序列化 JSON 全文/媒体字节</li>
 *   <li>W11 — log.info 含 tweetId + mediaCount + 路径 + 字节 + 耗时</li>
 *   <li>跨包可见性 — 复用 {@link TextTruncateUtil#getRootMessage} / {@link TextTruncateUtil#truncateForLog}</li>
 *   <li>A7 — 不加 @EventListener(ApplicationReadyEvent); 由调用方触发</li>
 * </ul>
 *
 * <p>引用源: Story 7.1 (实现) / ARCHITECTURE-SPINE AD-6 + 一致性约定表 / Story 2.5 MarkdownArchiver 模式.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "twitter.media.enabled", havingValue = "true", matchIfMissing = false)
public class TweetMediaArchiveWriter {

    /**
     * tweetId 路径段白名单: 仅字母/数字/下划线/连字符, 拒绝 / 与 .. 防穿越 (呼应 Epic 4 Article.id 治理).
     *
     * <p>Story 7.1 review patch-5: 加 {1,64} 长度上限, 防止恶意/畸形超长 tweetId 叠加 base-directory
     * 与日期段突破 OS 单路径组件或总长度限制 (多数文件系统单段上限 255 字节, 总路径 ~4096)。
     * X 真实 tweetId 为 Long (≤19 位), 64 字符余量足够兼容自定义前缀。
     */
    private static final Pattern TWEET_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 异常 message 中根因截断长度 (N4). */
    private static final int LOG_MSG_MAX_LENGTH = 200;

    private final TwitterMediaProperties properties;
    private final ObjectMapper objectMapper;
    private final String baseDirectory;
    private final String sidecarFilename;
    private final DateTimeFormatter dateFormatter;

    /**
     * 每个归档目录的独占锁, 保护 read-modify-write 原子性 (Story 7.1 review patch-2).
     *
     * <p>Spring @Component 单例, 多线程 (并行媒体下载/重试) 并发调用 writeSidecar/updateMedia 时,
     * 若无锁则 read-modify-write 交错会丢失更新。按目录路径字符串 striped: 同一推文 (同 dir) 串行,
     * 不同推文并行。锁对象长期驻留 (每 tweet 一条), 量级 = 历史归档推文数, 媒体归档低频可接受;
     * 若未来高吞吐可换固定槽数 striped lock。
     */
    private final ConcurrentHashMap<String, Object> dirLocks = new ConcurrentHashMap<>();

    public TweetMediaArchiveWriter(TwitterMediaProperties properties,
                                   ArchiverProperties archiveProperties,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.baseDirectory = resolveBaseDirectory(properties, archiveProperties);
        this.sidecarFilename = properties.getSidecarFilename();
        this.dateFormatter = DateTimeFormatter.ofPattern(properties.getDatePattern());
        // 防御非法 datePattern (与 MarkdownArchiver.initFormatter 一致, @Pattern 已校验字符集, 此处校验语义)
        try {
            LocalDate.of(2000, 1, 2).format(this.dateFormatter);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new NonRetryableException("twitter.media.date-pattern 配置非法: " + properties.getDatePattern()
                    + " (需为合法 DateTimeFormatter pattern)", e);
        }
    }

    private static String resolveBaseDirectory(TwitterMediaProperties properties, ArchiverProperties archiveProperties) {
        if (StringUtils.hasText(properties.getBaseDirectory())) {
            return properties.getBaseDirectory();
        }
        return archiveProperties.getBaseDirectory();
    }

    /**
     * 写 media.json sidecar (全量覆盖写). AC1/AC2/AC3/AC5/AC6/AC7.
     *
     * @param tweetId    推文 ID (路径段, 白名单校验)
     * @param publishedAt 推文发布时间 (用于日期目录; null 时 fallback now())
     * @param media      媒体列表 (null 视为空)
     * @return 写入的 sidecar record
     */
    public MediaArchiveRecord writeSidecar(String tweetId, LocalDateTime publishedAt, List<TweetMedia> media) {
        List<TweetMedia> safeMedia = media == null ? List.of() : new ArrayList<>(media);
        MediaArchiveRecord record = MediaArchiveRecord.builder()
                .tweetId(tweetId)
                .generatedAt(LocalDateTime.now())
                .media(safeMedia)
                .build();

        long startNanos = System.nanoTime();
        Path dir = resolveArchiveDir(tweetId, publishedAt);
        // review patch-2: 按目录加锁, 保证整个 ensureDir+serialize+write 串行, 防止并发 writeSidecar
        // 与 updateMedia (内部调本方法, 同线程可重入) 交错丢失更新
        synchronized (dirLock(dir)) {
            ensureDirectoryExists(dir, tweetId);
            Path file = dir.resolve(sidecarFilename);

            byte[] bytes = serialize(record, tweetId);
            writeBytes(file, bytes, tweetId);

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("媒体 sidecar 写入成功: tweetId={}, mediaCount={}, 文件={}, 大小={}bytes, 耗时={}ms",
                    tweetId, safeMedia.size(), file, bytes.length, elapsedMs);
        }
        return record;
    }

    /**
     * 读 media.json sidecar. AC4. 不存在或读取失败返回 {@link Optional#empty()} (fail-open,
     * 与 MarkdownArchiver.isAlreadyArchived 一致), 调用方按未归档处理.
     *
     * <p><b>损坏 sidecar 区分 (review patch-3):</b> JSON 解析失败 ({@link JsonProcessingException})
     * 与"文件不存在"语义不同 — 前者意味着归档状态损坏, 用 log.error 暴露信号供运维介入; 仍 fail-open
     * 返回 empty (媒体重下载/重写幂等, 调用方按未归档重处理是安全降级), 不抛 NonRetryable 阻塞流程。
     *
     * <p><b>默认值 backfill (review patch-4):</b> {@code @Builder.Default} 在 Jackson 无参构造+setter
     * 反序列化路径下不生效 (Lombok 把字段初始化移到 builder), 旧 sidecar 缺 downloadStatus/uploadStatus/
     * publishability 字段时反序列化为 null。读出后统一补默认值, 免调用方 null-safe 负担 (D3 警示)。
     */
    public Optional<MediaArchiveRecord> readSidecar(String tweetId, LocalDateTime publishedAt) {
        Path file = resolveSidecarFile(tweetId, publishedAt);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return Optional.ofNullable(backfillDefaults(objectMapper.readValue(bytes, MediaArchiveRecord.class)));
        } catch (JsonProcessingException e) {
            log.error("media.json 损坏 (JSON 解析失败), 按未归档重新处理: tweetId={}, 文件={}, error={}",
                    tweetId, file, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return Optional.empty();
        } catch (IOException e) {
            log.warn("media.json 读取失败, 假定未归档继续: tweetId={}, 文件={}, error={}",
                    tweetId, file, TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return Optional.empty();
        }
    }

    /**
     * read-modify-write 单个媒体项. AC4. 只更新目标 mediaId, 不覆盖其他项字段.
     * 用于 Story 7.2-7.5/8.4 增量回写 localPath/downloadStatus/uploadStatus/publishability 等.
     *
     * @param tweetId    推文 ID
     * @param publishedAt 推文发布时间
     * @param mediaId    目标媒体 ID
     * @param mutation   字段变换 (对 TweetMedia 应用 toBuilder 修改)
     * @return true 若找到并更新; false 若 sidecar 不存在或 mediaId 未命中
     */
    public boolean updateMedia(String tweetId, LocalDateTime publishedAt, String mediaId, UnaryOperator<TweetMedia> mutation) {
        // review patch-6: null mediaId/mutation 是调用方编程错误, 快速失败而非静默返回 false 让调用方无感知
        Objects.requireNonNull(mediaId, "mediaId 不能为 null");
        Objects.requireNonNull(mutation, "mutation 不能为 null");
        Path dir = resolveArchiveDir(tweetId, publishedAt);
        // review patch-2: read-modify-write 整体加目录锁, 防止并发 updateMedia 之间、
        // 或 updateMedia 与独立 writeSidecar 交错导致丢失更新
        synchronized (dirLock(dir)) {
            Optional<MediaArchiveRecord> existing = readSidecar(tweetId, publishedAt);
            if (existing.isEmpty()) {
                log.warn("updateMedia 未找到 sidecar, 跳过: tweetId={}, mediaId={}", tweetId, mediaId);
                return false;
            }
            MediaArchiveRecord record = existing.get();
            List<TweetMedia> mediaList = record.getMedia();
            if (mediaList == null || mediaList.isEmpty()) {
                log.warn("updateMedia 媒体列表为空, 跳过: tweetId={}, mediaId={}", tweetId, mediaId);
                return false;
            }
            List<TweetMedia> updated = new ArrayList<>();
            boolean found = false;
            for (TweetMedia m : mediaList) {
                if (m == null) {
                    continue; // 退化 sidecar 含 null 元素, 跳过不中断
                }
                if (mediaId.equals(m.getId())) {
                    TweetMedia mutated = mutation.apply(m);
                    updated.add(mutated != null ? mutated : m);
                    found = true;
                } else {
                    updated.add(m);
                }
            }
            if (!found) {
                log.warn("updateMedia 未命中 mediaId, 跳过: tweetId={}, mediaId={}", tweetId, mediaId);
                return false;
            }
            writeSidecar(tweetId, publishedAt, updated);
            return true;
        }
    }

    /**
     * 按 media 列表下标回写单个媒体项, 用于 provider 未返回 mediaId 但调用方已生成稳定 synthetic id 的场景.
     *
     * <p>Story 7.2 AC2 要求 mediaId 为空时用 {@code tweetId:index:type} 兜底; 此时按 mediaId
     * 无法命中旧 sidecar 中的 null id, 必须用列表位置完成第一次回写并把 synthetic id 持久化到 sidecar。
     */
    public boolean updateMediaAtIndex(String tweetId, LocalDateTime publishedAt, int mediaIndex,
                                      UnaryOperator<TweetMedia> mutation) {
        Objects.requireNonNull(mutation, "mutation 不能为 null");
        if (mediaIndex < 0) {
            throw new NonRetryableException("mediaIndex 不能为负数: tweetId=" + tweetId
                    + " mediaIndex=" + mediaIndex, null);
        }
        Path dir = resolveArchiveDir(tweetId, publishedAt);
        synchronized (dirLock(dir)) {
            Optional<MediaArchiveRecord> existing = readSidecar(tweetId, publishedAt);
            if (existing.isEmpty()) {
                log.warn("updateMediaAtIndex 未找到 sidecar, 跳过: tweetId={}, mediaIndex={}", tweetId, mediaIndex);
                return false;
            }
            MediaArchiveRecord record = existing.get();
            List<TweetMedia> mediaList = record.getMedia();
            if (mediaList == null || mediaIndex >= mediaList.size() || mediaList.get(mediaIndex) == null) {
                log.warn("updateMediaAtIndex 未命中媒体下标, 跳过: tweetId={}, mediaIndex={}", tweetId, mediaIndex);
                return false;
            }
            List<TweetMedia> updated = new ArrayList<>(mediaList);
            TweetMedia original = mediaList.get(mediaIndex);
            TweetMedia mutated = mutation.apply(original);
            updated.set(mediaIndex, mutated != null ? mutated : original);
            writeSidecar(tweetId, publishedAt, updated);
            return true;
        }
    }

    /**
     * 解析推文归档目录 (不含 sidecar 文件名). AC1/AC6.
     *
     * <p>路径穿越双重防御: tweetId 白名单 + {@code .normalize()} + {@code startsWith(base)} 断言
     * (复用 MarkdownArchiver.resolveArchiveFile 模式).
     *
     * <p>public 可见性: TweetMediaArchiver 需要调用 (Story 7.2).
     */
    public Path resolveArchiveDir(String tweetId, LocalDateTime publishedAt) {
        validateTweetId(tweetId);
        LocalDateTime when = publishedAt != null ? publishedAt : LocalDateTime.now();
        if (publishedAt == null) {
            log.warn("推文 publishedAt 为 null, fallback 到当前时刻归档: tweetId={}", tweetId);
        }
        String datePart = when.toLocalDate().format(dateFormatter);
        Path baseNormalized = Path.of(baseDirectory).normalize();
        Path resolved = baseNormalized.resolve("media").resolve("twitter")
                .resolve(datePart).resolve(tweetId).normalize();
        if (!resolved.startsWith(baseNormalized)) {
            throw new NonRetryableException("媒体归档路径逃逸 baseDirectory: base=" + baseNormalized
                    + " resolved=" + resolved + " tweetId=" + tweetId, null);
        }
        return resolved;
    }

    /** 解析 sidecar 文件完整路径. */
    Path resolveSidecarFile(String tweetId, LocalDateTime publishedAt) {
        return resolveArchiveDir(tweetId, publishedAt).resolve(sidecarFilename);
    }

    private void validateTweetId(String tweetId) {
        if (!StringUtils.hasText(tweetId) || !TWEET_ID_PATTERN.matcher(tweetId).matches()) {
            throw new NonRetryableException("tweetId 非法或含路径穿越字符: tweetId=" + tweetId, null);
        }
    }

    /** 递归创建归档目录 (幂等). IOException/SecurityException → NonRetryable. AC5. */
    void ensureDirectoryExists(Path dir, String tweetId) {
        try {
            Files.createDirectories(dir);
        } catch (IOException | SecurityException e) {
            log.error("媒体归档目录创建失败 (永久错误): tweetId={}, 目录={}", tweetId, dir, e);
            throw new NonRetryableException("媒体归档目录创建失败: tweetId=" + tweetId + " 目录=" + dir, e);
        }
    }

    private byte[] serialize(MediaArchiveRecord record, String tweetId) {
        try {
            return objectMapper.writeValueAsBytes(record);
        } catch (JsonProcessingException e) {
            // N4: message 不含序列化 JSON 全文, 只含截断根因
            log.error("media.json 序列化失败 (永久错误): tweetId={}", tweetId, e);
            throw new NonRetryableException("media.json 序列化失败: tweetId=" + tweetId
                    + " cause=" + TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH), e);
        }
    }

    private void writeBytes(Path file, byte[] bytes, String tweetId) {
        try {
            // bytes 由 objectMapper.writeValueAsBytes 产生 (UTF-8); Files.write(byte[]) 不接受 Charset
            Files.write(file, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | SecurityException e) {
            log.error("媒体 sidecar 写入失败 (永久错误): tweetId={}, 文件={}", tweetId, file, e);
            throw new NonRetryableException("媒体 sidecar 写入失败: tweetId=" + tweetId + " 文件=" + file
                    + " cause=" + TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH), e);
        }
    }

    /** 获取目录级独占锁对象 (review patch-2): 同 dir 串行, 不同 dir 并行. */
    private Object dirLock(Path dir) {
        return dirLocks.computeIfAbsent(dir.toString(), key -> new Object());
    }

    /**
     * 反序列化后补 TweetMedia 生命周期字段的 {@code @Builder.Default} 默认值 (review patch-4).
     *
     * <p>旧 sidecar 或外部生成的 sidecar 缺 downloadStatus/uploadStatus/publishability 字段时,
     * Jackson 走无参构造+setter 反序列化得到 null (Lombok {@code @Builder.Default} 仅在 builder 构造时生效,
     * 不在无参构造时执行字段初始化)。读出后统一补默认, 免调用方 null-safe 负担 (D3 警示),
     * 也保证 readSidecar 契约: 返回的对象生命周期字段非 null。
     */
    private static MediaArchiveRecord backfillDefaults(MediaArchiveRecord record) {
        if (record == null) {
            return null;
        }
        List<TweetMedia> media = record.getMedia();
        if (media != null) {
            for (TweetMedia m : media) {
                if (m == null) {
                    continue;
                }
                if (m.getDownloadStatus() == null) {
                    m.setDownloadStatus(MediaDownloadStatus.PENDING);
                }
                if (m.getUploadStatus() == null) {
                    m.setUploadStatus(MediaUploadStatus.PENDING);
                }
                if (m.getPublishability() == null) {
                    m.setPublishability(PublishabilityStatus.UNKNOWN);
                }
            }
        }
        return record;
    }
}
