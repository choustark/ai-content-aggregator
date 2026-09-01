package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticlePublicationWorkflowTest {

    @Mock
    private WeChatPublisher weChatPublisher;
    @Mock
    private ArticleArchiveRepository archiveRepository;
    @Mock
    private ArticleStatusService articleStatusService;

    private PublishingProperties publishingProperties;
    private ArticlePublicationWorkflow workflow;
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        workflow = new ArticlePublicationWorkflow(weChatPublisher, archiveRepository,
                articleStatusService, publishingProperties, clock);
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
                .thenReturn(Optional.of(snapshot(article("tw-123"), ArticleStatus.DRAFT_CREATED)));

        assertThatThrownBy(() -> workflow.publishNow("tw-123"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("当前状态不允许发布");
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
