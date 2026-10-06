package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class DeliveryFailureCoordinatorTest {

    @Mock private ArticleArchiveRepository archiveRepository;
    @Mock private ArticleStatusService articleStatusService;
    @Mock private TaskQueue taskQueue;
    private DeliveryFailureCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new DeliveryFailureCoordinator(archiveRepository, articleStatusService, taskQueue);
    }

    @Test
    void should_converge_snapshot_redis_and_dead_letter_in_order() {
        when(taskQueue.recordDeliveryFailure("delivery:tw-1", "tw-1", "MEDIA_DOWNLOAD", "safe"))
                .thenReturn(true);

        DeliveryFailureCoordinator.ConvergenceResult result = coordinator.converge(
                "tw-1", "delivery:tw-1", "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD", "safe");

        assertThat(result.converged()).isTrue();
        var ordered = inOrder(archiveRepository, articleStatusService, taskQueue);
        ordered.verify(archiveRepository).markDeliveryFailed(eq("tw-1"), eq("MEDIA_DOWNLOAD"),
                eq("MEDIA_DOWNLOAD"), eq("safe"), eq("delivery:tw-1"), any());
        ordered.verify(articleStatusService).markDeliveryFailed("tw-1");
        ordered.verify(taskQueue).recordDeliveryFailure(
                "delivery:tw-1", "tw-1", "MEDIA_DOWNLOAD", "safe");
    }

    @Test
    void should_persist_generated_article_before_marking_first_delivery_failure() {
        Article article = Article.builder().id("tw-1").title("保留正文").build();
        when(taskQueue.recordDeliveryFailure("delivery:tw-1", "tw-1", "MEDIA_DOWNLOAD", "safe"))
                .thenReturn(true);

        DeliveryFailureCoordinator.ConvergenceResult result = coordinator.converge(
                article, "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD", "safe");

        assertThat(result.converged()).isTrue();
        var ordered = inOrder(archiveRepository, articleStatusService, taskQueue);
        ordered.verify(archiveRepository).saveSnapshot(article, ArticleStatus.MEDIA_PROCESSING, null, null);
        ordered.verify(archiveRepository).markDeliveryFailed(eq("tw-1"), eq("MEDIA_DOWNLOAD"),
                eq("MEDIA_DOWNLOAD"), eq("safe"), eq("delivery:tw-1"), any());
        ordered.verify(articleStatusService).markDeliveryFailed("tw-1");
        ordered.verify(taskQueue).recordDeliveryFailure(
                "delivery:tw-1", "tw-1", "MEDIA_DOWNLOAD", "safe");
    }

    @Test
    void should_fail_closed_and_stop_when_redis_mirror_fails() {
        doThrow(new IllegalStateException("redis down"))
                .when(articleStatusService).markDeliveryFailed("tw-1");

        DeliveryFailureCoordinator.ConvergenceResult result = coordinator.converge(
                "tw-1", "delivery:tw-1", "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD", "safe");

        assertThat(result.converged()).isFalse();
        assertThat(result.incompleteLayer()).isEqualTo(DeliveryFailureCoordinator.Layer.REDIS_MIRROR);
        verify(taskQueue, never()).recordDeliveryFailure(any(), any(), any(), any());
    }

    @Test
    void should_fail_closed_and_stop_when_article_snapshot_fails() {
        doThrow(new IllegalStateException("disk full")).when(archiveRepository)
                .markDeliveryFailed(eq("tw-1"), any(), any(), any(), any(), any());

        DeliveryFailureCoordinator.ConvergenceResult result = coordinator.converge(
                "tw-1", "delivery:tw-1", "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD", "safe");

        assertThat(result.converged()).isFalse();
        assertThat(result.incompleteLayer()).isEqualTo(DeliveryFailureCoordinator.Layer.ARTICLE_SNAPSHOT);
        verify(articleStatusService, never()).markDeliveryFailed(any());
        verify(taskQueue, never()).recordDeliveryFailure(any(), any(), any(), any());
    }

    @Test
    void should_report_task_queue_layer_and_remain_not_converged() {
        when(taskQueue.recordDeliveryFailure("delivery:tw-1", "tw-1", "MEDIA_DOWNLOAD", "safe"))
                .thenReturn(false);

        DeliveryFailureCoordinator.ConvergenceResult result = coordinator.converge(
                "tw-1", "delivery:tw-1", "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD", "safe");

        assertThat(result.converged()).isFalse();
        assertThat(result.incompleteLayer()).isEqualTo(DeliveryFailureCoordinator.Layer.TASK_QUEUE);
        verify(articleStatusService).markDeliveryFailed("tw-1");
    }
}
