package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.monitoring.CostMonitor;
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

    private ContentScheduler scheduler;

    @BeforeEach
    void setUp() {
        // lenient: 不所有用例都会路由到 processTask (例如 run-on-startup=false 的早返回用例)
        lenient().when(processorProperties.getTaskIdPrefix()).thenReturn("twitter");
        lenient().when(taskQueue.getProcessingTasks()).thenReturn(Set.of());
        lenient().when(taskQueue.isQueued("twitter:run")).thenReturn(false);
        scheduler = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, true);
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
        when(taskQueue.isQueued("twitter:run")).thenReturn(false, true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue).push("twitter:run");
        verify(taskQueue).poll(eq(0L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shouldContinueProcessContentEvenIfRecoveryThrowsRetryable() {
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
    void shouldContinueProcessContentEvenIfRecoveryThrowsUnexpectedException() {
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
    void shouldSkipStartupProcessingWhenRunOnStartupIsFalse() {
        ContentScheduler disabled = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, false);

        disabled.onStartup();

        // run-on-startup=false 时: 不应触发 recovery, 也不应 poll 队列
        verifyNoInteractions(recoveryRunner);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void shouldStillHonorCronWhenRunOnStartupIsFalse() {
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
    void shouldRouteTwitterTaskToProcessor() {
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
    void shouldSkipUnknownTaskIdPrefix(CapturedOutput output) {
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
    void shouldHandleBlankTaskId(CapturedOutput output) {
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
    void shouldRouteMultipleTwitterTasksInOneBatch() {
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
    void shouldRouteTaskByConfiguredPrefix() {
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
    void shouldTreatOldPrefixAsUnknownAfterConfigChange(CapturedOutput output) {
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
    void shouldRouteGithubTaskToGitHubProcessor() {
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
    void shouldWarnAndSkipGithubTaskWhenProcessorNotRegistered(CapturedOutput output) {
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
    void shouldPushTwitterRunOnCronTrigger() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue).push("twitter:run");
    }

    @Test
    void shouldSkipAutoProcessingWhenCostBudgetHalted(CapturedOutput output) {
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
    void shouldContinueAutoProcessingWhenCostMonitorFails(CapturedOutput output) {
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
    void shouldRefreshCurrentMonthCostBeforeAutomaticProcessing() {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        budgetGated.processContent();

        verify(costMonitor).refreshAndCheckProcessingHalted(any(YearMonth.class));
        verify(taskQueue).push("twitter:run");
    }

    @Test
    void shouldNotEnqueueStartupTaskBeforeBudgetGate() {
        ContentScheduler budgetGated = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                Optional.of(githubProcessor), processorProperties, Optional.of(costMonitor), true);
        when(costMonitor.refreshAndCheckProcessingHalted(any(YearMonth.class))).thenReturn(true);

        budgetGated.onStartup();

        verify(recoveryRunner).recoverPendingTasks();
        verify(taskQueue, never()).push("twitter:run");
        verify(taskQueue, never()).poll(any(Long.class), any(TimeUnit.class));
    }

    @Test
    void shouldPushTwitterRunOnStartup() {
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(taskQueue).push("twitter:run");
    }

    @Test
    void shouldSkipPushWhenRunTaskAlreadyQueued() {
        when(taskQueue.isQueued("twitter:run")).thenReturn(true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).push("twitter:run");
    }

    @Test
    void shouldSkipPushWhenRunTaskAlreadyProcessing() {
        when(taskQueue.getProcessingTasks()).thenReturn(Set.of("twitter:run"));
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.processContent();

        verify(taskQueue, never()).push("twitter:run");
    }

    @Test
    void shouldStillTriggerProcessContentWhenStartupEnqueueFails() {
        when(taskQueue.isQueued("twitter:run"))
                .thenReturn(false);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(null);

        scheduler.onStartup();

        verify(taskQueue).push("twitter:run");
    }
}
