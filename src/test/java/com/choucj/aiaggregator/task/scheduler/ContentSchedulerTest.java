package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.ArticleDeliveryRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.publish.status.ArticleDeliveryReplayExecutor;
import com.choucj.aiaggregator.publish.status.DeliveryFailureCoordinator;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.task.queue.RetryPolicyProperties;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * <p><b>Story 10.5 review 更新:</b> 批量任务 ID 日期化为 {@code twitter:run:{yyyy-MM-dd}}
 * (任务身份决策 — 同日确定性 ID 兼作去重键, 跨日新实例消除 attempt 跨批累计与死信阻断),
 * 全部用例经包级构造器注入 fixed Clock 保证日期确定; {@code markDeadLetter} 三参签名
 * (errorCode + 白名单摘要); F-R3 指标旁路隔离与 F-R8 推进异常批次内隔离新增用例。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ContentSchedulerTest {

    /** 固定时钟 — 2026-10-01T13:00Z(UTC 当日), 保证日期化实例 ID 可断言. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T13:00:00Z"), ZoneOffset.UTC);

    /** fixed Clock 下的当日批量任务实例 ID. */
    private static final String RUN_ID = "twitter:run:2026-10-01";

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
    @Mock private ArticleDeliveryReplayExecutor deliveryReplayExecutor;
    @Mock private DeliveryFailureCoordinator deliveryFailureCoordinator;
    @Mock private TweetMediaArchiveWriter archiveWriter;

    @BeforeEach
    void setUp() {
        MDC.clear();
        // lenient: 不所有用例都会路由到 processTask (例如 run-on-startup=false 的早返回用例)
        lenient().when(processorProperties.getTaskIdPrefix()).thenReturn("twitter");
        lenient().when(taskQueue.getProcessingTasks()).thenReturn(Set.of());
        lenient().when(taskQueue.isQueued(RUN_ID)).thenReturn(false);
    }

    /** 标准调度器(fixed Clock, 无成本 gate/指标). */
    private ContentScheduler scheduler() {
        return scheduler(Optional.empty(), Optional.empty());
    }

    /** 带成本 gate / 指标注入的调度器(fixed Clock). */
    private ContentScheduler scheduler(Optional<CostMonitor> costMonitor,
                                       Optional<TaskMetrics> taskMetrics) {
        return new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, costMonitor, taskMetrics,
                new RetryPolicyProperties(), true, FIXED_CLOCK);
    }

    private ContentScheduler schedulerWithReplay(Optional<ArticleDeliveryReplayExecutor> executor) {
        return new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(), Optional.empty(),
                executor, new RetryPolicyProperties(), true, FIXED_CLOCK);
    }

    @Test
    void should_route_delivery_replay_to_registered_executor() {
        when(taskQueue.poll(0L, TimeUnit.SECONDS)).thenReturn("delivery:tw-1:replay:req").thenReturn(null);
        schedulerWithReplay(Optional.of(deliveryReplayExecutor)).processContent();
        verify(deliveryReplayExecutor).execute("delivery:tw-1:replay:req");
        verify(taskQueue).complete("delivery:tw-1:replay:req");
    }

    @Test
    void should_dead_letter_delivery_replay_when_executor_is_missing() {
        when(taskQueue.poll(0L, TimeUnit.SECONDS)).thenReturn("delivery:tw-1:replay:req").thenReturn(null);
        when(taskQueue.markDeadLetter(anyString(), anyString(), anyString())).thenReturn(true);
        schedulerWithReplay(Optional.empty()).processContent();
        verify(taskQueue).markDeadLetter(eq("delivery:tw-1:replay:req"), anyString(), anyString());
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

        scheduler().processContent();

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
                .thenReturn(RUN_ID)
                .thenReturn(null);
        doAnswer(invocation -> {
            processingCorrelationId[0] = CorrelationContext.require();
            return null;
        }).when(twitterProcessor).process();

        scheduler().onStartup();

        assertThat(recoveryCorrelationId[0]).isNotBlank();
        assertThat(processingCorrelationId[0]).isNotBlank().isNotEqualTo(recoveryCorrelationId[0]);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_complete_task_when_process_succeeds() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("task-1")
                .thenReturn(null);

        scheduler().processContent();

        verify(taskQueue).complete("task-1");
    }

    @Test
    void should_not_call_complete_when_queue_is_empty() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();

        verify(taskQueue, never()).complete(any());
    }

    @Test
    void should_survive_when_poll_throws_retryable_exception() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        // 调度器吞异常不抛出,不中断
        scheduler().processContent();

        // 验证调度器仍可再次执行 — 第二次 poll 正常返回 null
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);
        scheduler().processContent();
    }

    @Test
    void should_trigger_recovery_before_processing_when_application_starts() {
        when(taskQueue.isQueued(RUN_ID)).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_continue_processing_when_recovery_throws_retryable_exception() {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "recovery fail"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.isQueued(RUN_ID)).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_continue_processing_when_recovery_throws_unexpected_exception() {
        doThrow(new RuntimeException("unexpected"))
                .when(recoveryRunner).recoverPendingTasks();
        when(taskQueue.isQueued(RUN_ID)).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    // ============ CR W2 修复: schedule.run-on-startup=false 早返回 ============

    @Test
    void should_skip_startup_processing_when_run_on_startup_is_false() {
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), false, FIXED_CLOCK);

        disabled.onStartup();

        // run-on-startup=false 时: 不应触发 recovery, 也不应 poll 队列
        verifyNoInteractions(recoveryRunner);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_honor_cron_when_run_on_startup_is_false() {
        // run-on-startup=false 不影响 cron 触发的 processContent
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), false, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        disabled.processContent();

        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
        verify(taskQueue).push(RUN_ID);
        verifyNoInteractions(recoveryRunner);
    }

    // ============ Story 2.6 Task 7: processTask 路由用例 (AC-6) ============

    @Test
    void should_route_twitter_task_when_prefix_matches() {
        // taskId 以 "twitter:" 前缀开头 → 路由到 TwitterProcessor.process()
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn(RUN_ID)
                .thenReturn(null);

        scheduler().processContent();

        verify(twitterProcessor).process();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).complete(RUN_ID);
    }

    @Test
    void should_skip_task_when_prefix_is_unknown(CapturedOutput output) {
        // taskId 不以 "twitter:" 开头 → 记 warn 跳过, 不调用 process, 仍 complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("unknown:xyz")
                .thenReturn(null);

        scheduler().processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).complete("unknown:xyz");
        assertThat(output.getOut()).contains("未知 taskId 前缀");
    }

    @Test
    void should_skip_task_when_task_id_is_blank(CapturedOutput output) {
        // taskId 为空白 → 记 warn 跳过, 不调用 process, 仍 complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("   ")
                .thenReturn(null);

        scheduler().processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).complete("   ");
        assertThat(output.getOut()).contains("taskId 为空");
    }

    @Test
    void should_route_multiple_twitter_tasks_when_batch_contains_multiple_items() {
        // 队列中含多个 twitter: 任务 → 顺序路由, 全部 process + complete
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn(RUN_ID)
                .thenReturn("twitter:tweet:123")
                .thenReturn(null);

        scheduler().processContent();

        verify(twitterProcessor, times(2)).process();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).complete(RUN_ID);
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
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), true, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter-stage:run:2026-10-01")
                .thenReturn(null);

        staged.processContent();

        verify(twitterProcessor).process();
        verify(taskQueue).push("twitter-stage:run:2026-10-01");
        verify(taskQueue).complete("twitter-stage:run:2026-10-01");
    }

    /**
     * Patch-3 修复: 改 prefix 后旧前缀的任务应被视为未知 (验证配置真正生效, 不是硬编码兼容).
     */
    @Test
    void should_treat_old_prefix_as_unknown_when_configuration_changes(CapturedOutput output) {
        when(processorProperties.getTaskIdPrefix()).thenReturn("twitter-stage");
        ContentScheduler staged = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), true, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run:2026-10-01")
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
                .thenReturn("github:run:2026-10-01")
                .thenReturn(null);

        scheduler().processContent();

        verify(githubProcessor).process();
        verify(twitterProcessor, never()).process();
        verify(taskQueue).complete("github:run:2026-10-01");
    }

    @Test
    void should_warn_and_skip_github_task_when_processor_is_not_registered(CapturedOutput output) {
        // AC-7: github.enabled=false → GitHubProcessor Bean 未注册, Optional.empty
        // github: 任务走 warn 日志跳过, 不抛异常 (仍 complete 避免队列阻塞)
        ContentScheduler withoutGithub = new ContentScheduler(taskQueue, recoveryRunner,
                twitterProcessor, Optional.empty(), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), true, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("github:run:2026-10-01")
                .thenReturn(null);

        withoutGithub.processContent();

        verify(twitterProcessor, never()).process();
        verify(taskQueue).complete("github:run:2026-10-01");
        assertThat(output.getOut()).contains("GitHubProcessor 未注册");
        assertThat(output.getOut()).contains("taskId=github:run:2026-10-01");
    }

    @Test
    void should_push_twitter_run_when_cron_triggers() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();

        verify(taskQueue).push(RUN_ID);
    }

    @Test
    void should_skip_automatic_processing_when_cost_budget_is_halted(CapturedOutput output) {
        ContentScheduler budgetGated = scheduler(Optional.of(costMonitor), Optional.empty());
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(true);

        budgetGated.processContent();

        verify(taskQueue, never()).push(any());
        verify(taskQueue, never()).poll(any(Long.class), any(TimeUnit.class));
        verify(twitterProcessor, never()).process();
        assertThat(output.getOut()).contains("成本预算已停机");
    }

    @Test
    void should_continue_automatic_processing_when_cost_monitor_fails(CapturedOutput output) {
        ContentScheduler budgetGated = scheduler(Optional.of(costMonitor), Optional.empty());
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenThrow(new RuntimeException("redis down"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        budgetGated.processContent();

        verify(taskQueue).push(RUN_ID);
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
        assertThat(output.getOut()).contains("读取成本预算 gate 失败");
    }

    @Test
    void should_refresh_current_month_cost_when_automatic_processing_starts() {
        ContentScheduler budgetGated = scheduler(Optional.of(costMonitor), Optional.empty());
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        budgetGated.processContent();

        verify(costMonitor).refreshAndCheckProcessingHalted(any(YearMonth.class));
        verify(taskQueue).push(RUN_ID);
    }

    @Test
    void should_not_enqueue_task_when_budget_gate_halts_processing() {
        ContentScheduler budgetGated = scheduler(Optional.of(costMonitor), Optional.empty());
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(true);

        budgetGated.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue, never()).push(RUN_ID);
        verify(taskQueue, never()).poll(any(Long.class), any(TimeUnit.class));
    }

    @Test
    void should_push_twitter_run_when_application_starts() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().onStartup();

        verify(taskQueue).push(RUN_ID);
    }

    @Test
    void should_skip_push_when_run_task_is_already_queued() {
        when(taskQueue.isQueued(RUN_ID)).thenReturn(true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();

        verify(taskQueue, never()).push(RUN_ID);
    }

    @Test
    void should_skip_push_when_run_task_is_already_processing() {
        when(taskQueue.getProcessingTasks()).thenReturn(Set.of(RUN_ID));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();

        verify(taskQueue, never()).push(RUN_ID);
    }

    // ============ Story 10.5 review F-R1: 日期化任务实例 ID(任务身份模型) ============

    @Test
    void should_reuse_same_day_instance_id_and_dedupe_when_triggered_twice() {
        // 同日多次触发(cron/startup)生成同一确定性 ID — 首次 push, 二次经 isQueued 守卫跳过
        when(taskQueue.isQueued(RUN_ID)).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();
        scheduler().processContent();

        verify(taskQueue, times(1)).push(RUN_ID);
    }

    @Test
    void should_generate_new_instance_id_when_crossing_day_boundary() {
        // 跨日新实例 ID — 消除固定 ID 死信阻断与 attempt 跨批累计
        Clock day2 = Clock.fixed(Instant.parse("2026-10-02T13:00:00Z"), ZoneOffset.UTC);
        ContentScheduler nextDay = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), true, day2);
        when(taskQueue.isQueued(anyString())).thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();
        nextDay.processContent();

        verify(taskQueue).push("twitter:run:2026-10-01");
        verify(taskQueue).push("twitter:run:2026-10-02");
    }

    // ============ Story 10.5: 失败推进(F-R3 指标隔离 / F-R8 推进异常隔离) ============

    @Test
    void should_record_success_and_window_once_when_task_completes() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(RUN_ID, null);

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.SUCCESS);
        verify(taskMetrics).recordScheduleSuccess(TaskMetrics.Operation.CONTENT_FETCH);
    }

    @Test
    void should_record_skipped_when_task_prefix_is_unknown() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn("unknown:https://secret.example/id", null);

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.UNKNOWN, TaskMetrics.Outcome.SKIPPED);
    }

    @Test
    void should_record_closed_failure_outcomes_when_processing_fails() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", "twitter:non-retryable", "twitter:unexpected", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .doThrow(new NonRetryableException(ErrorCode.INTERNAL_ERROR, "permanent"))
                .doThrow(new IllegalStateException("unexpected"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.scheduled(1, System.currentTimeMillis()));

        observed.processContent();
        observed.processContent();
        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.RETRYABLE_FAILURE);
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.NON_RETRYABLE_FAILURE);
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.UNEXPECTED_FAILURE);
        // Story 10.4: 不可重试失败走死信终态而非误写 COMPLETED
        // F-R5: 错误摘要白名单化 — 只入 errorCode + 消息长度, 不持久化异常原文(N4)
        verify(taskQueue).markDeadLetter(eq("twitter:non-retryable"),
                eq(ErrorCode.INTERNAL_ERROR.name()), eq("INTERNAL_ERROR len=9"));
        verify(taskQueue, never()).complete(eq("twitter:non-retryable"));
        // Story 10.5: 可重试失败不再留 processing, 而是原子推进重试/死信(摘要同样白名单化)
        verify(taskQueue).recordRetryableFailure(eq("twitter:retryable"),
                eq(ErrorCode.REDIS_CONNECTION_ERROR.name()), eq("REDIS_CONNECTION_ERROR len=9"),
                any(RetryPolicyProperties.class));
        verify(taskQueue, never()).complete(eq("twitter:retryable"));
    }

    @Test
    void should_record_exhausted_retry_metric_when_attempts_exhausted() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.deadLettered(3));

        observed.processContent();

        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.EXHAUSTED);
        verify(taskMetrics, never()).recordRetry(TaskMetrics.RetryMetricOutcome.SCHEDULED);
    }

    // ===== Story 10.8: 媒体交付可重试失败的耗尽收敛钩子 =====

    /** Story 10.8 (AC4): RETRY_SCHEDULED 时把 articleId 记入 state Hash — 耗尽时刻定位依据. */
    @Test
    void should_record_delivery_retry_context_when_article_failure_retry_scheduled() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new ArticleDeliveryRetryableException("tw-2083615699260313955",
                "媒体微信准备失败(可重试): errcode=45009"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.scheduled(1, System.currentTimeMillis()));

        observed.processContent();

        verify(taskQueue).recordDeliveryRetryContext("twitter:retryable", "tw-2083615699260313955");
        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.SCHEDULED);
    }

    /** Story 10.8: articleId 上下文写入失败只降级告警 — SCHEDULED 指标与审计日志必然执行. */
    @Test
    void should_still_record_scheduled_metric_when_delivery_retry_context_write_throws() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new ArticleDeliveryRetryableException("tw-2083615699260313955",
                "媒体微信准备失败(可重试): errcode=45009"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.scheduled(1, System.currentTimeMillis()));
        doThrow(new IllegalStateException("redis hset broken"))
                .when(taskQueue).recordDeliveryRetryContext("twitter:retryable",
                        "tw-2083615699260313955");

        observed.processContent();

        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.SCHEDULED);
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER,
                TaskMetrics.Outcome.RETRYABLE_FAILURE);
    }

    /** Story 10.8: 非媒体交付类 RetryableException 不写 articleId 上下文 (无 getArticleId). */
    @Test
    void should_not_record_delivery_retry_context_for_plain_retryable_exception() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.scheduled(1, System.currentTimeMillis()));

        observed.processContent();

        verify(taskQueue, never()).recordDeliveryRetryContext(anyString(), anyString());
    }

    /** Story 10.8 (AC4): 重试耗尽落死信后 — 终态化 sidecar + 四层收敛 (state.articleId 驱动). */
    @Test
    void should_converge_exhausted_prepare_after_dead_letter_when_article_context_present() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.of(taskMetrics), Optional.empty(),
                Optional.of(deliveryFailureCoordinator), Optional.of(archiveWriter),
                new RetryPolicyProperties(), true, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new ArticleDeliveryRetryableException("tw-2083615699260313955",
                "媒体微信准备失败(可重试): errcode=45009"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.deadLettered(3));
        when(taskQueue.readStateArticleId("twitter:retryable"))
                .thenReturn(Optional.of("tw-2083615699260313955"));
        when(archiveWriter.markWechatPrepareExhausted(eq("tw-2083615699260313955"),
                anyString(), anyString())).thenReturn(true);
        when(deliveryFailureCoordinator.converge(eq("tw-2083615699260313955"),
                eq("twitter:retryable"), eq("WECHAT_PREPARE"), anyString(), anyString()))
                .thenReturn(new DeliveryFailureCoordinator.ConvergenceResult(true,
                        DeliveryFailureCoordinator.Layer.COMPLETE));

        observed.processContent();

        // 先终态化 wechatPrepare 证据, 再四层收敛 (stage=WECHAT_PREPARE)
        verify(archiveWriter).markWechatPrepareExhausted(eq("tw-2083615699260313955"),
                anyString(), anyString());
        verify(deliveryFailureCoordinator).converge(eq("tw-2083615699260313955"),
                eq("twitter:retryable"), eq("WECHAT_PREPARE"), anyString(), anyString());
        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.EXHAUSTED);
    }

    /** Story 10.8: state 无 articleId (非媒体交付失败) 时耗尽钩子不收敛. */
    @Test
    void should_not_converge_exhausted_prepare_without_article_context() {
        ContentScheduler observed = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.of(taskMetrics), Optional.empty(),
                Optional.of(deliveryFailureCoordinator), Optional.of(archiveWriter),
                new RetryPolicyProperties(), true, FIXED_CLOCK);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.deadLettered(3));
        when(taskQueue.readStateArticleId("twitter:retryable")).thenReturn(Optional.empty());

        observed.processContent();

        verify(taskQueue, never()).recordDeliveryRetryContext(anyString(), anyString());
        verify(archiveWriter, never()).markWechatPrepareExhausted(anyString(), anyString(), anyString());
        verify(deliveryFailureCoordinator, never()).converge(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void should_not_record_dead_lettered_metric_when_mark_dead_letter_skipped() {
        // F13: markDeadLetter 返回 false = 任务不在 processing(幂等跳过),
        // 死信并未真正发生, 计 DEAD_LETTERED 会让死信指标虚高误导运维
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:non-retryable", null);
        doThrow(new NonRetryableException(ErrorCode.INTERNAL_ERROR, "permanent"))
                .when(twitterProcessor).process();
        when(taskQueue.markDeadLetter(anyString(), anyString(), anyString())).thenReturn(false);

        observed.processContent();

        verify(taskQueue).markDeadLetter(eq("twitter:non-retryable"),
                eq(ErrorCode.INTERNAL_ERROR.name()), eq("INTERNAL_ERROR len=9"));
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_continue_failure_handling_when_metric_recording_throws() {
        // F14 + F-R3: 指标组件故障只降级为告警 — 先权威状态迁移后旁路指标,
        // 指标抛出不得影响已完成的失败推进, 也不得中断批次
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", "twitter:healthy", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .doNothing()
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.scheduled(1, System.currentTimeMillis()));
        doThrow(new IllegalStateException("metrics registry broken"))
                .when(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.SCHEDULED);

        observed.processContent();

        // 主流程不受影响: 失败推进照常执行, 后续健康任务照常处理成功
        verify(taskQueue).recordRetryableFailure(eq("twitter:retryable"),
                eq(ErrorCode.REDIS_CONNECTION_ERROR.name()), anyString(),
                any(RetryPolicyProperties.class));
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER,
                TaskMetrics.Outcome.RETRYABLE_FAILURE);
        verify(taskQueue).complete("twitter:healthy");
    }

    @Test
    void should_continue_loop_when_retryable_task_not_in_processing_set() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:retryable", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .when(twitterProcessor).process();
        // NOT_IN_PROCESSING: 任务已被并发恢复迁出, 幂等跳过不抛异常, while 循环继续
        when(taskQueue.recordRetryableFailure(anyString(), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenReturn(TaskQueue.RetryAdvance.notInProcessing());

        observed.processContent();

        verify(taskQueue, never()).complete(eq("twitter:retryable"));
        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER,
                TaskMetrics.Outcome.RETRYABLE_FAILURE);
    }

    @Test
    void should_continue_batch_when_retry_advance_throws_non_retryable(CapturedOutput output) {
        // F-R8: state 数据损坏令 recordRetryableFailure 抛 NonRetryableException —
        // 推进异常批次内隔离(任务保持 PROCESSING 待恢复重排), 不得中断批次拖累后续健康任务
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:corrupt", "twitter:healthy", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .doNothing()
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(eq("twitter:corrupt"), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "corrupt state"));

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.TWITTER,
                TaskMetrics.Outcome.RETRYABLE_FAILURE);
        verify(twitterProcessor, times(2)).process();
        verify(taskQueue).complete("twitter:healthy");
        assertThat(output.getOut()).contains("可重试失败推进异常");
    }

    @Test
    void should_continue_batch_when_retry_advance_throws_unexpected(CapturedOutput output) {
        // F-R8: 推进自身的 RuntimeException 同样批次内隔离
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:broken", "twitter:healthy", null);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "temporary"))
                .doNothing()
                .when(twitterProcessor).process();
        when(taskQueue.recordRetryableFailure(eq("twitter:broken"), anyString(), anyString(),
                any(RetryPolicyProperties.class)))
                .thenThrow(new IllegalStateException("cluster topology changed"));

        observed.processContent();

        verify(twitterProcessor, times(2)).process();
        verify(taskQueue).complete("twitter:healthy");
        assertThat(output.getOut()).contains("可重试失败推进异常");
    }

    @Test
    void should_continue_batch_when_dead_letter_advance_throws(CapturedOutput output) {
        // F-R8: 不可重试失败的死信推进异常同样批次内隔离
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:corrupt", "twitter:healthy", null);
        doThrow(new NonRetryableException(ErrorCode.INTERNAL_ERROR, "permanent"))
                .doNothing()
                .when(twitterProcessor).process();
        when(taskQueue.markDeadLetter(eq("twitter:corrupt"), anyString(), anyString()))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "state attempt corrupt"));

        observed.processContent();

        verify(twitterProcessor, times(2)).process();
        verify(taskQueue).complete("twitter:healthy");
        assertThat(output.getOut()).contains("不可重试失败推进异常");
    }

    @Test
    void should_record_unknown_failure_when_route_resolution_fails() {
        ContentScheduler observed = scheduler(Optional.empty(), Optional.of(taskMetrics));
        when(processorProperties.getTaskIdPrefix())
                .thenReturn("twitter")
                .thenThrow(new IllegalStateException("route config unavailable"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(RUN_ID);

        observed.processContent();

        verify(taskMetrics).recordProcessed(TaskMetrics.Source.UNKNOWN, TaskMetrics.Outcome.UNEXPECTED_FAILURE);
    }

    @Test
    void should_enqueue_and_process_when_startup_task_is_absent() {
        when(taskQueue.isQueued(RUN_ID))
                .thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().onStartup();

        verify(taskQueue).push(RUN_ID);
    }

    @Test
    void should_keep_startup_listener_alive_when_enqueue_fails() {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"))
                .when(taskQueue).push(RUN_ID);

        scheduler().onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push(RUN_ID);
        verify(taskQueue, never()).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    // ============ Story 10.6: 终态编排(调度器层 mock 编排, 非脚本守卫) ============
    // 注意: 本节 taskQueue 为 mock, Lua push/RECOVER 脚本并不实际执行;
    // push 脚本终态守卫(COMPLETED 返回 5 幂等跳过)的真实覆盖位置是
    // TaskQueueTest.should_skip_push_when_task_state_is_already_completed 及 external 集成套件.

    @Test
    void should_not_reprocess_when_completed_run_task_is_reenqueued_same_day(CapturedOutput output) {
        // 调度器层编排: 同日 push 返回后 poll 为空(mock 模拟 push 脚本终态守卫拦截后的队列视角),
        // 调度器不得重跑整批(不处理、不 complete); 脚本级拦截行为见 TaskQueueTest 与 external 套件
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler().processContent();

        verify(taskQueue).push(RUN_ID);
        verify(twitterProcessor, never()).process();
        verify(taskQueue, never()).complete(anyString());
    }

    @Test
    void should_process_cross_day_instance_normally_when_previous_day_completed() {
        // 调度器层编排: 跨日实例 ID 日期化(twitter:run:{d+1}), isQueued=false 时正常入队
        // 并被完整处理(push+process+complete); 脚本级同 ID 拦截见 TaskQueueTest 与 external 套件
        Clock day2 = Clock.fixed(Instant.parse("2026-10-02T13:00:00Z"), ZoneOffset.UTC);
        ContentScheduler nextDay = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.empty(),
                Optional.empty(), new RetryPolicyProperties(), true, day2);
        when(taskQueue.isQueued("twitter:run:2026-10-02")).thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:run:2026-10-02", (String) null);

        nextDay.processContent();

        verify(taskQueue).push("twitter:run:2026-10-02");
        verify(twitterProcessor).process();
        verify(taskQueue).complete("twitter:run:2026-10-02");
    }

    @Test
    void should_continue_processing_after_recovery_leaves_terminal_states_untouched() {
        // 调度器层编排: onStartup 触发恢复后, 恢复动作不阻塞当日正常处理流程
        // (recover+process+complete); RECOVER 脚本仅重排 PROCESSING、终态成员返回 0 跳过的
        // 脚本级行为由 TaskQueueTest 与 external 集成套件覆盖
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(RUN_ID, (String) null);

        scheduler().onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(twitterProcessor).process();
        verify(taskQueue).complete(RUN_ID);
    }
}
