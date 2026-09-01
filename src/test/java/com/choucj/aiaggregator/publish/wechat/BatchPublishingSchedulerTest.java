package com.choucj.aiaggregator.publish.wechat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class BatchPublishingSchedulerTest {

    @Mock
    private ArticlePublicationWorkflow publicationWorkflow;

    private BatchPublishingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchPublishingScheduler(publicationWorkflow);
    }

    @Test
    void shouldPublishDueArticles(CapturedOutput output) {
        when(publicationWorkflow.publishDueArticles())
                .thenReturn(new ArticlePublicationWorkflow.PublishDueResult(2, 2, 0));

        scheduler.processBatch();

        assertThat(output.getOut()).contains("批量发布完成: total=2, success=2, failure=0");
    }

    @Test
    void shouldKeepSchedulerAliveWhenWorkflowFails(CapturedOutput output) {
        when(publicationWorkflow.publishDueArticles())
                .thenThrow(new IllegalStateException("scan failed"));

        scheduler.processBatch();

        assertThat(output.getOut()).contains("批量发布失败(调度器存活");
    }
}
