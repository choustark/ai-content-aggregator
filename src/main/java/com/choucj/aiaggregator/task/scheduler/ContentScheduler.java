package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.task.queue.RetryPolicyProperties;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 内容处理调度器 — 基于 Spring {@code @Scheduled} + Redis 任务队列(Story 1.6).
 *
 * <p><b>触发机制:</b>
 * <ul>
 *   <li>{@code @Scheduled(cron = "${schedule.cron:0 0 22 * * ?}")} — 默认每天晚上 22:00 触发</li>
 *   <li>{@code @EventListener(ApplicationReadyEvent.class)} — 应用启动时执行一次
 *       (含断点恢复), 由 {@code schedule.run-on-startup} 控制是否启用(CR W2 修复)</li>
 * </ul>
 *
 * <p><b>失败容错:</b> {@link #processContent()} 内部 try/catch 兜底,
 * 即使内部抛出 {@link RetryableException} / {@link NonRetryableException} / 任意 RuntimeException,
 * 调度器继续存活, 下次 cron 触发时仍正常执行. 这是关键设计: 单次任务失败不应该杀掉整个调度器.
 *
 * <p><b>构造器注入:</b> 遵循架构 L1490-1495 强制构造器注入规范,
 * 替代架构文档 L846 的 {@code @Autowired} 字段注入模式(Story 1.6 Delta).
 *
 * <p><b>启动钩子 vs 架构文档:</b> 架构文档 L862 用 {@code @Scheduled(fixedDelay = Long.MAX_VALUE)}
 * 是 hack(意图"启动时执行一次"); Story 1.6 改用 {@link EventListener}/{@link ApplicationReadyEvent}
 * 是 Spring Boot 官方推荐模式(Story 1.6 Delta).
 *
 * <p><b>CR W1 修复(2026-06-27):</b> 启动流程由本类 {@link #onStartup()} 独占编排 —
 * 先调用 {@link TaskRecoveryRunner#recoverPendingTasks()} 后调用 {@link #processContent()}.
 * {@link TaskRecoveryRunner} 不再独立监听 {@code ApplicationReadyEvent}, 避免双监听器顺序不确定
 * 导致 processing 集合中正在处理的任务被错误重入队.
 *
 * <p><b>CR W2 修复(2026-06-27):</b> 注入 {@code schedule.run-on-startup}(默认 {@code false}),
 * 关闭时仅按 cron 周期触发, 应用启动后等待下一个 cron 时刻.
 *
 * <p>引用源: Story 1.6 创建;CR W1/W2 修复(2026-06-27);消费方 Story 2.6 Pipeline Integration.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContentScheduler {

    private final TaskQueue taskQueue;
    private final TaskRecoveryRunner recoveryRunner;
    private final TwitterProcessor twitterProcessor;
    private final Optional<GitHubProcessor> githubProcessorOptional;
    private final ProcessorProperties processorProperties;
    private final Optional<CostMonitor> costMonitorOptional;
    private final Optional<TaskMetrics> taskMetricsOptional;
    private final RetryPolicyProperties retryPolicy;
    private final boolean runOnStartup;
    private final Clock clock;

    /**
     * 构造器注入 {@code schedule.run-on-startup}(CR W2 修复) +
     * {@link TwitterProcessor} (Story 2.6 Pipeline Integration) +
     * {@link Optional<GitHubProcessor>} (Story 4.4 AC-7 — github: 路由, 测试/未来拆分时保留容错) +
     * {@link ProcessorProperties} (Patch-3 修复 — 配置驱动 taskId 前缀路由).
     *
     * <p>不用 {@code @RequiredArgsConstructor} 是因为 boolean 配置项需要
     * {@code @Value} 显式标注 + 默认值, Lombok 自动生成的构造器无法表达.
     * 其他依赖({@link TaskQueue} / {@link TaskRecoveryRunner} / {@link TwitterProcessor} /
     * {@link ProcessorProperties}) 仍走 Spring 自动注入.
     *
     * <p><b>Story 4.4 github: 容错 (AC-7):</b> {@link GitHubProcessor} 常驻注册并在
     * {@code process()} 内处理 {@code features.github.enabled} + {@code feature-flags.github.enabled} +
     * {@code github.enabled} 三开关 disabled 0-summary 契约. 此处保留 {@link Optional<T>} 注入,
     * 让测试或未来拆分 processor Bean 时 ContentScheduler 启动不受影响.
     *
     * @param taskQueue                任务队列
     * @param recoveryRunner           断点恢复 Bean
     * @param twitterProcessor         Twitter 处理流水线 (Story 2.6, 按 taskId 前缀路由)
     * @param githubProcessorOptional  GitHub 处理流水线 (Story 4.4, 常规运行常驻注册; 测试/未来拆分时可为空)
     * @param processorProperties      Processor 配置 (Story 2.6 Patch-3, 提供 task-id-prefix 路由判断)
     * @param runOnStartup             启动时是否执行首次处理, 默认 false (来自 {@code schedule.run-on-startup})
     */
    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            TwitterProcessor twitterProcessor,
                            Optional<GitHubProcessor> githubProcessorOptional,
                            ProcessorProperties processorProperties,
                            @Value("${schedule.run-on-startup:false}") boolean runOnStartup) {
        this(taskQueue, recoveryRunner, twitterProcessor, githubProcessorOptional, processorProperties,
                Optional.empty(), Optional.empty(), new RetryPolicyProperties(), runOnStartup,
                Clock.systemDefaultZone());
    }

    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            TwitterProcessor twitterProcessor,
                            Optional<GitHubProcessor> githubProcessorOptional,
                            ProcessorProperties processorProperties,
                            Optional<CostMonitor> costMonitorOptional,
                            @Value("${schedule.run-on-startup:false}") boolean runOnStartup) {
        this(taskQueue, recoveryRunner, twitterProcessor, githubProcessorOptional, processorProperties,
                costMonitorOptional, Optional.empty(), new RetryPolicyProperties(), runOnStartup,
                Clock.systemDefaultZone());
    }

    @Autowired
    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            TwitterProcessor twitterProcessor,
                            Optional<GitHubProcessor> githubProcessorOptional,
                            ProcessorProperties processorProperties,
                            Optional<CostMonitor> costMonitorOptional,
                            Optional<TaskMetrics> taskMetricsOptional,
                            RetryPolicyProperties retryPolicy,
                            @Value("${schedule.run-on-startup:false}") boolean runOnStartup) {
        this(taskQueue, recoveryRunner, twitterProcessor, githubProcessorOptional, processorProperties,
                costMonitorOptional, taskMetricsOptional, retryPolicy, runOnStartup,
                Clock.systemDefaultZone());
    }

    /**
     * 全参构造器 — 额外注入 {@link Clock} 供批量任务实例 ID 日期化
     * (10.5 review 二轮任务身份决策, 测试注入 fixed Clock 验证跨日实例化;
     * Spring 装配走 {@code @Autowired} 构造器并以 {@code Clock.systemDefaultZone()} 委托)。
     */
    ContentScheduler(TaskQueue taskQueue,
                     TaskRecoveryRunner recoveryRunner,
                     TwitterProcessor twitterProcessor,
                     Optional<GitHubProcessor> githubProcessorOptional,
                     ProcessorProperties processorProperties,
                     Optional<CostMonitor> costMonitorOptional,
                     Optional<TaskMetrics> taskMetricsOptional,
                     RetryPolicyProperties retryPolicy,
                     boolean runOnStartup,
                     Clock clock) {
        this.taskQueue = taskQueue;
        this.recoveryRunner = recoveryRunner;
        this.twitterProcessor = twitterProcessor;
        this.githubProcessorOptional = githubProcessorOptional;
        this.processorProperties = processorProperties;
        this.costMonitorOptional = costMonitorOptional;
        this.taskMetricsOptional = taskMetricsOptional;
        this.retryPolicy = retryPolicy;
        this.runOnStartup = runOnStartup;
        this.clock = clock;
    }

    /**
     * 每天晚上 22:00 触发(默认),可通过 {@code schedule.cron} 配置覆盖.
     *
     * <p>失败容错: 任意异常被 catch 记 error 日志, 不抛出, 保证调度器存活.
     */
    @Scheduled(cron = "${schedule.cron:0 0 22 * * ?}")
    public void processContent() {
        CorrelationContext.begin(null);
        try {
            log.info("开始执行内容处理任务");
            if (isBudgetHalted()) {
                log.error("成本预算已停机, 跳过本次自动内容处理");
                return;
            }
            enqueueRunTaskIfAbsent("cron");
            processQueueOnce();
            taskMetricsOptional.ifPresent(metrics ->
                    metrics.recordScheduleSuccess(TaskMetrics.Operation.CONTENT_FETCH));
            log.info("内容处理任务完成");
        } catch (Exception e) {
            log.error("内容处理任务失败(调度器存活, 等待下次 cron 触发)", e);
        } finally {
            CorrelationContext.end();
        }
    }

    /**
     * 应用启动后触发: 先断点恢复(委托给 {@link TaskRecoveryRunner}), 再触发首次内容处理.
     *
     * <p>用 {@link EventListener}/{@link ApplicationReadyEvent} 而非
     * {@code @Scheduled(fixedDelay = Long.MAX_VALUE)} hack(Story 1.6 Delta).
     *
     * <p>CR W1 修复: 本方法是启动流程的<b>唯一</b>触发点, {@link TaskRecoveryRunner} 不再独立
     * 监听 {@code ApplicationReadyEvent}, 由此处显式调用以保证"先恢复, 后处理"顺序.
     *
     * <p>CR W2 修复: {@code schedule.run-on-startup=false} 时仅记 info 日志并早返回,
     * 不做断点恢复也不触发首次处理 — 应用启动后等待下一个 cron 时刻.
     *
     * <p><b>Patch-9 修复 (2026-06-30, Round 3 review):</b> {@code enqueueRunTaskIfAbsent("startup")}
     * 包在 try/catch 中. Round 2 Patch-8 在此处显式入队 startup 任务, 但未做异常兜底 — 若启动时
     * Redis 不可达, {@code TaskQueue.isQueued} / {@code getProcessingTasks} 抛 {@link RetryableException}
     * 会逃逸到 {@code @EventListener} 外, 抑制下方 {@code processContent()} 调用, 启动触发器静默丢失.
     * 包 try/catch 后失败仅记 warn, {@code processContent()} 内部仍会触发自己的 enqueue 兜底.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            log.info("schedule.run-on-startup=false, 跳过启动时处理, 等待下一个 cron 时刻");
            return;
        }
        CorrelationContext.begin("startup-recovery");
        try {
            log.info("应用启动完成, 执行断点恢复");
            recoveryRunner.recoverPendingTasks();
        } catch (Exception e) {
            log.warn("启动时断点恢复失败, 继续触发首次内容处理", e);
        } finally {
            CorrelationContext.end();
        }
        processContent();
    }

    /**
     * 取出队列中所有当前可处理的任务并执行.
     *
     * <p>MVP 实现: 单线程顺序处理. 多线程/并发处理留 Story 1.7 Feature Flags 控制.
     *
     * <p>异常策略(Story 10.5 起, 10.5 review 二轮强化):
     * <ul>
     *   <li><b>先权威状态迁移, 后旁路指标</b> — 处理结果指标经 safe wrapper 记录且只在
     *       状态迁移完成后调用; 指标组件故障只降级告警, 不会让任务停留 processing
     *       或中断当前批次, 也不会把已成功的迁移误报为失败</li>
     *   <li>{@link RetryableException} — 任务失败可重试, 经
     *       {@code TaskQueue.recordRetryableFailure} 单个原子状态迁移推进:
     *       未达 {@code task.retry.max-attempts} 上限时转 RETRY_SCHEDULED
     *       (retry ZSET 按 dueAt 延迟, 由 RetryDispatchScheduler 到期重投回 pending);
     *       达上限时同脚本落死信终态 DEAD_LETTER。推进自身的异常(如 state 数据损坏)
     *       在批次内隔离 — 单个损坏任务不得拖延其后全部健康 pending 任务, 也不得
     *       提前结束本批(任务保持 PROCESSING, 重启恢复按状态重排)</li>
     *   <li>{@link NonRetryableException} — 任务不可重试, 经 {@code TaskQueue.markDeadLetter}
     *       原子移入死信终态(DEAD_LETTER, 审计本次执行的 errorCode 与递增后 attempt),
     *       不再误写 COMPLETED</li>
     * </ul>
     */
    private void processQueueOnce() {
        while (true) {
            CorrelationContext.putTaskId(null);
            String taskId = taskQueue.poll(0, TimeUnit.SECONDS);
            if (taskId == null) {
                return;
            }
            CorrelationContext.putTaskId(taskId);
            long startedAtMs = System.currentTimeMillis();
            TaskRoute route = TaskRoute.UNKNOWN;
            try {
                route = routeOf(taskId);
                TaskMetrics.Outcome outcome = processTask(taskId, route);
                taskQueue.complete(taskId);
                recordProcessedSafely(route.source(), outcome);
            } catch (RetryableException e) {
                // 先权威状态迁移(推进异常批次内隔离), 再旁路记录指标 —
                // 指标故障不得让任务停留 processing 或中断批次(10.5 review 二轮)
                advanceRetryableFailure(taskId, e, startedAtMs);
                recordProcessedSafely(route.source(), TaskMetrics.Outcome.RETRYABLE_FAILURE);
            } catch (NonRetryableException e) {
                deadLetterNonRetryableFailure(taskId, e);
                recordProcessedSafely(route.source(), TaskMetrics.Outcome.NON_RETRYABLE_FAILURE);
            } catch (RuntimeException e) {
                recordProcessedSafely(route.source(), TaskMetrics.Outcome.UNEXPECTED_FAILURE);
                throw e;
            }
        }
    }

    /**
     * 可重试失败推进(10.5 review 二轮) — 调用 {@code TaskQueue.recordRetryableFailure}
     * 完成权威状态迁移并按 outcome 记录审计日志与指标.
     *
     * <p>批次内隔离(F-R8): 推进自身抛出的 {@link NonRetryableException}(state 数据损坏
     * 写前拒绝)或 RuntimeException 被本方法捕获记 ERROR, 不向上传播 — 否则异常会直接结束
     * {@code processQueueOnce}(Retryable catch 内的同级 catch 不会再捕获),
     * 单个损坏任务拖延其后全部健康 pending 任务并滞留 processing。
     * 错误摘要经 {@link #safeErrorSummary} 白名单脱敏(N4), 不得持久化第三方异常原文。
     */
    private void advanceRetryableFailure(String taskId, RetryableException e, long startedAtMs) {
        try {
            TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                    taskId, errorCodeOf(e), safeErrorSummary(e), retryPolicy);
            TaskQueue.RetryOutcome outcome = advance.outcome();
            if (outcome == TaskQueue.RetryOutcome.RETRY_SCHEDULED) {
                recordRetryMetricSafely(TaskMetrics.RetryMetricOutcome.SCHEDULED, taskId);
                // W11: 重试排期审计日志 — 到点时间/尝试次数/失败耗时全量落盘,
                // 运维凭 taskId 即可回答"这个任务下次何时重试"(10.5 review)
                log.warn("任务 {} 失败(可重试), 已排期重试: attempt={}, dueAtEpochMs={}, "
                                + "failedAfterMs={}, errorCode={}",
                        taskId, advance.attempt(), advance.dueAtEpochMs(),
                        System.currentTimeMillis() - startedAtMs, errorCodeOf(e));
            } else if (outcome == TaskQueue.RetryOutcome.DEAD_LETTERED) {
                recordRetryMetricSafely(TaskMetrics.RetryMetricOutcome.EXHAUSTED, taskId);
                log.warn("任务 {} 失败(可重试)且尝试耗尽, 已落死信: attempt={}, "
                                + "failedAfterMs={}, errorCode={}",
                        taskId, advance.attempt(),
                        System.currentTimeMillis() - startedAtMs, errorCodeOf(e));
            } else {
                log.warn("任务 {} 失败(可重试), 但不在 processing 集合, 跳过重试推进: outcome={}",
                        taskId, outcome, e);
            }
        } catch (RuntimeException advanceFailure) {
            log.error("任务 {} 可重试失败推进异常(批次内隔离, 继续处理后续任务; "
                            + "任务保持 PROCESSING 待重启恢复按状态重排): errorType={}, detail={}",
                    taskId, advanceFailure.getClass().getSimpleName(), advanceFailure.getMessage());
        }
    }

    /**
     * 不可重试失败死信推进(10.5 review 二轮) — 调用 {@code TaskQueue.markDeadLetter}
     * 完成权威状态迁移, 审计<b>本次执行</b>的 errorCode 与递增后 attempt.
     *
     * <p>批次内隔离同 {@link #advanceRetryableFailure}: 推进异常(state attempt 损坏等)
     * 捕获记 ERROR 不传播, 不中断批次。指标仅在真正落入死信时计 DEAD_LETTERED(F13)。
     */
    private void deadLetterNonRetryableFailure(String taskId, NonRetryableException e) {
        try {
            boolean deadLettered = taskQueue.markDeadLetter(taskId, errorCodeOf(e), safeErrorSummary(e));
            if (deadLettered) {
                log.warn("任务 {} 失败(不可重试), 移入死信终态 DEAD_LETTER: errorCode={}",
                        taskId, errorCodeOf(e));
                recordRetryMetricSafely(TaskMetrics.RetryMetricOutcome.DEAD_LETTERED, taskId);
            } else {
                log.warn("任务 {} 非死信推进未发生(不在 processing 集合, 幂等跳过), 不计 DEAD_LETTERED", taskId);
            }
        } catch (RuntimeException advanceFailure) {
            log.error("任务 {} 不可重试失败推进异常(批次内隔离, 继续处理后续任务; "
                            + "任务保持 PROCESSING 待重启恢复按状态重排): errorType={}, detail={}",
                    taskId, advanceFailure.getClass().getSimpleName(), advanceFailure.getMessage());
        }
    }

    /**
     * 处理结果指标隔离记录(10.5 review 二轮) — 指标异常只降级为告警,
     * 不让观测组件故障中断当前批次或覆盖已完成的权威状态迁移.
     */
    private void recordProcessedSafely(TaskMetrics.Source source, TaskMetrics.Outcome outcome) {
        try {
            taskMetricsOptional.ifPresent(metrics -> metrics.recordProcessed(source, outcome));
        } catch (RuntimeException e) {
            log.warn("处理结果指标记录失败(已降级, 不影响任务状态迁移): source={}, outcome={}, reason={}",
                    source, outcome, e.getMessage());
        }
    }

    /**
     * 重试指标隔离记录(10.5 review) — 指标异常只降级为告警,
     * 不让观测组件故障中断任务失败处理主流程(失败推进本身不能被指标拖垮).
     */
    private void recordRetryMetricSafely(TaskMetrics.RetryMetricOutcome outcome, String taskId) {
        try {
            taskMetricsOptional.ifPresent(metrics -> metrics.recordRetry(outcome));
        } catch (RuntimeException e) {
            log.warn("重试指标记录失败(已降级, 不影响失败处理): taskId={}, outcome={}, reason={}",
                    taskId, outcome, e.getMessage());
        }
    }

    /**
     * 处理单个任务 — 按 taskId 前缀路由到具体业务处理器 (Story 2.6 实施 + Story 4.4 github: 扩展).
     *
     * <p><b>路由策略 (Patch-3 配置驱动 + Story 4.4 github: 路由):</b>
     * <ul>
     *   <li>{@code processorProperties.getTaskIdPrefix() + ":"} 前缀 →
     *       {@link TwitterProcessor#process()} 编排完整 Pipeline
     *       (fetch → filter chain → rewrite → publish)</li>
     *   <li>{@code "github:"} 前缀 → {@link GitHubProcessor#process()} (Story 4.4 AC-7).
     *       常规 disabled 场景由 processor 内部输出 0-summary; 若 Bean 真未注册则记 warn 日志跳过, 不抛异常</li>
     *   <li>其他前缀 (未来 {@code "rss:"} / {@code "blog:"}) → 记 {@code log.warn} 跳过,
     *       前向兼容 Epic 5+ 扩展</li>
     * </ul>
     *
     * <p><b>路由失败异常策略 (沿用 {@link #processQueueOnce()} 分类):</b>
     * {@link TwitterProcessor#process()} / {@link GitHubProcessor#process()} 内部三层防御
     * 已捕获所有异常不会抛出, 但若因 Bean 装配问题抛出, 或
     * {@code processor.fault-isolation-enabled=false} 调试模式主动透传 per-article 异常,
     * 沿用 processQueueOnce catch Retryable/NonRetryable 策略
     * (Retryable 经 recordRetryableFailure 推进重试/死信, NonRetryable 移入死信终态).
     *
     * <p><b>Patch-3 修复动机:</b>
     * 早期实现硬编码 {@code taskId.startsWith("twitter:")}, 但 {@code TwitterProcessor}
     * 用 {@code properties.getTaskIdPrefix()} 生成 taskId, 改前缀后处理器产生的任务会被调度器
     * 视为"未知前缀"跳过, 违背 AC-6 与配置注释中"按 task-id-prefix 路由"的契约.
     * 注入 {@link ProcessorProperties} 让配置真正驱动路由行为.
     *
     * <p><b>Story 4.4 github: 前缀硬编码决策 (Task 4.4):</b> GitHubProcessor 暂不实现
     * 独立的 {@code processor.github.task-id-prefix} 配置 — github: 前缀硬编码于本方法,
     * 与 TwitterProcessor 的配置驱动 prefix 解耦. 触发 github 处理仅通过手动
     * {@code redis-cli RPUSH task:{queue}:pending "github:run:{yyyy-MM-dd}"} 或测试用例, 不实现自动 enqueue
     * (避免与 TwitterProcessor 共享 cron 时段冲突, 留 Epic 5 按需扩展).
     *
     * <p><b>Epic 5+ 扩展点:</b>
     * 引入 {@code Map<String, Processor>} 或 Spring 自动注入 {@code List<Processor>} +
     * {@code @Qualifier} 替换 if-else, 支持多 Processor 路由.
     */
    private TaskMetrics.Outcome processTask(String taskId, TaskRoute route) {
        log.info("处理任务: taskId={}", taskId);
        if (taskId.isBlank()) {
            log.warn("taskId 为空, 跳过");
            return TaskMetrics.Outcome.SKIPPED;
        }
        if (route == TaskRoute.TWITTER) {
            twitterProcessor.process();
            return TaskMetrics.Outcome.SUCCESS;
        }
        if (route == TaskRoute.GITHUB) {
            if (githubProcessorOptional.isPresent()) {
                githubProcessorOptional.get().process();
                return TaskMetrics.Outcome.SUCCESS;
            } else {
                log.warn("github: 任务到达但 GitHubProcessor 未注册 (github.enabled=false), 跳过: taskId={}", taskId);
            }
            return TaskMetrics.Outcome.SKIPPED;
        }
        String prefix = processorProperties.getTaskIdPrefix() + ":";
        log.warn("未知 taskId 前缀, 跳过 (Epic 5+ 其他前缀待扩展): taskId={}, expectedPrefix={}",
                taskId, prefix);
        return TaskMetrics.Outcome.SKIPPED;
    }

    private TaskRoute routeOf(String taskId) {
        if (taskId != null && taskId.startsWith(processorProperties.getTaskIdPrefix() + ":")) {
            return TaskRoute.TWITTER;
        }
        if (taskId != null && taskId.startsWith("github:")) {
            return TaskRoute.GITHUB;
        }
        return TaskRoute.UNKNOWN;
    }

    /**
     * 提取脱敏错误码(state Hash {@code lastErrorCode} 字段来源) —
     * 优先异常携带的 {@link ErrorCode} 枚举名, 缺失时退化为异常类 simpleName。
     * 禁止堆栈/消息原文入库(N4); 最终写库前仍会过 {@code sanitizeStateText} 净化。
     */
    private String errorCodeOf(RetryableException e) {
        ErrorCode code = e.getErrorCode();
        if (code != null) {
            return code.name();
        }
        return e.getClass().getSimpleName();
    }

    private String errorCodeOf(AggregatorException e) {
        ErrorCode code = e.getErrorCode();
        if (code != null) {
            return code.name();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 白名单化错误摘要(state Hash {@code lastErrorSummary} 字段来源, 10.5 review F-R5) —
     * 只保留 errorCode 与消息长度, 不持久化异常原文: 消息可能携带第三方 API 响应体
     * (URL/账号标识等潜在敏感信息), 入库违反 N4 脱敏纪律。
     */
    private String safeErrorSummary(AggregatorException e) {
        String message = e.getMessage();
        return errorCodeOf(e) + " len=" + (message == null ? 0 : message.length());
    }

    private enum TaskRoute {
        TWITTER(TaskMetrics.Source.TWITTER),
        GITHUB(TaskMetrics.Source.GITHUB),
        UNKNOWN(TaskMetrics.Source.UNKNOWN);

        private final TaskMetrics.Source source;

        TaskRoute(TaskMetrics.Source source) {
            this.source = source;
        }

        TaskMetrics.Source source() {
            return source;
        }
    }

    private boolean isBudgetHalted() {
        if (costMonitorOptional.isEmpty()) {
            return false;
        }
        try {
            return costMonitorOptional.get().refreshAndCheckProcessingHalted(YearMonth.now());
        } catch (RuntimeException e) {
            log.warn("读取成本预算 gate 失败, 继续本次自动处理: errorType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private void enqueueRunTaskIfAbsent(String trigger) {
        String runTaskId = batchRunTaskId();
        if (taskQueue.getProcessingTasks().contains(runTaskId)) {
            log.info("批量任务已在 processing 中, 跳过重复入队: trigger={}, taskId={}", trigger, runTaskId);
            return;
        }
        if (taskQueue.isQueued(runTaskId)) {
            log.info("批量任务已在队列中, 跳过重复入队: trigger={}, taskId={}", trigger, runTaskId);
            return;
        }
        taskQueue.push(runTaskId);
        log.info("已推送批量任务: trigger={}, taskId={}", trigger, runTaskId);
    }

    /**
     * 当日批量运行任务实例 ID(10.5 review 任务身份决策) —
     * {@code <prefix>:run:{yyyy-MM-dd}}(按注入 {@link Clock} 取当前日期).
     *
     * <p>同日多次触发(cron/startup)生成同一确定性 ID, 兼作逻辑作业去重键:
     * {@code isQueued}/{@code processing} 守卫与 {@code push} 脚本幂等守卫双保险,
     * 同日不会重复入队。跨日自然产生新实例 ID — 消除固定 {@code :run} ID 的两大缺陷:
     * 一次死信即永久阻断后续调度(只能人工补跑), 以及 attempt 跨批次累计导致
     * 早于 {@code max-attempts} 误判耗尽。
     */
    private String batchRunTaskId() {
        return processorProperties.getTaskIdPrefix() + ":run:" + LocalDate.now(clock);
    }
}
