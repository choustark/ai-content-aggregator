package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.monitoring.TaskMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.util.Optional;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class BatchPublishingSchedulerTest {

    @Mock
    private ArticlePublicationWorkflow publicationWorkflow;

    @Mock
    private TaskMetrics taskMetrics;

    private BatchPublishingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchPublishingScheduler(publicationWorkflow);
    }

    @Test
    void should_publish_due_articles_when_batch_runs(CapturedOutput output) {
        when(publicationWorkflow.publishDueArticles())
                .thenReturn(new ArticlePublicationWorkflow.PublishDueResult(2, 2, 0));

        scheduler.processBatch();

        assertThat(output.getOut()).contains("批量发布完成: total=2, success=2, failure=0");
    }

    @Test
    void should_keep_scheduler_alive_when_workflow_fails(CapturedOutput output) {
        // 接入 metrics mock 才能让 "工作流失败不得记窗口成功" 的反向断言真正生效
        BatchPublishingScheduler observed = new BatchPublishingScheduler(publicationWorkflow, Optional.of(taskMetrics));
        when(publicationWorkflow.publishDueArticles())
                .thenThrow(new IllegalStateException("scan failed"));

        observed.processBatch();

        assertThat(output.getOut()).contains("批量发布失败(调度器存活");
        verify(taskMetrics, never()).recordScheduleSuccess(TaskMetrics.Operation.BATCH_PUBLISH);
        verify(taskMetrics, never()).recordBatchArticles(anyLong(), anyLong());
    }

    @Test
    void should_record_batch_window_when_all_articles_succeed() {
        BatchPublishingScheduler observed = new BatchPublishingScheduler(publicationWorkflow, Optional.of(taskMetrics));
        when(publicationWorkflow.publishDueArticles())
                .thenReturn(new ArticlePublicationWorkflow.PublishDueResult(0, 0, 0));

        observed.processBatch();

        verify(taskMetrics).recordScheduleSuccess(TaskMetrics.Operation.BATCH_PUBLISH);
        verify(taskMetrics).recordBatchArticles(0, 0);
    }

    @Test
    void should_record_window_and_article_outcomes_when_any_article_fails() {
        BatchPublishingScheduler observed = new BatchPublishingScheduler(publicationWorkflow, Optional.of(taskMetrics));
        when(publicationWorkflow.publishDueArticles())
                .thenReturn(new ArticlePublicationWorkflow.PublishDueResult(2, 1, 1));

        observed.processBatch();

        verify(taskMetrics).recordScheduleSuccess(TaskMetrics.Operation.BATCH_PUBLISH);
        verify(taskMetrics).recordBatchArticles(1, 1);
    }
}
