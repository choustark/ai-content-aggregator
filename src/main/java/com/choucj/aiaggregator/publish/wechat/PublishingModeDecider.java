package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 微信发布模式决策器: 高分实时创建草稿, 低分进入下一次计划发布窗口.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class PublishingModeDecider implements ContentPublisher {

    private final ArticlePublicationWorkflow publicationWorkflow;
    private final PublishingProperties publishingProperties;

    @Override
    public void publish(Article article) {
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article 为 null");
        }
        String articleId = requireArticleId(article);
        int score = article.getInnovationScore();
        int threshold = publishingProperties.getRealtimeThreshold();

        if (score >= threshold || !publishingProperties.isBatchEnabled()) {
            log.info("微信发布决策: articleId={}, innovationScore={}, threshold={}, decision=realtime, reason={}",
                    articleId, score, threshold, score >= threshold ? "scoreAboveThreshold" : "batchDisabled");
            publicationWorkflow.publishRealtime(article);
            return;
        }

        publicationWorkflow.queueForNextPublishWindow(article);
        log.info("微信发布决策: articleId={}, innovationScore={}, threshold={}, decision=batch",
                articleId, score, threshold);
    }

    private static String requireArticleId(Article article) {
        String articleId = article.getId();
        if (articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article.id 不能为空");
        }
        return articleId;
    }
}
