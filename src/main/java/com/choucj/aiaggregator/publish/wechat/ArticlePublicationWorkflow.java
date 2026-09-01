package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布工作流: 管理本地发布池、定时发布与人工发布的共同状态流转.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class ArticlePublicationWorkflow {

    private final WeChatPublisher weChatPublisher;
    private final ArticleArchiveRepository archiveRepository;
    private final ArticleStatusService articleStatusService;
    private final PublishingProperties publishingProperties;
    private final Clock clock;

    @Autowired
    public ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                                      ArticleArchiveRepository archiveRepository,
                                      ArticleStatusService articleStatusService,
                                      PublishingProperties publishingProperties) {
        this(weChatPublisher, archiveRepository, articleStatusService, publishingProperties, Clock.systemDefaultZone());
    }

    ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                               ArticleArchiveRepository archiveRepository,
                               ArticleStatusService articleStatusService,
                               PublishingProperties publishingProperties,
                               Clock clock) {
        this.weChatPublisher = weChatPublisher;
        this.archiveRepository = archiveRepository;
        this.articleStatusService = articleStatusService;
        this.publishingProperties = publishingProperties;
        this.clock = clock;
    }

    public void queueForNextPublishWindow(Article article) {
        LocalDateTime scheduledAt = nextPublishWindow();
        archiveRepository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH, scheduledAt, null);
        articleStatusService.markPendingPublish(article.getId());
        log.info("文章进入待发布池: articleId={}, scheduledPublishAt={}", article.getId(), scheduledAt);
    }

    public String publishRealtime(Article article) {
        archiveRepository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH, LocalDateTime.now(clock), null);
        return publishArticle(article);
    }

    public String publishNow(String articleId) {
        ArchivedArticle snapshot = archiveRepository.findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
        if (!canPublish(snapshot.getStatus())) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "当前状态不允许发布: articleId=" + articleId + ", status=" + snapshot.getStatus());
        }
        Article article = snapshot.getArticle();
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "文章归档快照缺少 Article: articleId=" + articleId);
        }
        return publishArticle(article);
    }

    public PublishDueResult publishDueArticles() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ArchivedArticle> due = archiveRepository.findDueForPublish(now);
        long success = 0;
        long failure = 0;
        for (ArchivedArticle snapshot : due) {
            try {
                publishArticle(snapshot.getArticle());
                success++;
            } catch (Exception e) {
                failure++;
                try {
                    archiveRepository.markStatus(snapshot.getArticleId(), ArticleStatus.PENDING_PUBLISH);
                    articleStatusService.markPendingPublish(snapshot.getArticleId());
                } catch (Exception statusError) {
                    log.warn("发布失败后状态恢复失败: articleId={}", snapshot.getArticleId(), statusError);
                }
                log.error("待发布文章创建草稿失败, 已保留待发布状态: articleId={}",
                        snapshot.getArticleId(), e);
            }
        }
        return new PublishDueResult(due.size(), success, failure);
    }

    private String publishArticle(Article article) {
        if (article == null || article.getId() == null || article.getId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "待发布 Article 非法");
        }
        archiveRepository.markStatus(article.getId(), ArticleStatus.PROCESSING);
        articleStatusService.markProcessing(article.getId());
        String mediaId = weChatPublisher.publishDraft(article);
        archiveRepository.markDraftCreated(article.getId(), mediaId, LocalDateTime.now(clock));
        articleStatusService.markDraftCreated(article.getId());
        return mediaId;
    }

    private LocalDateTime nextPublishWindow() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime todayWindow = now.toLocalDate().atTime(publishingProperties.getDailyPublishHour(), 0);
        if (now.isBefore(todayWindow)) {
            return todayWindow;
        }
        return todayWindow.plusDays(1);
    }

    private static boolean canPublish(ArticleStatus status) {
        return status == ArticleStatus.CREATED || status == ArticleStatus.PENDING_PUBLISH;
    }

    public record PublishDueResult(long total, long success, long failure) {
    }
}
