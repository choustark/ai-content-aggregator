package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ArticlePublicationWorkflowTest {

    @Mock
    private WeChatPublisher weChatPublisher;
    @Mock
    private ArticleArchiveRepository archiveRepository;
    @Mock
    private ArticleStatusService articleStatusService;
    @Mock
    private TaskMetrics taskMetrics;
    @Mock
    private ArticleMediaReadinessGate mediaReadinessGate;

    private PublishingProperties publishingProperties;
    private ArticlePublicationWorkflow workflow;
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        workflow = new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.empty(), clock);
    }

    /** 注入指标 mock 的工作流(Story 10.6 幂等指标用例). */
    private ArticlePublicationWorkflow workflowWithMetrics() {
        return new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.of(taskMetrics), clock);
    }

    private ArticlePublicationWorkflow workflowWithMediaGate() {
        return new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.empty(),
                Optional.of(mediaReadinessGate), clock);
    }

    @Test
    void should_block_realtime_publish_before_pending_or_draft_when_media_incomplete() {
        Article article = article("tw-media");
        when(mediaReadinessGate.evaluate(article))
                .thenReturn(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);

        assertThatThrownBy(() -> workflowWithMediaGate().publishRealtime(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("PHASE_INCOMPLETE");
        verify(archiveRepository, never()).saveSnapshot(any(), any(), any(), any());
        verify(weChatPublisher, never()).publishDraft(any());
    }

    @Test
    void should_block_queueing_when_media_is_incomplete() {
        Article article = article("tw-queue-media");
        when(mediaReadinessGate.evaluate(article))
                .thenReturn(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);

        assertThatThrownBy(() -> workflowWithMediaGate().queueForNextPublishWindow(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("PHASE_INCOMPLETE");
        verify(archiveRepository, never()).saveSnapshot(any(), any(), any(), any());
        verify(articleStatusService, never()).markPendingPublish(anyString());
    }

    @Test
    void should_block_due_publish_when_media_is_incomplete() {
        Article article = article("tw-due-media");
        when(archiveRepository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 20, 0)))
                .thenReturn(List.of(snapshot(article, ArticleStatus.PENDING_PUBLISH)));
        when(mediaReadinessGate.evaluate(article))
                .thenReturn(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);

        ArticlePublicationWorkflow.PublishDueResult result =
                workflowWithMediaGate().publishDueArticles();

        assertThat(result.success()).isZero();
        assertThat(result.failure()).isEqualTo(1);
        verify(weChatPublisher, never()).publishDraft(any());
    }

    @Test
    void should_block_manual_publish_when_article_delivery_failed() {
        Article article = article("tw-failed");
        when(archiveRepository.findByArticleId("tw-failed"))
                .thenReturn(Optional.of(snapshot(article, ArticleStatus.DELIVERY_FAILED)));

        assertThatThrownBy(() -> workflowWithMediaGate().publishNow("tw-failed"))
                .isInstanceOf(NonRetryableException.class);
        verify(weChatPublisher, never()).publishDraft(any());
    }

    @Test
    void shouldQueueForNextMorningWindow() {
        Article article = article("tw-123");

        workflow.queueForNextPublishWindow(article);

        verify(archiveRepository).saveSnapshot(article, ArticleStatus.PENDING_PUBLISH,
                LocalDateTime.of(2026, 9, 2, 8, 0), null);
        verify(articleStatusService).markPendingPublish("tw-123");
    }

    @Test
    void shouldPublishNowFromSnapshot() {
        Article article = article("tw-123");
        when(archiveRepository.findByArticleId("tw-123"))
                .thenReturn(Optional.of(snapshot(article, ArticleStatus.PENDING_PUBLISH)));
        when(weChatPublisher.publishDraft(article)).thenReturn("media-1");

        String mediaId = workflow.publishNow("tw-123");

        assertThat(mediaId).isEqualTo("media-1");
        verify(archiveRepository).markStatus("tw-123", ArticleStatus.PROCESSING);
        verify(archiveRepository).markDraftCreated("tw-123", "media-1",
                LocalDateTime.of(2026, 9, 1, 20, 0));
        verify(articleStatusService).markDraftCreated("tw-123");
    }

    @Test
    void shouldPublishDueArticlesAndKeepFailuresPending() {
        Article success = article("tw-ok");
        Article failed = article("tw-fail");
        when(archiveRepository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 20, 0)))
                .thenReturn(List.of(
                        snapshot(success, ArticleStatus.PENDING_PUBLISH),
                        snapshot(failed, ArticleStatus.PENDING_PUBLISH)));
        when(archiveRepository.findByArticleId("tw-ok")).thenReturn(Optional.empty());
        when(archiveRepository.findByArticleId("tw-fail")).thenReturn(Optional.empty());
        when(weChatPublisher.publishDraft(success)).thenReturn("media-ok");
        doThrow(new IllegalStateException("wechat down")).when(weChatPublisher).publishDraft(failed);

        ArticlePublicationWorkflow.PublishDueResult result = workflow.publishDueArticles();

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failure()).isEqualTo(1);
        verify(archiveRepository).markDraftCreated("tw-ok", "media-ok",
                LocalDateTime.of(2026, 9, 1, 20, 0));
        verify(archiveRepository).markStatus("tw-fail", ArticleStatus.PENDING_PUBLISH);
        verify(articleStatusService).markPendingPublish("tw-fail");
    }

    @Test
    void shouldRejectAlreadyDraftCreatedArticleOnManualPublish() {
        when(archiveRepository.findByArticleId("tw-123"))
                .thenReturn(Optional.of(draftCreatedSnapshot(article("tw-123"), "media-old")));

        assertThatThrownBy(() -> workflow.publishNow("tw-123"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("当前状态不允许发布");
        // 人工入口既有防护: DRAFT_CREATED 拒绝, 不触 addDraft(Story 10.6 保持不变)
        verify(weChatPublisher, never()).publishDraft(any());
    }

    // ============ Story 10.6: 草稿跨日/重复投递幂等(实时/批量/人工三入口) ============

    @Test
    void should_skip_draft_creation_when_realtime_snapshot_already_draft_created(CapturedOutput output) {
        Article article = article("tw-realtime-dup");
        when(archiveRepository.findByArticleId("tw-realtime-dup"))
                .thenReturn(Optional.of(draftCreatedSnapshot(article, "media-existing")));

        String mediaId = workflow.publishRealtime(article);

        assertThat(mediaId).isEqualTo("media-existing");
        // 幂等命中: 不重写快照、不进 PROCESSING、不触 addDraft
        verify(archiveRepository, never()).saveSnapshot(any(), any(), any(), any());
        verify(archiveRepository, never()).markStatus(anyString(), any());
        verify(weChatPublisher, never()).publishDraft(any());
        verify(articleStatusService, never()).markDraftCreated(anyString());
        // 审计字段: articleId + 既有 mediaId 均落日志(correlationId 由 MDC 携带)
        assertThat(output.getOut()).contains("跳过重复 addDraft");
        assertThat(output.getOut()).contains("tw-realtime-dup");
        assertThat(output.getOut()).contains("media-existing");
    }

    @Test
    void should_skip_draft_creation_when_due_article_snapshot_already_draft_created() {
        Article article = article("tw-batch-dup");
        when(archiveRepository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 20, 0)))
                .thenReturn(List.of(snapshot(article, ArticleStatus.PENDING_PUBLISH)));
        // 批量入口竞态形态: 扫描时 PENDING_PUBLISH, 处理前快照已推进为 DRAFT_CREATED(如并发人工发布)
        when(archiveRepository.findByArticleId("tw-batch-dup"))
                .thenReturn(Optional.of(draftCreatedSnapshot(article, "media-batch")));

        ArticlePublicationWorkflow.PublishDueResult result = workflow.publishDueArticles();

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failure()).isZero();
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository, never()).markStatus(anyString(), any());
    }

    @Test
    void should_create_draft_normally_when_snapshot_has_no_prior_draft() {
        Article article = article("tw-first-draft");
        when(archiveRepository.findByArticleId("tw-first-draft")).thenReturn(Optional.empty());
        when(weChatPublisher.publishDraft(article)).thenReturn("media-new");

        String mediaId = workflow.publishRealtime(article);

        assertThat(mediaId).isEqualTo("media-new");
        verify(archiveRepository).saveSnapshot(article, ArticleStatus.PENDING_PUBLISH,
                LocalDateTime.of(2026, 9, 1, 20, 0), null);
        verify(archiveRepository).markStatus("tw-first-draft", ArticleStatus.PROCESSING);
        verify(archiveRepository).markDraftCreated("tw-first-draft", "media-new",
                LocalDateTime.of(2026, 9, 1, 20, 0));
        verify(articleStatusService).markDraftCreated("tw-first-draft");
    }

    @Test
    void should_expose_media_id_when_wechat_succeeds_but_snapshot_write_fails() {
        Article article = article("tw-receipt");
        when(archiveRepository.findByArticleId("tw-receipt")).thenReturn(Optional.empty());
        when(weChatPublisher.publishDraft(article)).thenReturn("media-receipt");
        doThrow(new IllegalStateException("disk full")).when(archiveRepository)
                .markDraftCreated("tw-receipt", "media-receipt",
                        LocalDateTime.of(2026, 9, 1, 20, 0));

        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(ArticlePublicationWorkflow.DraftCreatedPersistenceException.class)
                .satisfies(error -> {
                    var typed = (ArticlePublicationWorkflow.DraftCreatedPersistenceException) error;
                    assertThat(typed.articleId()).isEqualTo("tw-receipt");
                    assertThat(typed.mediaId()).isEqualTo("media-receipt");
                });
        verify(weChatPublisher).publishDraft(article);
        verify(articleStatusService, never()).markDraftCreated("tw-receipt");
    }

    // fail-closed: 快照读取失败时异常原样传播, 绝不在幂等状态未知时冒险创建草稿
    // (仿 MarkdownArchiverTest.should_fail_closed_and_not_append_when_snapshot_read_fails)

    @Test
    void should_propagate_snapshot_read_failure_and_never_create_draft_on_realtime_publish() {
        Article article = article("tw-read-fail");
        when(archiveRepository.findByArticleId("tw-read-fail"))
                .thenThrow(new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "读取文章归档快照失败"));

        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("读取文章归档快照失败");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository, never()).saveSnapshot(any(), any(), any(), any());
    }

    @Test
    void should_count_snapshot_read_failure_as_batch_failure_and_never_create_draft() {
        Article article = article("tw-batch-read-fail");
        when(archiveRepository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 20, 0)))
                .thenReturn(List.of(snapshot(article, ArticleStatus.PENDING_PUBLISH)));
        when(archiveRepository.findByArticleId("tw-batch-read-fail"))
                .thenThrow(new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "读取文章归档快照失败"));

        ArticlePublicationWorkflow.PublishDueResult result = workflow.publishDueArticles();

        assertThat(result.success()).isZero();
        assertThat(result.failure()).isEqualTo(1);
        verify(weChatPublisher, never()).publishDraft(any());
    }

    @Test
    void should_record_idempotent_metric_on_draft_skip() {
        Article article = article("tw-metric-dup");
        when(archiveRepository.findByArticleId("tw-metric-dup"))
                .thenReturn(Optional.of(draftCreatedSnapshot(article, "media-metric")));

        workflowWithMetrics().publishRealtime(article);

        verify(taskMetrics).recordIdempotent(TaskMetrics.IdempotentKind.DRAFT);
    }

    @Test
    void should_keep_idempotent_skip_result_when_metric_recording_fails(CapturedOutput output) {
        Article article = article("tw-metric-fail");
        when(archiveRepository.findByArticleId("tw-metric-fail"))
                .thenReturn(Optional.of(draftCreatedSnapshot(article, "media-fail")));
        doThrow(new IllegalStateException("registry down")).when(taskMetrics).recordIdempotent(any());

        String mediaId = workflowWithMetrics().publishRealtime(article);

        // 指标旁路化: 观测故障只降级告警, 不改变"跳过创建、返回既有 mediaId"的幂等结果
        assertThat(mediaId).isEqualTo("media-fail");
        verify(weChatPublisher, never()).publishDraft(any());
        assertThat(output.getOut()).contains("幂等跳过指标记录失败");
    }

    private static ArchivedArticle snapshot(Article article, ArticleStatus status) {
        return ArchivedArticle.builder()
                .articleId(article.getId())
                .article(article)
                .status(status)
                .scheduledPublishAt(LocalDateTime.of(2026, 9, 1, 8, 0))
                .createdAt(article.getCreatedAt())
                .build();
    }

    private static ArchivedArticle draftCreatedSnapshot(Article article, String mediaId) {
        return ArchivedArticle.builder()
                .articleId(article.getId())
                .article(article)
                .status(ArticleStatus.DRAFT_CREATED)
                .scheduledPublishAt(LocalDateTime.of(2026, 9, 1, 8, 0))
                .createdAt(article.getCreatedAt())
                .wechatDraftMediaId(mediaId)
                .draftCreatedAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .build();
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
}
