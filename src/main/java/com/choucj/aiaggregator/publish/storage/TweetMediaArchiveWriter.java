package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.monitoring.MediaMetrics;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
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
 *   <li><b>原子写入 (Epic 8 retro 修复 #1)</b> — sidecar 经同目录临时文件 {@code *.tmp} +
 *       {@code ATOMIC_MOVE} 落盘, 写入失败/崩溃时旧文件保持完整; sidecar 为 PRESERVE 发布链路
 *       唯一权威状态源, 不容忍半写坏 JSON.</li>
 *   <li><b>列表结构保真 (Epic 8 retro 修复 #2/#3)</b> — {@link #updateMedia} 对 null 元素保位回填
 *       (列表不收缩, 防按 index 回写错位), 对重复 id 仅突变首个命中项.</li>
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
    /** Story 10.12: 媒体阶段成功率指标 (观测旁路; 测试直接构造传 null → 埋点静默关闭). */
    private final MediaMetrics mediaMetrics;
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
                                   ObjectMapper objectMapper,
                                   MediaMetrics mediaMetrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mediaMetrics = mediaMetrics;
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
        LocalDate canonicalDate = resolveCanonicalDate(tweetId, publishedAt, true);
        List<TweetMedia> safeMedia = media == null ? List.of() : new ArrayList<>(media);
        MediaArchiveRecord record = MediaArchiveRecord.builder()
                .tweetId(tweetId)
                .generatedAt(LocalDateTime.now())
                .canonicalArchiveDate(canonicalDate)
                .media(safeMedia)
                .build();

        long startNanos = System.nanoTime();
        Path dir = resolveArchiveDirForDate(tweetId, canonicalDate);
        // review patch-2: 按目录加锁, 保证整个 ensureDir+serialize+write 串行, 防止并发 writeSidecar
        // 与 updateMedia (内部调本方法, 同线程可重入) 交错丢失更新
        synchronized (dirLock(dir)) {
            ensureDirectoryExists(dir, tweetId);
            Path file = dir.resolve(sidecarFilename);

            if (Files.exists(file)) {
                readSidecar(tweetId, publishedAt).ifPresent(existing ->
                        record.setCanonicalArchiveDate(existing.getCanonicalArchiveDate()));
            }
            initializeSchema(record);

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
        LocalDate canonicalDate = resolveCanonicalDate(tweetId, publishedAt, false);
        Path file = resolveArchiveDirForDate(tweetId, canonicalDate).resolve(sidecarFilename);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            MediaArchiveRecord record = backfillDefaults(objectMapper.readValue(bytes, MediaArchiveRecord.class));
            if (record != null && record.getCanonicalArchiveDate() == null) {
                record.setCanonicalArchiveDate(canonicalDate);
            }
            writeCanonicalDateIndexIfAbsent(tweetId, canonicalDate);
            return Optional.ofNullable(record);
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
     * <p><b>结构保真语义 (Epic 8 retro 修复 #2/#3, 2026-08-29):</b>
     * <ul>
     *   <li>null 元素<b>保位回填</b> — 持久化列表长度与位置不变, 防止后续按 index 的
     *       {@link #updateMediaAtIndex} 回写错位 (跨媒体误写 / URL 丢失)。</li>
     *   <li>重复 id <b>仅突变首个命中项</b> — 同 id 多项不再被同一 mutation 全量覆盖
     *       (与 WeChatMediaPreparer 的 id 优先定位语义对齐)。</li>
     * </ul>
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
                    // Epic 8 retro 修复 #2 (2026-08-29): null 元素保位回填而非跳过 —
                    // 跳过会让持久化列表收缩, 后续按 index 的 updateMediaAtIndex 回写错位
                    // (上传 B 的微信 URL 写到 A 名下 / miss 后 URL 直接丢失)
                    updated.add(null);
                    continue;
                }
                if (!found && mediaId.equals(m.getId())) {
                    // Epic 8 retro 修复 #3 (2026-08-29): 仅突变首个命中项 —
                    // sidecar 含重复 id (多源发现重复媒体) 时, 一次上传不再把 N 个同 id 项
                    // 全部标记 UPLOADED 同一 wechatUrl; 与 preparer 的 id 优先定位语义对齐
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
        return resolveArchiveDirForDate(tweetId, resolveCanonicalDate(tweetId, publishedAt, false));
    }

    /** 不依赖执行日期读取首次持久化的 canonical sidecar，供跨日恢复、门禁与补跑使用。 */
    public Optional<MediaArchiveRecord> readCanonicalSidecar(String tweetId) {
        validateTweetId(tweetId);
        Optional<LocalDate> indexed = readCanonicalDateIndex(tweetId);
        if (indexed.isEmpty()) {
            return Optional.empty();
        }
        Path file = resolveArchiveDirForDate(tweetId, indexed.get()).resolve(sidecarFilename);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(backfillDefaults(objectMapper.readValue(Files.readAllBytes(file),
                    MediaArchiveRecord.class)));
        } catch (JsonProcessingException e) {
            log.error("canonical media.json 损坏: tweetId={}, error={}", tweetId,
                    TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return Optional.empty();
        } catch (IOException e) {
            log.warn("canonical media.json 读取失败: tweetId={}, error={}", tweetId,
                    TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return Optional.empty();
        }
    }

    /** 仅在最终正文实际包含已准备媒体 URL 时标记引用成功。 */
    public boolean markArticleReferencesSucceeded(String tweetId, String renderedContent) {
        Optional<LocalDate> indexed = readCanonicalDateIndex(tweetId);
        if (indexed.isEmpty()) {
            return false;
        }
        Path dir = resolveArchiveDirForDate(tweetId, indexed.get());
        synchronized (dirLock(dir)) {
            return markArticleReferencesSucceededLocked(tweetId, renderedContent);
        }
    }

    private boolean markArticleReferencesSucceededLocked(String tweetId, String renderedContent) {
        Optional<MediaArchiveRecord> existing = readCanonicalSidecar(tweetId);
        if (existing.isEmpty() || existing.get().getCanonicalArchiveDate() == null
                || existing.get().getMedia() == null || !StringUtils.hasText(renderedContent)) {
            return false;
        }
        MediaArchiveRecord record = existing.get();
        List<TweetMedia> required = record.getMedia().stream()
                .filter(Objects::nonNull)
                .filter(media -> media.getType() == TweetMediaType.PHOTO
                        || media.getType() == TweetMediaType.VIDEO)
                .toList();
        boolean allDeferredGif = required.isEmpty() && !record.getMedia().isEmpty()
                && record.getMedia().stream().allMatch(media -> media != null
                && media.getType() == TweetMediaType.GIF
                && media.getDownload() != null
                && media.getDownload().getStatus() == MediaPhaseStatus.DEFERRED
                && StringUtils.hasText(media.getOriginalPostUrl())
                && StringUtils.hasText(media.getManualInstruction()));
        if (allDeferredGif) {
            return true;
        }
        boolean evidenceComplete = !required.isEmpty() && required.stream().allMatch(media -> {
            if (media.getDownload() == null || media.getWechatPrepare() == null
                    || media.getDownload().getStatus() != MediaPhaseStatus.SUCCEEDED
                    || media.getWechatPrepare().getStatus() != MediaPhaseStatus.SUCCEEDED) {
                return false;
            }
            if (media.getType() == TweetMediaType.VIDEO) {
                // Story 10.11: VIDEO 引用完整性 = renderedContent 包含该媒体的
                // wechatVideoMediaId (候选 A 纯文本直嵌); 缺失/未嵌入 → fail-closed,
                // articleReference 不推进, readiness gate 持续阻断, 草稿 0。
                return StringUtils.hasText(media.getWechatVideoMediaId())
                        && renderedContent.contains(media.getWechatVideoMediaId());
            }
            return StringUtils.hasText(media.getWechatUrl())
                    && renderedContent.contains(media.getWechatUrl());
        });
        if (!evidenceComplete) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TweetMedia media : required) {
            media.setArticleReference(MediaPhaseState.builder()
                    .status(MediaPhaseStatus.SUCCEEDED).attempt(1).updatedAt(now).build());
        }
        writeSidecar(tweetId, record.getCanonicalArchiveDate().atStartOfDay(), record.getMedia());
        // Story 10.12: article_reference 引用成功计数在 writeSidecar 成功之后单点统一计 —
        // writeSidecar 抛异常 + 重放时不会双计 (观测旁路; 仅 PHOTO/VIDEO)
        required.forEach(this::recordReferenceSucceeded);
        return true;
    }

    /**
     * Story 10.8: 重试耗尽收敛钩子的 sidecar 终态化 — 把 canonical sidecar 中所有
     * {@code wechatPrepare=RETRY_SCHEDULED} 的媒体置为 {@code FAILED_TERMINAL}
     * (保留本次 attempt, nextRetryAt=null, errorClass=TERMINAL, errorCode/errorSummary 为
     * 安全摘要) + {@code uploadStatus=FAILED}。
     *
     * <p>仅处理仍处于 RETRY_SCHEDULED 的媒体: 已 SUCCEEDED (本轮重试已成功) 或已终态
     * (ENVIRONMENT_BLOCKED 等) 的证据不被覆盖。articleId 遵循 {@code tw-{tweetId}} 约定;
     * canonical date 索引缺失、sidecar 缺失或无可终态化媒体时返回 false (钩子侧只记日志)。
     */
    public boolean markWechatPrepareExhausted(String articleId, String errorCode, String safeSummary) {
        if (articleId == null || !articleId.startsWith("tw-") || articleId.length() <= 3) {
            return false;
        }
        String tweetId = articleId.substring(3);
        Optional<LocalDate> indexed = readCanonicalDateIndex(tweetId);
        if (indexed.isEmpty()) {
            return false;
        }
        Path dir = resolveArchiveDirForDate(tweetId, indexed.get());
        synchronized (dirLock(dir)) {
            Optional<MediaArchiveRecord> existing = readCanonicalSidecar(tweetId);
            if (existing.isEmpty() || existing.get().getMedia() == null) {
                return false;
            }
            MediaArchiveRecord record = existing.get();
            LocalDateTime now = LocalDateTime.now();
            boolean mutated = false;
            for (TweetMedia media : record.getMedia()) {
                if (media == null || media.getWechatPrepare() == null
                        || media.getWechatPrepare().getStatus() != MediaPhaseStatus.RETRY_SCHEDULED) {
                    continue;
                }
                media.setWechatPrepare(MediaPhaseState.builder()
                        .status(MediaPhaseStatus.FAILED_TERMINAL)
                        .attempt(Math.max(media.getWechatPrepare().getAttempt(), 0))
                        .nextRetryAt(null)
                        .errorClass("TERMINAL")
                        .errorCode(errorCode != null ? TextTruncateUtil.truncateForLog(errorCode, 64) : null)
                        .errorSummary(safeSummary)
                        .updatedAt(now)
                        .build());
                media.setUploadStatus(MediaUploadStatus.FAILED);
                // Story 10.12: 重试耗尽收敛 → wechat_prepare 终态计数 (观测旁路)
                recordPrepareExhausted(media);
                mutated = true;
            }
            if (mutated) {
                writeSidecar(tweetId, record.getCanonicalArchiveDate().atStartOfDay(), record.getMedia());
                log.info("wechatPrepare 重试耗尽已终态化: tweetId={}, errorCode={}",
                        tweetId, TextTruncateUtil.truncateForLog(errorCode, 64));
            }
            return mutated;
        }
    }

    // ===== Story 10.12: 媒体阶段指标 (观测旁路; mediaMetrics=null 时静默关闭) =====

    /** article_reference 引用成功计数 (PHOTO/VIDEO; GIF/UNKNOWN 不建指标). */
    private void recordReferenceSucceeded(TweetMedia media) {
        MediaMetrics.MediaType type = metricTypeOf(media);
        if (mediaMetrics != null && type != null) {
            mediaMetrics.recordSucceeded(type, MediaMetrics.MediaPhase.ARTICLE_REFERENCE);
        }
    }

    /** wechat_prepare 重试耗尽终态计数 (PERMANENT 分类). */
    private void recordPrepareExhausted(TweetMedia media) {
        MediaMetrics.MediaType type = metricTypeOf(media);
        if (mediaMetrics != null && type != null) {
            mediaMetrics.record(type, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                    MediaMetrics.MediaOutcome.FAILED_TERMINAL, MediaMetrics.MediaErrorClass.PERMANENT);
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

    private Path resolveArchiveDirForDate(String tweetId, LocalDate date) {
        validateTweetId(tweetId);
        String datePart = date.format(dateFormatter);
        Path baseNormalized = Path.of(baseDirectory).normalize();
        Path resolved = baseNormalized.resolve("media").resolve("twitter")
                .resolve(datePart).resolve(tweetId).normalize();
        if (!resolved.startsWith(baseNormalized)) {
            throw new NonRetryableException("媒体归档路径逃逸 baseDirectory: base=" + baseNormalized
                    + " resolved=" + resolved + " tweetId=" + tweetId, null);
        }
        return resolved;
    }

    private LocalDate resolveCanonicalDate(String tweetId, LocalDateTime publishedAt, boolean persist) {
        validateTweetId(tweetId);
        Optional<LocalDate> indexed = readCanonicalDateIndex(tweetId);
        if (indexed.isPresent()) {
            return indexed.get();
        }
        LocalDate date = (publishedAt != null ? publishedAt : LocalDateTime.now()).toLocalDate();
        if (publishedAt == null) {
            log.warn("推文 publishedAt 为 null, fallback 到当前时刻归档: tweetId={}", tweetId);
        }
        if (persist) {
            writeCanonicalDateIndexIfAbsent(tweetId, date);
            return readCanonicalDateIndex(tweetId)
                    .orElseThrow(() -> new NonRetryableException(
                            "canonical archive date 索引写后缺失: tweetId=" + tweetId, null));
        }
        return date;
    }

    private Optional<LocalDate> readCanonicalDateIndex(String tweetId) {
        Path index = canonicalIndexFile(tweetId);
        if (!Files.exists(index)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(Files.readString(index, StandardCharsets.UTF_8).trim(), dateFormatter));
        } catch (IOException | DateTimeException e) {
            throw new NonRetryableException("canonical archive date 索引损坏: tweetId=" + tweetId, e);
        }
    }

    private void writeCanonicalDateIndexIfAbsent(String tweetId, LocalDate date) {
        Path index = canonicalIndexFile(tweetId);
        synchronized (dirLock(index.getParent())) {
            if (Files.exists(index)) {
                return;
            }
            try {
                Files.createDirectories(index.getParent());
                Files.writeString(index, date.format(dateFormatter), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // 并发首次创建由唯一赢家冻结日期。
            } catch (IOException | SecurityException e) {
                throw new NonRetryableException("媒体归档目录创建失败或 canonical archive date 索引写入失败: tweetId="
                        + tweetId, e);
            }
        }
    }

    private Path canonicalIndexFile(String tweetId) {
        validateTweetId(tweetId);
        Path base = Path.of(baseDirectory).normalize();
        Path index = base.resolve("media").resolve("twitter").resolve(".canonical")
                .resolve(tweetId + ".date").normalize();
        if (!index.startsWith(base)) {
            throw new NonRetryableException("canonical archive date 索引路径逃逸: tweetId=" + tweetId, null);
        }
        return index;
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
        // Epic 8 retro 修复 #1 (2026-08-29): 临时文件 + 原子 move 替代 TRUNCATE_EXISTING 直接覆盖写。
        // 直接覆盖写在进程崩溃/磁盘故障时会留下半写坏的 media.json — sidecar 现为 PRESERVE 发布链路
        // 唯一权威状态源, 损坏意味着该推文全部媒体状态丢失 (重下载+重上传烧微信配额)。
        // 临时文件与目标同目录 (同文件系统, 原子 move 可用), 写失败时旧 sidecar 保持完整。
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            // bytes 由 objectMapper.writeValueAsBytes 产生 (UTF-8); Files.write(byte[]) 不接受 Charset
            Files.write(tempFile, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 防御性回退: 同目录 tmp 理论上不跨文件系统, 但异常文件系统/网络挂载可能不支持原子 move
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException e) {
            // best-effort 清理残留 tmp (失败不影响异常语义, 下次写入会 CREATE+TRUNCATE 覆盖)
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // 清理失败不掩盖原始写入异常
            }
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
        if (record.getCanonicalArchiveDate() == null && record.getGeneratedAt() != null) {
            record.setCanonicalArchiveDate(record.getGeneratedAt().toLocalDate());
        }
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
                backfillPhases(m);
            }
        }
        return record;
    }

    private static void initializeSchema(MediaArchiveRecord record) {
        if (record.getMedia() == null) {
            return;
        }
        for (TweetMedia media : record.getMedia()) {
            if (media == null) {
                continue;
            }
            if (media.getType() == TweetMediaType.GIF
                    || (media.getType() == TweetMediaType.UNKNOWN && media.getManualInstruction() == null)) {
                media.initializeDeliveryPhases(media.getOriginalPostUrl());
            } else {
                reconcilePhasesFromCompatibility(media);
            }
            projectCompatibility(media);
        }
    }

    private static void backfillPhases(TweetMedia media) {
        if (media.getType() == TweetMediaType.GIF) {
            media.initializeDeliveryPhases(media.getOriginalPostUrl());
            projectCompatibility(media);
            return;
        }
        reconcilePhasesFromCompatibility(media);
        projectCompatibility(media);
    }

    /**
     * 旧链路仍通过 downloadStatus/uploadStatus 增量更新；在其迁移完成前，写盘时把这些
     * 已完成/失败结果投影到新权威阶段，避免新 schema 反向把真实结果重置为 PENDING。
     */
    private static void reconcilePhasesFromCompatibility(TweetMedia media) {
        if (media.getDownload() == null
                || media.getDownload().getStatus() == null
                || media.getDownload().getStatus() == MediaPhaseStatus.NOT_STARTED) {
            MediaPhaseStatus status = switch (media.getDownloadStatus()) {
                case DOWNLOADED -> MediaPhaseStatus.SUCCEEDED;
                case FAILED -> MediaPhaseStatus.FAILED_TERMINAL;
                default -> MediaPhaseStatus.NOT_STARTED;
            };
            media.setDownload(MediaPhaseState.builder().status(status)
                    .attempt(status == MediaPhaseStatus.FAILED_TERMINAL ? 1 : 0)
                    .updatedAt(status == MediaPhaseStatus.NOT_STARTED ? null : LocalDateTime.now())
                    .build());
        }
        if (media.getWechatPrepare() == null
                || media.getWechatPrepare().getStatus() == null
                || media.getWechatPrepare().getStatus() == MediaPhaseStatus.NOT_STARTED) {
            MediaPhaseStatus status = media.getUploadStatus() == MediaUploadStatus.UPLOADED
                    ? MediaPhaseStatus.SUCCEEDED : MediaPhaseStatus.NOT_STARTED;
            media.setWechatPrepare(MediaPhaseState.builder().status(status)
                    .updatedAt(status == MediaPhaseStatus.NOT_STARTED ? null : LocalDateTime.now()).build());
        }
        if (media.getArticleReference() == null) {
            media.setArticleReference(MediaPhaseState.notStarted());
        }
    }

    private static void projectCompatibility(TweetMedia media) {
        MediaPhaseStatus download = media.getDownload().getStatus();
        media.setDownloadStatus(switch (download) {
            case SUCCEEDED -> MediaDownloadStatus.DOWNLOADED;
            case FAILED_TERMINAL, ENVIRONMENT_BLOCKED -> MediaDownloadStatus.FAILED;
            case DEFERRED -> MediaDownloadStatus.SKIPPED;
            default -> media.getDownloadStatus() == null ? MediaDownloadStatus.PENDING : media.getDownloadStatus();
        });
        MediaPhaseStatus prepare = media.getWechatPrepare().getStatus();
        media.setUploadStatus(switch (prepare) {
            case SUCCEEDED -> MediaUploadStatus.UPLOADED;
            case FAILED_TERMINAL, ENVIRONMENT_BLOCKED -> MediaUploadStatus.FAILED;
            case DEFERRED -> MediaUploadStatus.SKIPPED;
            default -> media.getUploadStatus() == null ? MediaUploadStatus.PENDING : media.getUploadStatus();
        });
    }
}
