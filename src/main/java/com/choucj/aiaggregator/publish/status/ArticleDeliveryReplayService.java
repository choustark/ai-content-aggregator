package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 复用已归档 Article 触发显式补跑，避免默认再次调用 LLM。 */
@Service
@RequiredArgsConstructor
public class ArticleDeliveryReplayService {
    private final ArticleArchiveRepository archiveRepository;
    private final TaskQueue taskQueue;

    /** 仅允许 DELIVERY_FAILED 且失败任务可关联的文章补跑。 */
    public TaskQueue.ReplayResult replay(String articleId, String requestId) {
        ArchivedArticle snapshot = archiveRepository.findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
        if (snapshot.getStatus() != ArticleStatus.DELIVERY_FAILED
                || snapshot.getArticle() == null
                || snapshot.getFailureTaskId() == null || snapshot.getFailureTaskId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "文章不满足交付补跑条件: articleId=" + articleId);
        }
        return taskQueue.replayDeadLetter(snapshot.getFailureTaskId(), requestId);
    }
}
