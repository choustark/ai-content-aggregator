package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.5 {@link RetryDispatchScheduler} 单测 — 验证到期重投委托、
 * 批量 DISPATCHED 指标累计与软失败容错(异常吞掉不抛, 调度器存活).
 */
@ExtendWith(MockitoExtension.class)
class RetryDispatchSchedulerTest {

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private TaskMetrics taskMetrics;

    @Test
    void should_create_and_clear_correlation_context_for_each_dispatch_tick() {
        MDC.put(CorrelationContext.CORRELATION_ID_KEY, "stale-correlation");
        when(taskQueue.dispatchDueRetries()).thenAnswer(invocation -> {
            assertThat(CorrelationContext.require()).isNotEqualTo("stale-correlation");
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isNull();
            return 0;
        });
        RetryDispatchScheduler scheduler =
                new RetryDispatchScheduler(taskQueue, Optional.empty());

        scheduler.dispatchDueRetries();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_delegate_dispatch_and_record_metric_batch() {
        when(taskQueue.dispatchDueRetries()).thenReturn(3);
        RetryDispatchScheduler scheduler =
                new RetryDispatchScheduler(taskQueue, Optional.of(taskMetrics));

        scheduler.dispatchDueRetries();

        verify(taskQueue).dispatchDueRetries();
        // 一批 3 个任务按数量累计而非单次 +1(Task 7.2)
        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.DISPATCHED, 3);
    }

    @Test
    void should_not_record_metric_when_nothing_dispatched() {
        when(taskQueue.dispatchDueRetries()).thenReturn(0);
        RetryDispatchScheduler scheduler =
                new RetryDispatchScheduler(taskQueue, Optional.of(taskMetrics));

        scheduler.dispatchDueRetries();

        verifyNoInteractions(taskMetrics);
    }

    @Test
    void should_work_without_metrics_bean() {
        // Optional<TaskMetrics> 缺失不影响重投语义(只观测契约)
        when(taskQueue.dispatchDueRetries()).thenReturn(2);
        RetryDispatchScheduler scheduler =
                new RetryDispatchScheduler(taskQueue, Optional.empty());

        scheduler.dispatchDueRetries();

        verify(taskQueue).dispatchDueRetries();
    }

    @Test
    void should_survive_when_dispatch_throws_retryable_exception() {
        when(taskQueue.dispatchDueRetries())
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"))
                .thenReturn(1);
        RetryDispatchScheduler scheduler =
                new RetryDispatchScheduler(taskQueue, Optional.of(taskMetrics));

        // 第一次异常被吞(调度器存活), 第二次 tick 正常执行
        scheduler.dispatchDueRetries();
        scheduler.dispatchDueRetries();

        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.DISPATCHED, 1);
        verify(taskMetrics, never()).recordRetry(TaskMetrics.RetryMetricOutcome.DISPATCHED, 0);
    }
}
