package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
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

import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 内容处理调度器 — 基于 Spring {@code @Scheduled} + Redis 任务队列(Story 1.6).
 *
 * <p><b>触发机制:</b>
 * <ul>
 *   <li>{@code @Scheduled(cron = "${schedule.cron:0 0 * * * ?}")} — 默认每小时整点触发</li>
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
 * <p><b>CR W2 修复(2026-06-27):</b> 注入 {@code schedule.run-on-startup}(默认 {@code true}),
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
    private final boolean runOnStartup;

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
     * @param runOnStartup             启动时是否执行首次处理, 默认 true (来自 {@code schedule.run-on-startup})
     */
    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            TwitterProcessor twitterProcessor,
                            Optional<GitHubProcessor> githubProcessorOptional,
                            ProcessorProperties processorProperties,
                            @Value("${schedule.run-on-startup:true}") boolean runOnStartup) {
        this(taskQueue, recoveryRunner, twitterProcessor, githubProcessorOptional, processorProperties,
                Optional.empty(), runOnStartup);
    }

    @Autowired
    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            TwitterProcessor twitterProcessor,
                            Optional<GitHubProcessor> githubProcessorOptional,
                            ProcessorProperties processorProperties,
                            Optional<CostMonitor> costMonitorOptional,
                            @Value("${schedule.run-on-startup:true}") boolean runOnStartup) {
        this.taskQueue = taskQueue;
        this.recoveryRunner = recoveryRunner;
        this.twitterProcessor = twitterProcessor;
        this.githubProcessorOptional = githubProcessorOptional;
        this.processorProperties = processorProperties;
        this.costMonitorOptional = costMonitorOptional;
        this.runOnStartup = runOnStartup;
    }

    /**
     * 每小时整点触发(默认),可通过 {@code schedule.cron} 配置覆盖.
     *
     * <p>失败容错: 任意异常被 catch 记 error 日志, 不抛出, 保证调度器存活.
     */
    @Scheduled(cron = "${schedule.cron:0 0 * * * ?}")
    public void processContent() {
        log.info("开始执行内容处理任务");
        try {
            if (isBudgetHalted()) {
                log.error("成本预算已停机, 跳过本次自动内容处理");
                return;
            }
            enqueueRunTaskIfAbsent("cron");
            processQueueOnce();
            log.info("内容处理任务完成");
        } catch (Exception e) {
            log.error("内容处理任务失败(调度器存活, 等待下次 cron 触发)", e);
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
        log.info("应用启动完成, 触发首次内容处理");
        try {
            recoveryRunner.recoverPendingTasks();
        } catch (Exception e) {
            log.warn("启动时断点恢复失败, 继续触发首次内容处理", e);
        }
        processContent();
    }

    /**
     * 取出队列中所有当前可处理的任务并执行.
     *
     * <p>MVP 实现: 单线程顺序处理. 多线程/并发处理留 Story 1.7 Feature Flags 控制.
     *
     * <p>异常策略:
     * <ul>
     *   <li>{@link RetryableException} — 任务失败可重试, <b>不调用 complete</b>,
     *       任务留在 {@code task:processing} 集合中, 下次启动时由 {@link TaskRecoveryRunner} 重入队</li>
     *   <li>{@link NonRetryableException} — 任务不可重试, 调用 complete 移除避免阻塞队列
     *       (任务终态 FAILED, 由业务侧记录死信)</li>
     * </ul>
     */
    private void processQueueOnce() {
        String taskId;
        while ((taskId = taskQueue.poll(0, TimeUnit.SECONDS)) != null) {
            try {
                processTask(taskId);
                taskQueue.complete(taskId);
            } catch (RetryableException e) {
                log.warn("任务 {} 失败(可重试), 留在 processing 集合中待启动时重入队", taskId, e);
            } catch (NonRetryableException e) {
                log.error("任务 {} 失败(不可重试), 标记 complete 以避免阻塞队列", taskId, e);
                taskQueue.complete(taskId);
            }
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
     * (Retryable 留 processing 集合待重启重入队, NonRetryable complete 移除避免阻塞).
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
     * {@code redis-cli RPUSH task:queue "github:run"} 或测试用例, 不实现自动 enqueue
     * (避免与 TwitterProcessor 共享 cron 时段冲突, 留 Epic 5 按需扩展).
     *
     * <p><b>Epic 5+ 扩展点:</b>
     * 引入 {@code Map<String, Processor>} 或 Spring 自动注入 {@code List<Processor>} +
     * {@code @Qualifier} 替换 if-else, 支持多 Processor 路由.
     */
    private void processTask(String taskId) {
        log.info("处理任务: taskId={}", taskId);
        if (taskId == null || taskId.isBlank()) {
            log.warn("taskId 为空, 跳过");
            return;
        }
        String prefix = processorProperties.getTaskIdPrefix() + ":";
        if (taskId.startsWith(prefix)) {
            twitterProcessor.process();
            return;
        }
        if (taskId.startsWith("github:")) {
            if (githubProcessorOptional.isPresent()) {
                githubProcessorOptional.get().process();
            } else {
                log.warn("github: 任务到达但 GitHubProcessor 未注册 (github.enabled=false), 跳过: taskId={}", taskId);
            }
            return;
        }
        log.warn("未知 taskId 前缀, 跳过 (Epic 5+ 其他前缀待扩展): taskId={}, expectedPrefix={}",
                taskId, prefix);
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
        String runTaskId = processorProperties.getTaskIdPrefix() + ":run";
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
}
