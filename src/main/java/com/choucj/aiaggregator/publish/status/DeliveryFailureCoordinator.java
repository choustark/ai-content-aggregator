package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 按固定顺序收敛文章交付失败，让部分写失败可通过同一命令幂等补齐。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryFailureCoordinator {

    private final ArticleArchiveRepository archiveRepository;
    private final ArticleStatusService articleStatusService;
    private final TaskQueue taskQueue;

    /** 首次失败先保留完整 Article，再按同一幂等命令收敛其余三层。 */
    public ConvergenceResult converge(Article article, String stage, String code, String safeSummary) {
        String articleId = article.getId();
        String taskId = "delivery:" + articleId;
        try {
            archiveRepository.saveSnapshot(article, ArticleStatus.MEDIA_PROCESSING, null, null);
        } catch (RuntimeException e) {
            logFailure(articleId, Layer.ARTICLE_SNAPSHOT, e);
            return new ConvergenceResult(false, Layer.ARTICLE_SNAPSHOT);
        }
        return converge(articleId, taskId, stage, code, safeSummary);
    }

    /** 在 sidecar 已终态化后依次写快照、Redis 镜像和 dead-letter。 */
    public ConvergenceResult converge(String articleId, String taskId, String stage,
                                      String code, String safeSummary) {
        try {
            archiveRepository.markDeliveryFailed(articleId, stage, code, safeSummary,
                    taskId, LocalDateTime.now());
        } catch (RuntimeException e) {
            logFailure(articleId, Layer.ARTICLE_SNAPSHOT, e);
            return new ConvergenceResult(false, Layer.ARTICLE_SNAPSHOT);
        }
        try {
            articleStatusService.markDeliveryFailed(articleId);
        } catch (RuntimeException e) {
            logFailure(articleId, Layer.REDIS_MIRROR, e);
            return new ConvergenceResult(false, Layer.REDIS_MIRROR);
        }
        try {
            if (!taskQueue.recordDeliveryFailure(taskId, articleId, code, safeSummary)) {
                return new ConvergenceResult(false, Layer.TASK_QUEUE);
            }
        } catch (RuntimeException e) {
            logFailure(articleId, Layer.TASK_QUEUE, e);
            return new ConvergenceResult(false, Layer.TASK_QUEUE);
        }
        return new ConvergenceResult(true, Layer.COMPLETE);
    }

    private void logFailure(String articleId, Layer layer, RuntimeException failure) {
        log.warn("文章交付失败状态收敛中断: articleId={}, layer={}, exceptionType={}",
                articleId, layer, failure.getClass().getSimpleName());
    }

    /** 指出尚未收敛的层，供运维安全重放而非制造可发布假象。 */
    public record ConvergenceResult(boolean converged, Layer incompleteLayer) {
    }

    public enum Layer {
        ARTICLE_SNAPSHOT,
        REDIS_MIRROR,
        TASK_QUEUE,
        COMPLETE
    }
}
