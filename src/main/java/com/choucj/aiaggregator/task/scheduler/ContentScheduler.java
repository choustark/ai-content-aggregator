package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final boolean runOnStartup;

    /**
     * 构造器注入 {@code schedule.run-on-startup}(CR W2 修复).
     *
     * <p>不用 {@code @RequiredArgsConstructor} 是因为 boolean 配置项需要
     * {@code @Value} 显式标注 + 默认值, Lombok 自动生成的构造器无法表达.
     * 其他依赖({@link TaskQueue} / {@link TaskRecoveryRunner})仍走 Spring 自动注入.
     *
     * @param taskQueue       任务队列
     * @param recoveryRunner  断点恢复 Bean
     * @param runOnStartup    启动时是否执行首次处理, 默认 true (来自 {@code schedule.run-on-startup})
     */
    public ContentScheduler(TaskQueue taskQueue,
                            TaskRecoveryRunner recoveryRunner,
                            @Value("${schedule.run-on-startup:true}") boolean runOnStartup) {
        this.taskQueue = taskQueue;
        this.recoveryRunner = recoveryRunner;
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
     * 处理单个任务 — MVP 占位实现, Story 2.x 路由到具体业务处理器.
     *
     * <p>Story 2.6 Pipeline Integration 会注入 {@code ContentProcessor} 替换本方法,
     * 按 taskId 前缀路由到 RSSHUB / Twitter / LLM 处理器.
     */
    private void processTask(String taskId) {
        log.info("处理任务: {}", taskId);
        // TODO(2026-07) Story 2.6: 注入 ContentProcessor 路由到具体业务处理器
    }
}
