package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PublishingModeDeciderTest {

    @Mock
    private ArticlePublicationWorkflow publicationWorkflow;

    private PublishingProperties publishingProperties;
    private PublishingModeDecider decider;

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        decider = new PublishingModeDecider(publicationWorkflow, publishingProperties);
    }

    @Test
    void shouldPublishImmediatelyWhenScoreAboveThreshold(CapturedOutput output) {
        Article article = article("art-1", 9);

        decider.publish(article);

        verify(publicationWorkflow).publishRealtime(article);
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
        assertThat(output.getOut()).contains("decision=realtime");
        assertThat(output.getOut()).contains("articleId=art-1");
        assertThat(output.getOut()).contains("innovationScore=9");
    }

    @Test
    void shouldPublishImmediatelyWhenScoreEqualsThreshold(CapturedOutput output) {
        Article article = article("art-eq", 8);

        decider.publish(article);

        verify(publicationWorkflow).publishRealtime(article);
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
        assertThat(output.getOut()).contains("decision=realtime");
    }

    @Test
    void shouldQueueForNextPublishWindowWhenScoreBelowThreshold(CapturedOutput output) {
        Article article = article("art-2", 5);

        decider.publish(article);

        verify(publicationWorkflow, never()).publishRealtime(any());
        verify(publicationWorkflow).queueForNextPublishWindow(article);
        assertThat(output.getOut()).contains("decision=batch");
    }

    @Test
    void shouldRespectCustomThresholdFromConfig(CapturedOutput output) {
        publishingProperties.setRealtimeThreshold(10);
        Article article = article("art-5", 8);

        decider.publish(article);

        verify(publicationWorkflow).queueForNextPublishWindow(article);
        verify(publicationWorkflow, never()).publishRealtime(any());
        assertThat(output.getOut()).contains("decision=batch");
        assertThat(output.getOut()).contains("threshold=10");
    }

    @Test
    void shouldPublishRealtimeWhenBatchDisabled(CapturedOutput output) {
        publishingProperties.setBatchEnabled(false);
        Article article = article("art-batch-off", 5);

        decider.publish(article);

        verify(publicationWorkflow).publishRealtime(article);
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
        assertThat(output.getOut()).contains("decision=realtime");
        assertThat(output.getOut()).contains("reason=batchDisabled");
    }

    @Test
    void shouldThrowOnNullArticle() {
        assertThatThrownBy(() -> decider.publish(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article 为 null");
        verify(publicationWorkflow, never()).publishRealtime(any());
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
    }

    @Test
    void shouldThrowOnNullArticleId() {
        Article article = article(null, 5);

        assertThatThrownBy(() -> decider.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 不能为空");
        verify(publicationWorkflow, never()).publishRealtime(any());
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
    }

    @Test
    void shouldThrowOnBlankArticleId() {
        Article article = article(" ", 5);

        assertThatThrownBy(() -> decider.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 不能为空");
        verify(publicationWorkflow, never()).publishRealtime(any());
        verify(publicationWorkflow, never()).queueForNextPublishWindow(any());
    }

    private static Article article(String id, int score) {
        return Article.builder()
                .id(id)
                .title("标题 " + id)
                .content("正文 " + id)
                .innovationScore(score)
                .createdAt(LocalDate.of(2026, 7, 6).atStartOfDay())
                .build();
    }
}
