package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件型发布池: Markdown 给人看, JSON 快照给机器恢复发布.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ArticleArchiveRepository {

    private static final String ARTICLE_ID_MARKER_TEMPLATE = "<!-- article-id: %s -->";

    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("(tw|gh)-[A-Za-z0-9_-]+");
    private static final Object[] ARTICLE_LOCKS = new Object[64];
    static { java.util.Arrays.setAll(ARTICLE_LOCKS, ignored -> new Object()); }

    private final ArchiverProperties properties;
    private final ObjectMapper objectMapper;

    public ArchivedArticle saveSnapshot(Article article, ArticleStatus targetStatus,
                                        LocalDateTime scheduledPublishAt, Path archiveFile) {
        requireArticle(article);
        synchronized (articleLock(article.getId())) {
            ArticleStatus desiredStatus = targetStatus == null ? ArticleStatus.CREATED : targetStatus;
            ArchivedArticle existing = findByArticleId(article.getId()).orElse(null);
            ArticleStatus status = mergeStatus(existing == null ? null : existing.getStatus(), desiredStatus);
            ArchivedArticle snapshot = existing == null ? new ArchivedArticle() : existing;
            snapshot.setArticleId(article.getId());
            snapshot.setArticle(article);
            snapshot.setCreatedAt(article.getCreatedAt());
            snapshot.setStatus(status);
            if (scheduledPublishAt != null) snapshot.setScheduledPublishAt(scheduledPublishAt);
            if (archiveFile != null) snapshot.setArchiveFile(normalizeArchivePath(archiveFile));
            else if (snapshot.getArchiveFile() == null) snapshot.setArchiveFile("");
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    public Optional<ArchivedArticle> findByArticleId(String articleId) {
        validateArticleId(articleId);
        Path file = snapshotFile(articleId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), ArchivedArticle.class));
        } catch (IOException e) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "读取文章归档快照失败: articleId=" + articleId + " 文件=" + file, e);
        }
    }

    public List<ArchivedArticle> findDueForPublish(LocalDateTime now) {
        Path dir = snapshotDir();
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<ArchivedArticle> due = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> readDue(path, now).ifPresent(due::add));
            return due;
        } catch (IOException e) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "扫描待发布文章失败: 目录=" + dir, e);
        }
    }

    public ArchivedArticle markStatus(String articleId, ArticleStatus status) {
        validateArticleId(articleId);
        synchronized (articleLock(articleId)) {
            ArchivedArticle snapshot = findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
            if (snapshot.getStatus() == ArticleStatus.DELIVERY_FAILED && status != ArticleStatus.DELIVERY_FAILED) {
                throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "DELIVERY_FAILED 为受保护终态，必须通过显式补跑创建新任务: articleId=" + articleId);
            }
            snapshot.setStatus(status);
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    /** 原子记录文章交付失败，并保留已经生成的 Article 与首次失败证据。 */
    public ArchivedArticle markDeliveryFailed(String articleId, String stage, String code,
                                               String summary, String taskId, LocalDateTime failedAt) {
        validateArticleId(articleId);
        synchronized (articleLock(articleId)) {
            ArchivedArticle snapshot = findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
            snapshot.setStatus(ArticleStatus.DELIVERY_FAILED);
            if (snapshot.getFailedAt() == null) {
                snapshot.setFailureStage(stage); snapshot.setFailureCode(code);
                snapshot.setFailureSummary(summary); snapshot.setFailureTaskId(taskId);
                snapshot.setFailedAt(failedAt);
            }
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    /** 人工已修复媒体后，保留失败证据并将文章重新交给受控补跑任务。 */
    public ArchivedArticle reopenDeliveryFailedForReplay(String articleId) {
        validateArticleId(articleId);
        synchronized (articleLock(articleId)) {
            ArchivedArticle snapshot = findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
            if (snapshot.getStatus() != ArticleStatus.DELIVERY_FAILED) {
                throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章不处于 DELIVERY_FAILED，拒绝补跑恢复: articleId=" + articleId);
            }
            snapshot.setStatus(ArticleStatus.CREATED);
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    public ArchivedArticle markDraftCreated(String articleId, String mediaId, LocalDateTime draftCreatedAt) {
        validateArticleId(articleId);
        synchronized (articleLock(articleId)) {
            ArchivedArticle snapshot = findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
            if (snapshot.getStatus() == ArticleStatus.DELIVERY_FAILED) {
                throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "DELIVERY_FAILED 为受保护终态，拒绝写入草稿成功: articleId=" + articleId);
            }
            snapshot.setStatus(ArticleStatus.DRAFT_CREATED);
            snapshot.setWechatDraftMediaId(mediaId);
            snapshot.setDraftCreatedAt(draftCreatedAt);
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    /**
     * 根据补跑任务中已持久化的微信成功收据完成本地对账。该入口允许从补跑中间态恢复，
     * 但若快照已是草稿成功且 mediaId 不一致则 fail-closed，避免覆盖冲突证据。
     */
    public ArchivedArticle reconcileDraftCreatedFromReplay(String articleId, String mediaId,
                                                            LocalDateTime draftCreatedAt) {
        validateArticleId(articleId);
        if (mediaId == null || mediaId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "补跑草稿收据 mediaId 不能为空: articleId=" + articleId);
        }
        synchronized (articleLock(articleId)) {
            ArchivedArticle snapshot = findByArticleId(articleId)
                    .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                            "文章归档快照不存在: articleId=" + articleId));
            if (snapshot.getStatus() == ArticleStatus.DRAFT_CREATED) {
                if (!mediaId.equals(snapshot.getWechatDraftMediaId())) {
                    throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                            "补跑草稿收据与既有草稿冲突: articleId=" + articleId);
                }
                return snapshot;
            }
            snapshot.setStatus(ArticleStatus.DRAFT_CREATED);
            snapshot.setWechatDraftMediaId(mediaId);
            snapshot.setDraftCreatedAt(draftCreatedAt);
            writeSnapshot(snapshot);
            updateMarkdownStatus(snapshot);
            return snapshot;
        }
    }

    private Object articleLock(String articleId) {
        String key = snapshotFile(articleId).toAbsolutePath().normalize().toString();
        return ARTICLE_LOCKS[(key.hashCode() & Integer.MAX_VALUE) % ARTICLE_LOCKS.length];
    }

    private Optional<ArchivedArticle> readDue(Path path, LocalDateTime now) {
        try {
            ArchivedArticle snapshot = objectMapper.readValue(path.toFile(), ArchivedArticle.class);
            if (!isPublishableStatus(snapshot.getStatus())) {
                return Optional.empty();
            }
            LocalDateTime scheduledAt = snapshot.getScheduledPublishAt();
            if (scheduledAt == null || scheduledAt.isAfter(now)) {
                return Optional.empty();
            }
            if (snapshot.getArticle() == null) {
                log.warn("待发布快照缺少 Article, 跳过: articleId={}, 文件={}", snapshot.getArticleId(), path);
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (IOException e) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "读取待发布文章失败: 文件=" + path, e);
        }
    }

    private static boolean isPublishableStatus(ArticleStatus status) {
        return status == ArticleStatus.PENDING_PUBLISH;
    }

    private void writeSnapshot(ArchivedArticle snapshot) {
        Path file = snapshotFile(snapshot.getArticleId());
        Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshot);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不能掩盖原始快照写入错误。
            }
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "写入文章归档快照失败: articleId=" + snapshot.getArticleId() + " 文件=" + file, e);
        }
    }

    private void updateMarkdownStatus(ArchivedArticle snapshot) {
        if (snapshot.getArchiveFile() == null || snapshot.getArchiveFile().isBlank()) {
            return;
        }
        Path file = Path.of(snapshot.getArchiveFile());
        if (!Files.exists(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String marker = String.format(ARTICLE_ID_MARKER_TEMPLATE, snapshot.getArticleId());
            int markerIndex = content.indexOf(marker);
            if (markerIndex < 0) {
                return;
            }
            int nextMarker = content.indexOf("<!-- article-id: ", markerIndex + marker.length());
            int blockEnd = nextMarker < 0 ? content.length() : nextMarker;
            String block = content.substring(markerIndex, blockEnd);
            String updated = replaceOrInsertLine(block, "- **状态**: ", "- **状态**: " + statusLabel(snapshot.getStatus()));
            updated = replaceOrInsertLine(updated, "- **计划发布时间**: ",
                    "- **计划发布时间**: " + formatTime(snapshot.getScheduledPublishAt()));
            updated = replaceOrInsertLine(updated, "- **草稿创建时间**: ",
                    "- **草稿创建时间**: " + formatTime(snapshot.getDraftCreatedAt()));
            if (snapshot.getWechatDraftMediaId() != null && !snapshot.getWechatDraftMediaId().isBlank()) {
                updated = replaceOrInsertLine(updated, "- **微信草稿 MediaId**: ",
                        "- **微信草稿 MediaId**: " + snapshot.getWechatDraftMediaId());
            }
            if (!updated.equals(block)) {
                Files.writeString(file,
                        content.substring(0, markerIndex) + updated + content.substring(blockEnd),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("归档 Markdown 状态回写失败, 保留 JSON 状态: articleId={}, 文件={}",
                    snapshot.getArticleId(), file, e);
        }
    }

    private static String replaceOrInsertLine(String block, String prefix, String line) {
        String[] lines = block.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) {
                lines[i] = line;
                return String.join("\n", lines);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("- **日期**: ")) {
                return insertLineAfterStatusMetadata(lines, i + 1, line);
            }
        }
        return block;
    }

    private static String insertLineAfterStatusMetadata(String[] lines, int startIndex, String line) {
        int insertAt = startIndex;
        while (insertAt < lines.length && isStatusMetadataLine(lines[insertAt])) {
            insertAt++;
        }
        List<String> updated = new ArrayList<>(List.of(lines));
        updated.add(insertAt, line);
        return String.join("\n", updated);
    }

    private static boolean isStatusMetadataLine(String line) {
        return line.startsWith("- **状态**: ")
                || line.startsWith("- **计划发布时间**: ")
                || line.startsWith("- **草稿创建时间**: ")
                || line.startsWith("- **微信草稿 MediaId**: ");
    }

    private static String statusLabel(ArticleStatus status) {
        if (status == ArticleStatus.DRAFT_CREATED) {
            return "已推送草稿";
        }
        if (status == ArticleStatus.PROCESSING) {
            return "发布中";
        }
        if (status == ArticleStatus.PENDING_PUBLISH) {
            return "待发布";
        }
        if (status == ArticleStatus.MEDIA_PROCESSING) {
            return "媒体处理中";
        }
        if (status == ArticleStatus.DELIVERY_FAILED) {
            return "交付失败";
        }
        return "已创建";
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.toString().replace('T', ' ');
    }

    private ArticleStatus mergeStatus(ArticleStatus existing, ArticleStatus desired) {
        if (existing == ArticleStatus.DRAFT_CREATED || existing == ArticleStatus.DELIVERY_FAILED) {
            return existing;
        }
        if (desired == ArticleStatus.CREATED && existing != null) {
            return existing;
        }
        return desired;
    }

    private Path snapshotFile(String articleId) {
        validateArticleId(articleId);
        return snapshotDir().resolve(articleId + ".json").normalize();
    }

    private Path snapshotDir() {
        Path base = Path.of(properties.getBaseDirectory()).normalize();
        Path dir = base.resolve("articles").normalize();
        if (!dir.startsWith(base)) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "文章快照目录逃逸 baseDirectory: base=" + base + " resolved=" + dir);
        }
        return dir;
    }

    private String normalizeArchivePath(Path archiveFile) {
        return archiveFile.normalize().toString();
    }

    private static void requireArticle(Article article) {
        if (article == null || article.getId() == null || article.getId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article.id 不能为空");
        }
        validateArticleId(article.getId());
        if (article.getCreatedAt() == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article.createdAt 不能为空, articleId=" + article.getId());
        }
    }

    private static void validateArticleId(String articleId) {
        if (articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article.id 不能为空");
        }
        if (!ARTICLE_ID_PATTERN.matcher(articleId).matches()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article.id 非法 (必须匹配 (tw|gh)-[A-Za-z0-9_-]+): " + articleId);
        }
    }
}
