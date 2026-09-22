package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import java.time.YearMonth;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * <p><b>Story 2.6 更新:</b> 构造器从 3 参数扩展为 5 参数, 加 {@link TwitterProcessor} mock
 * + {@link ProcessorProperties} mock (Patch-3 修复 — 配置驱动 taskId 前缀路由).
 * 现有 7 用例 taskId="task-1" 不匹配 "twitter:" 前缀, 走 "未知前缀" 路径不调 process(),
 * 业务逻辑不变.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContentSchedulerTest {

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private TaskRecoveryRunner recoveryRunner;

    @Mock
    private TwitterProcessor twitterProcessor;

    @Mock
    private GitHubProcessor githubProcessor;

    @Mock
    private ProcessorProperties processorProperties;

    @Mock
    private CostMonitor costMonitor;

    @Mock
    private TaskMetrics taskMetrics;

    private ContentScheduler scheduler;

    @BeforeEach
    void setUp() {
        MDC.clear();
        // lenient: 不所有用例都会路由到 processTask (例如 run-on-startup=false 的早返回用例)
        lenient().when(processorProperties.getTaskIdPrefix()).thenReturn("twitter");
        lenient().when(taskQueue.getProcessingTasks()).thenReturn(Set.of());
        lenient().when(taskQueue.isQueued("twitter:run")).thenReturn(false);
        scheduler = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, true);
    }

    @Test
    void should_expose_task_context_and_clear_mdc_when_task_is_processed() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:tweet:42")
                .thenReturn(null);
        doAnswer(invocation -> {
            assertThat(CorrelationContext.require()).isNotBlank();
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo("twitter:tweet:42");
            return null;
        }).when(twitterProcessor).process();

        scheduler.processContent();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_use_separate_recovery_and_processing_contexts_when_application_starts() {
        String[] recoveryCorrelationId = new String[1];
        String[] processingCorrelationId = new String[1];
        doAnswer(invocation -> {
            recoveryCorrelationId[0] = CorrelationContext.require();
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo("startup-recovery");
            return null;
        }).when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run")
                .thenReturn(null);
        doAnswer(invocation -> {
            processingCorrelationId[0] = CorrelationContext.require();
            return null;
        }).when(twitterProcessor).process();

        scheduler.onStartup();

        assertThat(recoveryCorrelationId[0]).isNotBlank();
        assertThat(processingCorrelationId[0]).isNotBlank().isNotEqualTo(recoveryCorrelationId[0]);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_complete_task_when_process_succeeds() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("task-1")
                .thenReturn(null);

        scheduler.processContent();

        verify(taskQueue).complete("task-1");
    }

    @Test
    void should_not_call_complete_when_queue_is_empty() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).complete(any());
    }

    @Test
    void should_survive_when_poll_throws_retryable_exception() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        // 调度器吞异常不抛出,不中断
        scheduler.processContent();

        // 验证调度器仍可再次执行 — 第二次 poll 正常返回 null
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);
        scheduler.processContent();
    }

    @Test
    void should_trigger_recovery_before_processing_when_application_starts() {
        when(taskQueue.isQueued("twitter:run")).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_continue_processing_when_recovery_throws_retryable_exception() {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "recovery fail"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.isQueued("twitter:run")).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_continue_processing_when_recovery_throws_unexpected_exception() {
        doThrow(new RuntimeException("unexpected"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.isQueued("twitter:run")).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    // ============ CR W2 修复: schedule.run-on-startup=false 早返回 ============

    @Test
    void should_skip_startup_processing_when_run_on_startup_is_false() {
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, false);

        disabled.onStartup();

        // run-on-startup=false 时: 不应触发 recovery, 也不应 poll 队列
        verifyNoInteractions(recoveryRunner);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_honor_cron_when_run_on_startup_is_false() {
        // run-on-startup=false 不影响 cron 触发的 processContent
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        disabled.processContent();

        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
        verify(taskQueue).push("twitter:run");
        verifyNoInteractions(recoveryRunner);
    }

    // ============ Story 2.6 Task 7: processTask 路由用例 (AC-6) ============

    @Test
    void should_route_twitter_task_when_prefix_matches() {
        // taskId 以 "twitter:" 前缀开头 → 路由到 TwitterProcessor.process()
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run")
                .thenReturn(null);

        scheduler.processContent();

        verify(twitterProcessor).process();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).complete("twitter:run");
    }

    @Test
    void should_skip_task_when_prefix_is_unknown(CapturedOutput output) {
        // taskId 不以 "twitter:" 开头 → 记 warn 跳过, 不调用 process, 仍 complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("unknown:xyz")
                .thenReturn(null);

        scheduler.processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).complete("unknown:xyz");
        assertThat(output.getOut()).contains("未知 taskId 前缀");
    }

    @Test
    void should_skip_task_when_task_id_is_blank(CapturedOutput output) {
        // taskId 为空白 → 记 warn 跳过, 不调用 process, 仍 complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("   ")
                .thenReturn(null);

        scheduler.processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).complete("   ");
        assertThat(output.getOut()).contains("taskId 为空");
    }

    @Test
    void should_route_multiple_twitter_tasks_when_batch_contains_multiple_items() {
        // 队列中含多个 twitter: 任务 → 顺序路由, 全部 process + complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run")
                .thenReturn("twitter:tweet:123")
                .thenReturn(null);

        scheduler.processContent();

        verify(twitterProcessor, times(2)).process();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).complete("twitter:run");
        verify(taskQueue).complete("twitter:tweet:123");
    }

    // ============ Patch-3 修复验证: 配置驱动 taskId 前缀路由 ============

    /**
     * Patch-3 修复: 改 processor.task-id-prefix 后路由仍能匹配.
     * 早期实现硬编码 "twitter:" — 改 prefix 后处理器产生的任务会被视为未知前缀跳过.
     */
    @Test
    void should_route_task_when_configured_prefix_matches() {
        when(processorProperties.getTaskIdPrefix()).thenReturn("twitter-stage");
        ContentScheduler staged = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter-stage:run")
                .thenReturn(null);

        staged.processContent();

        verify(twitterProcessor).process();
        verify(taskQueue).push("twitter-stage:run");
        verify(taskQueue).complete("twitter-stage:run");
    }

    /**
     * Patch-3 修复: 改 prefix 后旧前缀的任务应被视为未知 (验证配置真正生效, 不是硬编码兼容).
     */
    @Test
    void should_treat_old_prefix_as_unknown_when_configuration_changes(CapturedOutput output) {
        when(processorProperties.getTaskIdPrefix()).thenReturn("twitter-stage");
        ContentScheduler staged = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run")
                .thenReturn(null);

        staged.processContent();

        verify(twitterProcessor, never()).process();
        assertThat(output.getOut()).contains("未知 taskId 前缀");
    }

    // ============ Story 4.4 Task 4.5: github: 路由 (AC-7) ============

    @Test
    void should_route_github_task_when_processor_is_registered() {
        // AC-7: github: 前缀 → GitHubProcessor.process(), 不调 TwitterProcessor
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("github:run")
                .thenReturn(null);

        scheduler.processContent();

        verify(githubProcessor).process();
        verify(twitterProcessor, never()).process();
        verify(taskQueue).complete("github:run");
    }

    @Test
    void should_warn_and_skip_github_task_when_processor_is_not_registered(CapturedOutput output) {
        // AC-7: github.enabled=false → GitHubProcessor Bean 未注册, Optional.empty
        // github: 任务走 warn 日志跳过, 不抛异常 (仍 complete 避免队列阻塞)
        ContentScheduler withoutGithub = new ContentScheduler(taskQueue, recoveryRunner,
                twitterProcessor, Optional.empty(), processorProperties, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("github:run")
                .thenReturn(null);

        withoutGithub.processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).complete("github:run");
        assertThat(output.getOut()).contains("GitHubProcessor 未注册");
        assertThat(output.getOut()).contains("taskId=github:run");
    }

    @Test
    void should_push_twitter_run_when_cron_triggers() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue).push("twitter:run");
    }

    @Test
    void should_skip_automatic_processing_when_cost_budget_is_halted(CapturedOutput output) {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(true);

        budgetGated.processContent();

        verify(taskQueue, never()).push(any());
        verify(taskQueue, never()).poll(any(Long.class), any(TimeUnit.class));
        verify(twitterProcessor, never()).process();
        assertThat(output.getOut()).contains("成本预算已停机");
    }

    @Test
    void should_continue_automatic_processing_when_cost_monitor_fails(CapturedOutput output) {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenThrow(new RuntimeException("redis down"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        budgetGated.processContent();

        verify(taskQueue).push("twitter:run");
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
        assertThat(output.getOut()).contains("读取成本预算 gate 失败");
    }

    @Test
    void should_refresh_current_month_cost_when_automatic_processing_starts() {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        budgetGated.processContent();

        verify(costMonitor).refreshAndCheckProcessingHalted(any(YearMonth.class));
        verify(taskQueue).push("twitter:run");
    }

    @Test
    void should_not_enqueue_task_when_budget_gate_halts_processing() {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(true);

        budgetGated.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue, never()).push("twitter:run");
        verify(taskQueue, never()).poll(any(Long.class), any(TimeUnit.class));
    }

    @Test
    void should_push_twitter_run_when_application_starts() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(taskQueue).push("twitter:run");
    }

    @Test
    void should_skip_push_when_run_task_is_already_queued() {
        when(taskQueue.isQueued("twitter:run")).thenReturn(true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).push("twitter:run");
    }

    @Test
    void should_skip_push_when_run_task_is_already_processing() {
        when(taskQueue.getProcessingTasks()).thenReturn(Set.of("twitter:run"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).push("twitter:run");
    }

    @Test
    void should_record_success_and_window_once_when_task_completes() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(), Optional.of(taskMetrics), true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn("twitter:run", null);

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.SUCCESS);
        verify(taskMetrics).recordScheduleSuccess(TaskMetrics.Operation.CONTENT_FETCH);
    }

    @Test
    void should_record_skipped_when_task_prefix_is_unknown() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(), Optional.of(taskMetrics), true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn("unknown:https://secret.example/id", null);

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.UNKNOWN, TaskMetrics.Outcome.SKIPPED);
    }

    @Test
    void should_record_closed_failure_outcomes_when_processing_fails() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(), Optional.of(taskMetrics), true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", "twitter:non-retryable", "twitter:unexpected", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .doThrow(new NonRetryableException(ErrorCode.INTERNAL_ERROR, "permanent"))
                .doThrow(new IllegalStateException("unexpected"))
                .when(twitterProcessor).process();

        observed.processContent();
        observed.processContent();
        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.RETRYABLE_FAILURE);
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.NON_RETRYABLE_FAILURE);
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.UNEXPECTED_FAILURE);
        // Story 10.4: 不可重试失败走死信终态而非误写 COMPLETED; 可重试失败留在 processing 集合
        verify(taskQueue).markDeadLetter(eq("twitter:non-retryable"), eq("permanent"));
        verify(taskQueue, never()).complete(eq("twitter:non-retryable"));
        verify(taskQueue, never()).complete(eq("twitter:retryable"));
    }

    @Test
    void should_record_unknown_failure_when_route_resolution_fails() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(), Optional.of(taskMetrics), true);
        when(processorProperties.getTaskIdPrefix())
                .thenReturn("twitter")
                .thenThrow(new IllegalStateException("route config unavailable"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn("twitter:run");

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.UNKNOWN, TaskMetrics.Outcome.UNEXPECTED_FAILURE);
    }

    @Test
    void should_enqueue_and_process_when_startup_task_is_absent() {
        when(taskQueue.isQueued("twitter:run"))
                .thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(taskQueue).push("twitter:run");
    }

    @Test
    void should_keep_startup_listener_alive_when_enqueue_fails() {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"))
                .when(taskQueue).push("twitter:run");

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue, never()).poll(eq(0L), eq(TimeUnit.SECONDS));
    }
}
