package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.wechat.ArticleMediaReadinessGate;
import com.choucj.aiaggregator.publish.wechat.ArticlePublicationWorkflow;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 消费文章级 replay 任务：媒体已由人工修复时复用快照 Article 发布，绝不调用 LLM。 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class ArticleDeliveryReplayExecutor {
    private final ArticleArchiveRepository archiveRepository;
    private final ArticleMediaReadinessGate mediaReadinessGate;
    private final ArticlePublicationWorkflow publicationWorkflow;
    private final TaskQueue taskQueue;
    private final ArticleStatusService articleStatusService;

    public void execute(String taskId) {
        TaskQueue.ReplayMetadata metadata = taskQueue.getReplayMetadata(taskId);
        String articleId = metadata.articleId();
        ArchivedArticle snapshot = archiveRepository.findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "补跑文章快照不存在: articleId=" + articleId));
        if (snapshot.getArticle() == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "补跑文章快照缺少 Article: articleId=" + articleId);
        }
        if (!metadata.replayedFrom().equals(snapshot.getFailureTaskId())) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "补跑来源与失败快照不一致: articleId=" + articleId);
        }
        if (snapshot.getStatus() == ArticleStatus.DRAFT_CREATED
                && snapshot.getWechatDraftMediaId() != null
                && !snapshot.getWechatDraftMediaId().isBlank()) {
            reconcileRedisDraftStatus(articleId);
            return;
        }
        if (metadata.draftMediaId() != null) {
            reconcileDraftReceipt(articleId, metadata.draftMediaId());
            return;
        }
        if (snapshot.getStatus() != ArticleStatus.DELIVERY_FAILED) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "补跑文章不再处于 DELIVERY_FAILED: articleId=" + articleId);
        }
        ArticleMediaReadinessGate.GateResult gate = mediaReadinessGate.evaluate(snapshot.getArticle());
        if (gate != ArticleMediaReadinessGate.GateResult.READY) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "人工修复后的媒体仍未就绪: articleId=" + articleId + ", reason=" + gate);
        }
        archiveRepository.reopenDeliveryFailedForReplay(articleId);
        try {
            publicationWorkflow.publishRealtime(snapshot.getArticle());
        } catch (ArticlePublicationWorkflow.DraftCreatedPersistenceException e) {
            handleDraftCreatedPersistenceFailure(taskId, articleId, e);
        } catch (RuntimeException e) {
            restoreFailureBestEffort(articleId, snapshot, e);
            throw e;
        }
    }

    private void handleDraftCreatedPersistenceFailure(
            String taskId, String articleId,
            ArticlePublicationWorkflow.DraftCreatedPersistenceException failure) {
        try {
            archiveRepository.reconcileDraftCreatedFromReplay(
                    articleId, failure.mediaId(), LocalDateTime.now());
        } catch (RuntimeException localReceiptFailure) {
            try {
                taskQueue.recordReplayDraftReceipt(taskId, failure.mediaId());
            } catch (RuntimeException redisReceiptFailure) {
                failure.addSuppressed(localReceiptFailure);
                failure.addSuppressed(redisReceiptFailure);
                throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "微信草稿已创建但本地与 Redis 成功收据均无法持久化，已停止自动补跑: articleId="
                                + articleId,
                        failure);
            }
            throw new RetryableException(
                    "微信草稿成功收据已保存，本地快照对账失败: articleId=" + articleId,
                    localReceiptFailure);
        }
        try {
            taskQueue.recordReplayDraftReceipt(taskId, failure.mediaId());
        } catch (RuntimeException receiptFailure) {
            log.warn("本地草稿成功收据已保存，Redis replay receipt 暂不可用: articleId={}",
                    articleId, receiptFailure);
        }
        reconcileRedisDraftStatus(articleId);
    }

    private void reconcileDraftReceipt(String articleId, String mediaId) {
        archiveRepository.reconcileDraftCreatedFromReplay(articleId, mediaId, LocalDateTime.now());
        reconcileRedisDraftStatus(articleId);
    }

    private void reconcileRedisDraftStatus(String articleId) {
        articleStatusService.markDraftCreated(articleId);
        ArticleStatus actual = articleStatusService.getStatus(articleId).orElse(null);
        if (actual != ArticleStatus.DRAFT_CREATED) {
            throw new RetryableException("草稿状态 Redis 镜像对账未生效: articleId=" + articleId);
        }
    }

    private void restoreFailureBestEffort(String articleId, ArchivedArticle snapshot,
                                          RuntimeException original) {
        try {
            archiveRepository.markDeliveryFailed(articleId, snapshot.getFailureStage(),
                    snapshot.getFailureCode(), snapshot.getFailureSummary(),
                    snapshot.getFailureTaskId(), snapshot.getFailedAt());
        } catch (RuntimeException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
        try {
            articleStatusService.markDeliveryFailed(articleId);
        } catch (RuntimeException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
