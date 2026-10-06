package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.wechat.ArticleMediaReadinessGate;
import com.choucj.aiaggregator.publish.wechat.ArticlePublicationWorkflow;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ArticleDeliveryReplayExecutorTest {
    private final ArticleArchiveRepository repository = mock(ArticleArchiveRepository.class);
    private final ArticleMediaReadinessGate gate = mock(ArticleMediaReadinessGate.class);
    private final ArticlePublicationWorkflow workflow = mock(ArticlePublicationWorkflow.class);
    private final TaskQueue taskQueue = mock(TaskQueue.class);
    private final ArticleStatusService statusService = mock(ArticleStatusService.class);
    private final ArticleDeliveryReplayExecutor executor =
            new ArticleDeliveryReplayExecutor(repository, gate, workflow, taskQueue, statusService);

    @Test
    void should_route_by_state_metadata_and_reuse_snapshot_article() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder()
                .articleId("tw-1").article(article).status(ArticleStatus.DELIVERY_FAILED)
                .failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("opaque-replay-task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.READY);

        executor.execute("opaque-replay-task");

        var ordered = inOrder(repository, workflow);
        ordered.verify(repository).reopenDeliveryFailedForReplay("tw-1");
        ordered.verify(workflow).publishRealtime(article);
    }

    @Test
    void should_keep_failure_closed_when_media_is_not_ready() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder()
                .articleId("tw-1").article(article).status(ArticleStatus.DELIVERY_FAILED)
                .failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);

        assertThatThrownBy(() -> executor.execute("task"))
                .hasMessageContaining("媒体仍未就绪");
        verify(repository, never()).reopenDeliveryFailedForReplay("tw-1");
        verify(workflow, never()).publishRealtime(article);
    }

    @Test
    void should_restore_delivery_failed_and_redis_mirror_when_publish_fails() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1")
                .failureStage("WECHAT_PREPARE").failureCode("PREPARE_FAILED")
                .failureSummary("safe").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.READY);
        doThrow(new IllegalStateException("publish failed")).when(workflow).publishRealtime(article);

        assertThatThrownBy(() -> executor.execute("task")).isInstanceOf(IllegalStateException.class);
        verify(repository).markDeliveryFailed("tw-1", "WECHAT_PREPARE", "PREPARE_FAILED",
                "safe", "delivery:tw-1", null);
        verify(statusService).markDeliveryFailed("tw-1");
    }

    @Test
    void should_persist_receipt_and_reconcile_without_restoring_failure() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.READY);
        doThrow(new ArticlePublicationWorkflow.DraftCreatedPersistenceException(
                "tw-1", "media-1", new IllegalStateException("disk")))
                .when(workflow).publishRealtime(article);
        when(statusService.getStatus("tw-1")).thenReturn(Optional.of(ArticleStatus.DRAFT_CREATED));

        executor.execute("task");

        verify(taskQueue).recordReplayDraftReceipt("task", "media-1");
        verify(repository).reconcileDraftCreatedFromReplay(
                org.mockito.ArgumentMatchers.eq("tw-1"),
                org.mockito.ArgumentMatchers.eq("media-1"),
                org.mockito.ArgumentMatchers.any());
        verify(statusService).markDraftCreated("tw-1");
        verify(repository, never()).markDeliveryFailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_reconcile_existing_receipt_without_calling_wechat_again() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.PROCESSING).failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1", "media-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(statusService.getStatus("tw-1")).thenReturn(Optional.of(ArticleStatus.DRAFT_CREATED));

        executor.execute("task");

        verify(repository).reconcileDraftCreatedFromReplay(
                org.mockito.ArgumentMatchers.eq("tw-1"),
                org.mockito.ArgumentMatchers.eq("media-1"),
                org.mockito.ArgumentMatchers.any());
        verify(workflow, never()).publishRealtime(article);
        verify(gate, never()).evaluate(article);
    }

    @Test
    void should_attempt_both_rollbacks_and_preserve_original_failure() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1").build();
        IllegalStateException original = new IllegalStateException("publish failed");
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.READY);
        doThrow(original).when(workflow).publishRealtime(article);
        doThrow(new IllegalStateException("file rollback failed")).when(repository)
                .markDeliveryFailed("tw-1", null, null, null, "delivery:tw-1", null);
        doThrow(new IllegalStateException("redis rollback failed")).when(statusService)
                .markDeliveryFailed("tw-1");

        assertThatThrownBy(() -> executor.execute("task"))
                .isSameAs(original)
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(2));
        verify(statusService).markDeliveryFailed("tw-1");
    }

    @Test
    void should_stop_automatic_retry_when_receipt_cannot_be_persisted() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(gate.evaluate(article)).thenReturn(ArticleMediaReadinessGate.GateResult.READY);
        doThrow(new ArticlePublicationWorkflow.DraftCreatedPersistenceException(
                "tw-1", "media-1", new IllegalStateException("disk")))
                .when(workflow).publishRealtime(article);
        doThrow(new IllegalStateException("disk unavailable")).when(repository)
                .reconcileDraftCreatedFromReplay(
                        org.mockito.ArgumentMatchers.eq("tw-1"),
                        org.mockito.ArgumentMatchers.eq("media-1"),
                        org.mockito.ArgumentMatchers.any());
        doThrow(new IllegalStateException("redis unavailable")).when(taskQueue)
                .recordReplayDraftReceipt("task", "media-1");

        assertThatThrownBy(() -> executor.execute("task"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("停止自动补跑");
        verify(repository).reconcileDraftCreatedFromReplay(
                org.mockito.ArgumentMatchers.eq("tw-1"), org.mockito.ArgumentMatchers.eq("media-1"),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).markDeliveryFailed(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_repair_only_redis_when_local_snapshot_already_contains_draft_receipt() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DRAFT_CREATED).wechatDraftMediaId("media-1")
                .failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:tw-1"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        when(statusService.getStatus("tw-1")).thenReturn(Optional.of(ArticleStatus.DRAFT_CREATED));

        executor.execute("task");

        verify(statusService).markDraftCreated("tw-1");
        verify(workflow, never()).publishRealtime(article);
        verify(gate, never()).evaluate(article);
    }

    @Test
    void should_reject_replay_source_mismatch() {
        Article article = Article.builder().id("tw-1").build();
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1").article(article)
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1").build();
        when(taskQueue.getReplayMetadata("task"))
                .thenReturn(new TaskQueue.ReplayMetadata("tw-1", "delivery:other"));
        when(repository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> executor.execute("task")).hasMessageContaining("来源与失败快照不一致");
        verify(repository, never()).reopenDeliveryFailedForReplay("tw-1");
    }
}
