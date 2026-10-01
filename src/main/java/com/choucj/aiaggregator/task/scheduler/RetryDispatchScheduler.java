package com.choucj.aiaggregator.task.scheduler;

import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 重试到期重投调度器(Story 10.5 AC2) — 周期扫描 retry ZSET 中 dueAt 已到期的任务,
 * 经 {@link TaskQueue#dispatchDueRetries()} 原子转回 pending 队列.
 *
 * <p><b>触发机制:</b> {@code @Scheduled(fixedDelayString)} — 上轮扫描结束后再计时,
 * 间隔由 {@code task.retry.dispatch-interval-ms} 配置(默认 60s)。
 *
 * <p><b>A7 边界:</b> 本类<b>不监听</b> {@code ApplicationReadyEvent} —
 * {@code ContentScheduler.onStartup()} 是全项目唯一启动监听器。
 * 重启后到期的重试任务由第一个 fixedDelay tick 自然处理: retry ZSET 成员持久、
 * 无 TTL、不随重启丢失(AC3 "重启恢复不重新投递延迟重试任务" — 恢复只重排
 * processing 集合中 PROCESSING 状态的成员, 不触碰 retry ZSET)。
 *
 * <p><b>软失败容错:</b> 扫描异常({@link RetryableException} 等)被 catch 记 warn 不抛出,
 * 调度器存活, 下个 tick 继续 — 单次扫描失败不应杀掉调度器(沿用 ContentScheduler 容错模式)。
 * Mac 睡眠等环境暂停期间错过的 tick 无需补偿: ZSET 中到期任务会在唤醒后第一个 tick 补投,
 * 语义为"延迟但不丢失"。
 *
 * <p><b>消费模型不变:</b> 重投只把任务放回 pending; 消费仍由既有 cron/启动流程驱动。
 *
 * <p>引用源: Story 10.5 创建(2026-09-22)。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "task.retry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RetryDispatchScheduler {

    private final TaskQueue taskQueue;
    private final Optional<TaskMetrics> taskMetricsOptional;

    /**
     * 构造器注入 — {@link Optional<TaskMetrics>} 沿用 ContentScheduler 容错注入模式,
     * 指标缺失不影响重投语义(Story 10.5 Task 7.2 只观测契约)。
     */
    @Autowired
    public RetryDispatchScheduler(TaskQueue taskQueue,
                                  Optional<TaskMetrics> taskMetricsOptional) {
        this.taskQueue = taskQueue;
        this.taskMetricsOptional = taskMetricsOptional;
    }

    /**
     * 到期重投扫描 — 把 retry ZSET 中 dueAt 已到期的任务原子转回 pending.
     *
     * <p>重投数 > 0 时记 info 并累计 {@code aiaggregator.task.retry{outcome=dispatched}};
     * 异常只 warn 不抛, 保证调度器存活.
     *
     * <p><b>主操作与指标拆分独立 try(10.5 review F-R3):</b> 原实现把
     * {@code dispatchDueRetries} 与指标记录包在同一个 try 中 — 若重投成功但指标抛出,
     * 同一 catch 会把已成功的重投吞成失败告警, 观测故障反向污染主流程语义。
     * 现在指标在独立 try 内隔离, 异常只降级 warn。
     */
    @Scheduled(fixedDelayString = "${task.retry.dispatch-interval-ms:60000}")
    public void dispatchDueRetries() {
        CorrelationContext.begin(null);
        try {
            int dispatched;
            try {
                dispatched = taskQueue.dispatchDueRetries();
            } catch (Exception e) {
                log.warn("重试到期重投扫描失败, 等待下个调度周期(调度器存活)", e);
                return;
            }
            if (dispatched <= 0) {
                return;
            }
            log.info("重试到期重投: {} 个任务已转回 pending", dispatched);
            try {
                taskMetricsOptional.ifPresent(metrics ->
                        metrics.recordRetry(TaskMetrics.RetryMetricOutcome.DISPATCHED, dispatched));
            } catch (RuntimeException e) {
                log.warn("重投指标记录失败(已降级, 不影响重投结果): dispatched={}, reason={}",
                        dispatched, e.getMessage());
            }
        } finally {
            CorrelationContext.end();
        }
    }
}
