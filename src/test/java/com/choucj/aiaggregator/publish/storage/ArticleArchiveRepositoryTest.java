package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleArchiveRepositoryTest {

    @TempDir
    Path tempDir;

    private ArticleArchiveRepository repository;
    private Path archiveFile;

    @BeforeEach
    void setUp() throws IOException {
        ArchiverProperties properties = new ArchiverProperties();
        properties.setBaseDirectory(tempDir.resolve("archive").toString());
        repository = new ArticleArchiveRepository(properties, new ObjectMapper().findAndRegisterModules());
        archiveFile = tempDir.resolve("archive").resolve("2026-09-01.md");
        Files.createDirectories(archiveFile.getParent());
        Files.writeString(archiveFile, """
                <!-- article-id: tw-123 -->

                ## 标题

                - **日期**: 2026-09-01 01:00
                - **状态**: 已创建
                - **计划发布时间**:
                - **草稿创建时间**:
                - **来源**: 来源:@test

                正文

                ---

                """);
    }

    @Test
    void shouldSaveSnapshotAndFindDueArticles() {
        Article article = article("tw-123");
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 1, 8, 0);

        repository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH, scheduledAt, archiveFile);

        assertThat(repository.findByArticleId("tw-123"))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.PENDING_PUBLISH);
                    assertThat(snapshot.getScheduledPublishAt()).isEqualTo(scheduledAt);
                    assertThat(snapshot.getArticle().getTitle()).isEqualTo("标题");
                });
        assertThat(repository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 8, 1)))
                .extracting(ArchivedArticle::getArticleId)
                .containsExactly("tw-123");
    }

    @Test
    void shouldUpdateMarkdownStatusWhenDraftCreated() {
        Article article = article("tw-123");
        repository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH,
                LocalDateTime.of(2026, 9, 1, 8, 0), archiveFile);

        repository.markDraftCreated("tw-123", "media-1", LocalDateTime.of(2026, 9, 1, 8, 5));

        ArchivedArticle snapshot = repository.findByArticleId("tw-123").orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.DRAFT_CREATED);
        assertThat(snapshot.getWechatDraftMediaId()).isEqualTo("media-1");
        String markdown = readAll(archiveFile);
        assertThat(markdown).contains("- **状态**: 已推送草稿");
        assertThat(markdown).contains("- **草稿创建时间**: 2026-09-01 08:05");
        assertThat(markdown).contains("- **微信草稿 MediaId**: media-1");
    }

    @Test
    void shouldRejectUnsafeArticleId() {
        Article article = article("tw-../bad");

        assertThatThrownBy(() -> repository.saveSnapshot(article, ArticleStatus.CREATED, null, archiveFile))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 非法");
    }

    @Test
    void should_preserve_article_and_failure_evidence_in_delivery_failed_terminal_state() {
        Article article = article("tw-123");
        repository.saveSnapshot(article, ArticleStatus.MEDIA_PROCESSING, null, archiveFile);
        LocalDateTime failedAt = LocalDateTime.of(2026, 9, 1, 2, 0);

        repository.markDeliveryFailed("tw-123", "MEDIA_DOWNLOAD", "DOWNLOAD_FAILED",
                "safe summary", "delivery:tw-123", failedAt);

        ArchivedArticle snapshot = repository.findByArticleId("tw-123").orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.DELIVERY_FAILED);
        assertThat(snapshot.getArticle().getContent()).isEqualTo("正文");
        assertThat(snapshot.getFailureStage()).isEqualTo("MEDIA_DOWNLOAD");
        assertThat(snapshot.getFailureTaskId()).isEqualTo("delivery:tw-123");
        assertThat(snapshot.getFailedAt()).isEqualTo(failedAt);
        assertThatThrownBy(() -> repository.markStatus("tw-123", ArticleStatus.PENDING_PUBLISH))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void should_preserve_failure_evidence_when_reopened_for_controlled_replay() {
        repository.saveSnapshot(article("tw-123"), ArticleStatus.MEDIA_PROCESSING, null, archiveFile);
        LocalDateTime failedAt = LocalDateTime.of(2026, 9, 1, 2, 0);
        repository.markDeliveryFailed("tw-123", "MEDIA_DOWNLOAD", "DOWNLOAD_FAILED",
                "safe summary", "delivery:tw-123", failedAt);

        repository.reopenDeliveryFailedForReplay("tw-123");

        ArchivedArticle snapshot = repository.findByArticleId("tw-123").orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.CREATED);
        assertThat(snapshot.getFailureStage()).isEqualTo("MEDIA_DOWNLOAD");
        assertThat(snapshot.getFailureCode()).isEqualTo("DOWNLOAD_FAILED");
        assertThat(snapshot.getFailureSummary()).isEqualTo("safe summary");
        assertThat(snapshot.getFailureTaskId()).isEqualTo("delivery:tw-123");
        assertThat(snapshot.getFailedAt()).isEqualTo(failedAt);
    }

    @Test
    void should_reject_mark_draft_created_from_delivery_failed() {
        repository.saveSnapshot(article("tw-123"), ArticleStatus.CREATED, null, archiveFile);
        repository.markDeliveryFailed("tw-123", "MEDIA", "FAILED", "safe",
                "delivery:tw-123", LocalDateTime.now());

        assertThatThrownBy(() -> repository.markDraftCreated("tw-123", "media", LocalDateTime.now()))
                .isInstanceOf(NonRetryableException.class).hasMessageContaining("受保护终态");
    }

    @Test
    void should_reconcile_draft_receipt_from_delivery_failed_idempotently() {
        repository.saveSnapshot(article("tw-123"), ArticleStatus.CREATED, null, archiveFile);
        repository.markDeliveryFailed("tw-123", "WECHAT", "LOCAL_WRITE_FAILED", "safe",
                "delivery:tw-123", LocalDateTime.of(2026, 9, 1, 8, 0));
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 8, 5);

        repository.reconcileDraftCreatedFromReplay("tw-123", "media-1", createdAt);
        repository.reconcileDraftCreatedFromReplay("tw-123", "media-1", createdAt);

        ArchivedArticle snapshot = repository.findByArticleId("tw-123").orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.DRAFT_CREATED);
        assertThat(snapshot.getWechatDraftMediaId()).isEqualTo("media-1");
        assertThat(snapshot.getFailureTaskId()).isEqualTo("delivery:tw-123");
        assertThatThrownBy(() -> repository.reconcileDraftCreatedFromReplay(
                "tw-123", "media-other", createdAt))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void should_serialize_concurrent_read_modify_write_without_corrupting_snapshot() throws Exception {
        repository.saveSnapshot(article("tw-123"), ArticleStatus.CREATED, null, archiveFile);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> repository.saveSnapshot(
                    article("tw-123"), ArticleStatus.MEDIA_PROCESSING, null, archiveFile));
            var second = executor.submit(() -> repository.saveSnapshot(
                    article("tw-123"), ArticleStatus.PENDING_PUBLISH,
                    LocalDateTime.of(2026, 9, 1, 8, 0), archiveFile));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        ArchivedArticle snapshot = repository.findByArticleId("tw-123").orElseThrow();
        assertThat(snapshot.getArticle().getContent()).isEqualTo("正文");
        Path snapshotDir = tempDir.resolve("archive/articles");
        try (var files = Files.list(snapshotDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly("tw-123.json");
        }
    }

    private static Article article(String id) {
        return Article.builder()
                .id(id)
                .title("标题")
                .content("正文")
                .source("来源:@test")
                .createdAt(LocalDateTime.of(2026, 9, 1, 1, 0))
                .build();
    }

    private static String readAll(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
