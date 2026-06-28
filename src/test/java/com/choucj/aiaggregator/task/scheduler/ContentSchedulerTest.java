package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 1.6 {@link ContentScheduler} 单测 — 验证失败容错与启动钩子.
 *
 * <p>核心场景:
 * <ul>
 *   <li>AC-7: {@code processContent} 内部异常被吞,调度器存活</li>
 *   <li>AC-6 + CR W1: onStartup 是启动流程唯一编排者,显式调用 recovery + processContent</li>
 *   <li>AC-9 + CR W2: {@code schedule.run-on-startup=false} 时跳过启动处理</li>
 *   <li>recovery 失败仍继续 processContent(应用启动不被阻塞)</li>
 * </ul>
 *
 * <p><b>注:</b> {@code processTask} 是占位实现,仅记日志不抛异常;Retryable/NonRetryable
 * 路径的真实行为由 Story 2.6 Pipeline Integration 注入处理器后通过集成测试验证.
 */
@ExtendWith(MockitoExtension.class)
class ContentSchedulerTest {

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private TaskRecoveryRunner recoveryRunner;

    private ContentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ContentScheduler(taskQueue, recoveryRunner, true);
    }

    @Test
    void shouldCompleteTaskWhenProcessSucceeds() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("task-1")
                .thenReturn(null);

        scheduler.processContent();

        verify(taskQueue).complete("task-1");
    }

    @Test
    void shouldNotCallCompleteWhenQueueIsEmpty() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).complete(any());
    }

    @Test
    void shouldSurviveWhenPollThrowsRetryableException() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        // 调度器吞异常不抛出,不中断
        scheduler.processContent();

        // 验证调度器仍可再次执行 — 第二次 poll 正常返回 null
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);
        scheduler.processContent();
    }

    @Test
    void shouldTriggerRecoveryBeforeProcessContentOnStartup() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldContinueProcessContentEvenIfRecoveryThrowsRetryable() {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "recovery fail"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldContinueProcessContentEvenIfRecoveryThrowsUnexpectedException() {
        doThrow(new RuntimeException("unexpected"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    // ============ CR W2 修复: schedule.run-on-startup=false 早返回 ============

    @Test
    void shouldSkipStartupProcessingWhenRunOnStartupIsFalse() {
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, false);

        disabled.onStartup();

        // run-on-startup=false 时: 不应触发 recovery, 也不应 poll 队列
        verifyNoInteractions(recoveryRunner);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void shouldStillHonorCronWhenRunOnStartupIsFalse() {
        // run-on-startup=false 不影响 cron 触发的 processContent
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        disabled.processContent();

        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
        verifyNoInteractions(recoveryRunner);
    }
}
