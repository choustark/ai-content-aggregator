package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private DraftPublishClaimService draftClaimService;

    private PublishingProperties publishingProperties;
    private ArticlePublicationWorkflow workflow;
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        // Story 10.16: 正常路径默认认领成功; 个别用例可覆盖(门禁/幂等守卫先于认领, 用 lenient)
        lenient().when(draftClaimService.acquire(anyString()))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
        workflow = new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.empty(),
                Optional.empty(), draftClaimService, clock);
    }

    /** 注入指标 mock 的工作流(Story 10.6 幂等指标用例). */
    private ArticlePublicationWorkflow workflowWithMetrics() {
        return new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.of(taskMetrics),
                Optional.empty(), draftClaimService, clock);
    }

    private ArticlePublicationWorkflow workflowWithMediaGate() {
        return new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, Optional.empty(),
                Optional.of(mediaReadinessGate), draftClaimService, clock);
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
        // Story 10.16: 三处收据落位后租约释放
        verify(draftClaimService).release("tw-123");
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
        // 审计字段: articleId 落日志; Story 10.16 起既有 mediaId 脱敏只留前缀(correlationId 由 MDC 携带)
        assertThat(output.getOut()).contains("跳过重复 addDraft");
        assertThat(output.getOut()).contains("tw-realtime-dup");
        assertThat(output.getOut()).contains("media-ex***");
        assertThat(output.getOut()).doesNotContain("media-existing");
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
        // Story 10.16: 统一持久化对账状态 + 留存 mediaId(实时/批量不再重投为再次远端调用)
        verify(draftClaimService).recordReconcileReceipt("tw-receipt", "media-receipt");
        verify(archiveRepository).markReconciling("tw-receipt", "media-receipt");
        verify(articleStatusService).markReconciling("tw-receipt");
    }

    // ============ Story 10.16: 跨实例原子认领与耐久收据 ============

    @Test
    void should_never_call_remote_when_claim_held_by_another_instance() {
        Article article = article("tw-claim-held");
        when(archiveRepository.findByArticleId("tw-claim-held")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-claim-held"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_HELD);

        // 并发双实例: 失败方按幂等跳过处理(可重试稍后凭收据跳过), 不触达远端, 不抛错误风暴
        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("租约被其他实例持有");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository, never()).markStatus(anyString(), any());
    }

    @Test
    void should_record_skip_held_metric_when_claim_held_by_another_instance() {
        Article article = article("tw-claim-held-metric");
        when(archiveRepository.findByArticleId("tw-claim-held-metric")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-claim-held-metric"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_HELD);

        assertThatThrownBy(() -> workflowWithMetrics().publishRealtime(article))
                .isInstanceOf(RetryableException.class);

        verify(taskMetrics).recordDraftClaim(TaskMetrics.DraftClaimOutcome.SKIP_HELD);
    }

    @Test
    void should_reconcile_locally_without_remote_when_claim_hits_existing_receipt() {
        Article article = article("tw-claim-receipt");
        when(archiveRepository.findByArticleId("tw-claim-receipt")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-claim-receipt"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-claim-receipt"))
                .thenReturn(Optional.of(new DraftPublishClaimService.ReconcileReceipt(
                        "tw-claim-receipt", "media-reconcile", "2026-10-23T00:00:00Z")));

        String mediaId = workflow.publishRealtime(article);

        // 收据命中 → 幂等跳过远端, 只补本地写回并清除收据
        assertThat(mediaId).isEqualTo("media-reconcile");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository).reconcileDraftCreatedFromReplay(eq("tw-claim-receipt"),
                eq("media-reconcile"), any());
        verify(articleStatusService).markDraftCreated("tw-claim-receipt");
        verify(draftClaimService).clearReconcileReceipt("tw-claim-receipt");
    }

    @Test
    void should_record_idempotent_metric_when_claim_hits_existing_receipt(CapturedOutput output) {
        Article article = article("tw-claim-receipt-metric");
        when(archiveRepository.findByArticleId("tw-claim-receipt-metric")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-claim-receipt-metric"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-claim-receipt-metric"))
                .thenReturn(Optional.of(new DraftPublishClaimService.ReconcileReceipt(
                        "tw-claim-receipt-metric", "media-reconcile-2", "2026-10-23T00:00:00Z")));

        workflowWithMetrics().publishRealtime(article);

        // 与 10.6 幂等口径一致: 收据命中跳过远端即幂等命中
        verify(taskMetrics).recordIdempotent(TaskMetrics.IdempotentKind.DRAFT);
        verify(taskMetrics).recordDraftClaim(TaskMetrics.DraftClaimOutcome.SKIP_EXISTING);
        // 脱敏: 新增对账日志只输出 mediaId 前缀
        assertThat(output.getOut()).contains("media-re***");
        assertThat(output.getOut()).doesNotContain("media-reconcile-2");
    }

    @Test
    void should_reconcile_locally_when_claim_receipt_read_hits_concurrent_clear_race() {
        Article article = article("tw-claim-receipt-race");
        when(archiveRepository.findByArticleId("tw-claim-receipt-race")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-claim-receipt-race"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-claim-receipt-race"))
                .thenReturn(Optional.empty());

        // 收据在认领判定与读取之间被并发对账清除: 可重试, 不触达远端;
        // 收据曾存在即幂等命中(记 skip-existing), 不得误记 skip-held
        assertThatThrownBy(() -> workflowWithMetrics().publishRealtime(article))
                .isInstanceOf(RetryableException.class);
        verify(weChatPublisher, never()).publishDraft(any());
        verify(taskMetrics).recordDraftClaim(TaskMetrics.DraftClaimOutcome.SKIP_EXISTING);
        verify(taskMetrics, never()).recordDraftClaim(TaskMetrics.DraftClaimOutcome.SKIP_HELD);
    }

    @Test
    void should_park_reconciling_when_crash_residue_without_any_receipt() {
        Article article = article("tw-crash-residue");
        ArchivedArticle residue = snapshot(article, ArticleStatus.PROCESSING);
        when(archiveRepository.findByArticleId("tw-crash-residue"))
                .thenReturn(Optional.of(residue));
        // 上一实例崩溃残留: 租约已过期, 本方认领成功但快照仍在远端调用窗口中间态

        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("RECONCILING");
        // 绝不盲发远端; 释放本方用不上的租约并持久化停候状态
        verify(weChatPublisher, never()).publishDraft(any());
        verify(draftClaimService).release("tw-crash-residue");
        verify(archiveRepository).markReconciling("tw-crash-residue", null);
        verify(articleStatusService).markReconciling("tw-crash-residue");
    }

    @Test
    void should_record_parked_metric_on_crash_residue_without_receipt() {
        Article article = article("tw-crash-residue-metric");
        when(archiveRepository.findByArticleId("tw-crash-residue-metric"))
                .thenReturn(Optional.of(snapshot(article, ArticleStatus.PROCESSING)));

        assertThatThrownBy(() -> workflowWithMetrics().publishRealtime(article))
                .isInstanceOf(RetryableException.class);

        verify(taskMetrics).recordDraftClaim(TaskMetrics.DraftClaimOutcome.RECONCILE_PARKED);
    }

    @Test
    void should_reconcile_from_snapshot_media_id_on_crash_residue() {
        Article article = article("tw-crash-with-media");
        ArchivedArticle residue = snapshot(article, ArticleStatus.PROCESSING);
        residue.setReconcileMediaId("media-crash");
        when(archiveRepository.findByArticleId("tw-crash-with-media"))
                .thenReturn(Optional.of(residue));

        String mediaId = workflow.publishRealtime(article);

        // 崩溃前已留存 mediaId → 收据命中, 只补本地写回; 本方租约先行释放(与停候分支一致)
        assertThat(mediaId).isEqualTo("media-crash");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository).reconcileDraftCreatedFromReplay(eq("tw-crash-with-media"),
                eq("media-crash"), any());
        verify(draftClaimService).release("tw-crash-with-media");
    }

    @Test
    void should_reconcile_when_manual_publish_touches_reconciling_article() {
        // 裁决 1A: RECONCILING 纳入 canPublish 白名单 — 人工触碰触发自动对账(裁决 2A)
        Article article = article("tw-manual-reconcile");
        ArchivedArticle reconciling = snapshot(article, ArticleStatus.RECONCILING);
        reconciling.setReconcileMediaId("media-manual");
        when(archiveRepository.findByArticleId("tw-manual-reconcile"))
                .thenReturn(Optional.of(reconciling));
        when(draftClaimService.acquire("tw-manual-reconcile"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-manual-reconcile"))
                .thenReturn(Optional.of(new DraftPublishClaimService.ReconcileReceipt(
                        "tw-manual-reconcile", "media-manual", "2026-10-23T00:00:00Z")));

        String mediaId = workflow.publishNow("tw-manual-reconcile");

        assertThat(mediaId).isEqualTo("media-manual");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository).reconcileDraftCreatedFromReplay(eq("tw-manual-reconcile"),
                eq("media-manual"), any());
    }

    @Test
    void should_release_claim_when_wechat_addDraft_fails() {
        Article article = article("tw-remote-fail");
        when(archiveRepository.findByArticleId("tw-remote-fail")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("wechat 500")).when(weChatPublisher).publishDraft(article);

        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(IllegalStateException.class);
        // 远端调用未确认成功: 租约必须释放, 不得占坑阻塞后续重试
        verify(draftClaimService).release("tw-remote-fail");
    }

    @Test
    void should_record_acquired_metric_when_claim_succeeds_before_remote_publish() {
        Article article = article("tw-claim-acquired-metric");
        when(archiveRepository.findByArticleId("tw-claim-acquired-metric")).thenReturn(Optional.empty());
        when(weChatPublisher.publishDraft(article)).thenReturn("media-acquired");

        String mediaId = workflowWithMetrics().publishRealtime(article);

        assertThat(mediaId).isEqualTo("media-acquired");
        // ACQUIRED 分支同样记认领指标(outcome=acquired), 与 TaskMetrics 契约一致
        verify(taskMetrics).recordDraftClaim(TaskMetrics.DraftClaimOutcome.ACQUIRED);
    }

    @Test
    void should_skip_remote_idempotently_when_another_instance_finished_between_guard_and_acquire() {
        Article article = article("tw-claim-race-win");
        // 守卫读取时快照仍为 PENDING_PUBLISH(陈旧); 认领前另一实例已完成发布并释放租约
        AtomicReference<ArchivedArticle> latest =
                new AtomicReference<>(snapshot(article, ArticleStatus.PENDING_PUBLISH));
        when(archiveRepository.findByArticleId("tw-claim-race-win"))
                .thenAnswer(inv -> Optional.ofNullable(latest.get()));
        when(draftClaimService.acquire("tw-claim-race-win")).thenAnswer(inv -> {
            latest.set(draftCreatedSnapshot(article, "media-raced"));
            return DraftPublishClaimService.ClaimOutcome.ACQUIRED;
        });

        String mediaId = workflow.publishRealtime(article);

        // 认领后重读最新快照命中 DRAFT_CREATED → 幂等返回既有收据, 绝不重发 addDraft
        assertThat(mediaId).isEqualTo("media-raced");
        verify(weChatPublisher, never()).publishDraft(any());
        // 不得把 DRAFT_CREATED 改写为 PROCESSING, 也不得降级为 RECONCILING
        verify(archiveRepository, never()).markStatus(anyString(), any());
        verify(archiveRepository, never()).markReconciling(anyString(), any());
        verify(archiveRepository, never()).markDraftCreated(anyString(), anyString(), any());
        // 本方租约释放, 交还认领权
        verify(draftClaimService).release("tw-claim-race-win");
    }

    @Test
    void should_not_restore_pending_publish_when_parked_reconciling_article_fails_in_due_loop() {
        Article article = article("tw-parked-reconcile");
        ArchivedArticle parked = snapshot(article, ArticleStatus.RECONCILING);
        parked.setReconcileMediaId("media-parked");
        when(archiveRepository.findDueForPublish(LocalDateTime.of(2026, 9, 1, 20, 0)))
                .thenReturn(List.of(parked));
        when(archiveRepository.findByArticleId("tw-parked-reconcile"))
                .thenReturn(Optional.of(parked));
        when(draftClaimService.acquire("tw-parked-reconcile"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-parked-reconcile"))
                .thenReturn(Optional.of(new DraftPublishClaimService.ReconcileReceipt(
                        "tw-parked-reconcile", "media-parked", "2026-10-24T00:00:00Z")));
        doThrow(new IllegalStateException("disk full")).when(archiveRepository)
                .reconcileDraftCreatedFromReplay(eq("tw-parked-reconcile"), eq("media-parked"), any());

        ArticlePublicationWorkflow.PublishDueResult result = workflow.publishDueArticles();

        assertThat(result.success()).isZero();
        assertThat(result.failure()).isEqualTo(1);
        // 有意停候的 RECONCILING 只能经本地对账离开: 到期循环失败不做 PENDING_PUBLISH 状态恢复
        verify(weChatPublisher, never()).publishDraft(any());
        verify(archiveRepository, never()).markStatus(eq("tw-parked-reconcile"), any());
        verify(articleStatusService, never()).markPendingPublish(anyString());
    }

    @Test
    void should_propagate_failure_when_local_reconciliation_write_fails(CapturedOutput output) {
        Article article = article("tw-reconcile-fail");
        when(archiveRepository.findByArticleId("tw-reconcile-fail")).thenReturn(Optional.empty());
        when(draftClaimService.acquire("tw-reconcile-fail"))
                .thenReturn(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);
        when(draftClaimService.readReconcileReceipt("tw-reconcile-fail"))
                .thenReturn(Optional.of(new DraftPublishClaimService.ReconcileReceipt(
                        "tw-reconcile-fail", "media-stuck", "2026-10-23T00:00:00Z")));
        doThrow(new IllegalStateException("disk full again")).when(archiveRepository)
                .reconcileDraftCreatedFromReplay(eq("tw-reconcile-fail"), eq("media-stuck"), any());

        // 对账补全失败: 停留 RECONCILING 并告警, 绝不重发远端
        assertThatThrownBy(() -> workflow.publishRealtime(article))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("停留 RECONCILING");
        verify(weChatPublisher, never()).publishDraft(any());
        verify(articleStatusService, never()).markDraftCreated("tw-reconcile-fail");
        assertThat(output.getOut()).contains("禁止远端调用");
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
