package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleDeliveryReplayServiceTest {

    @Mock private ArticleArchiveRepository archiveRepository;
    @Mock private TaskQueue taskQueue;
    private ArticleDeliveryReplayService service;

    @BeforeEach
    void setUp() {
        service = new ArticleDeliveryReplayService(archiveRepository, taskQueue);
    }

    @Test
    void should_replay_linked_dead_letter_without_regenerating_article() {
        ArchivedArticle snapshot = ArchivedArticle.builder().articleId("tw-1")
                .article(Article.builder().id("tw-1").content("preserved").build())
                .status(ArticleStatus.DELIVERY_FAILED).failureTaskId("delivery:tw-1").build();
        when(archiveRepository.findByArticleId("tw-1")).thenReturn(Optional.of(snapshot));
        TaskQueue.ReplayResult expected = new TaskQueue.ReplayResult(
                TaskQueue.ReplayOutcome.REPLAYED, "delivery:tw-1:replay:req-1");
        when(taskQueue.replayDeadLetter("delivery:tw-1", "req-1")).thenReturn(expected);

        assertThat(service.replay("tw-1", "req-1")).isEqualTo(expected);
        verify(taskQueue).replayDeadLetter("delivery:tw-1", "req-1");
    }

    @Test
    void should_reject_replay_when_snapshot_is_not_delivery_failed() {
        when(archiveRepository.findByArticleId("tw-1")).thenReturn(Optional.of(
                ArchivedArticle.builder().articleId("tw-1").status(ArticleStatus.CREATED)
                        .article(Article.builder().id("tw-1").build()).build()));

        assertThatThrownBy(() -> service.replay("tw-1", "req-1"))
                .isInstanceOf(NonRetryableException.class);
    }
}
