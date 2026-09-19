package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 断点恢复业务 Bean — 把 {@code task:processing} 集合中的未完成任务重新入队到
 * {@code task:queue}(Story 1.6 AC-6).
 *
 * <p><b>触发时机:</b> 由 {@link com.choucj.aiaggregator.task.scheduler.ContentScheduler#onStartup()}
 * 在应用启动时显式调用 — <b>本类不再独立监听 {@code ApplicationReadyEvent}</b>.
 *
 * <p><b>CR W1 修复(2026-06-27):</b> 原 Story 1.6 实现把 {@code @EventListener(ApplicationReadyEvent.class)}
 * 放在本类上, 与 {@code ContentScheduler.onStartup()} 形成双监听器. Spring 不保证两 listener 顺序,
 * 若 {@code ContentScheduler} 先 fire, {@code processContent()} 会向 {@code task:processing} 集合写入
 * 正在处理的任务, 随后本 listener 又把所有 processing 成员当作"未完成"重入队并清空集合,
 * 导致任务被重复执行. 改为由 {@code ContentScheduler} 独占编排 "先 recovery, 后 processContent"
 * 顺序, 本类降级为纯业务 Bean.
 *
 * <p><b>异常容忍:</b> 调用方({@code ContentScheduler.onStartup})对 {@link com.choucj.aiaggregator.common.exception.RetryableException}
 * 与任意 {@link Exception} 都做了 try/catch, Redis 不可用不阻塞应用启动.
 *
 * <p>引用源: Story 1.6 创建;CR W1 修复(2026-06-27).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskRecoveryRunner {

    private final TaskQueue taskQueue;
    private final StringRedisTemplate stringRedisTemplate;
    private final SlowOperationRecorder slowOperationRecorder;

    /**
     * 断点恢复核心逻辑 — 把 {@code task:processing} 中的任务重新入队, 然后清空集合.
     *
     * <p>步骤:
     * <ol>
     *   <li>{@code getProcessingTasks()} 拿到所有未完成任务</li>
     *   <li>对每个 taskId 调用 {@code push(taskId)} 加入 {@code task:queue} 右端</li>
     *   <li>{@code delete(task:processing)} 清空集合(避免下次启动重复入队)</li>
     * </ol>
     *
     * <p>异常策略: {@link com.choucj.aiaggregator.common.exception.RetryableException} 与
     * 其他 {@link Exception} 均向上抛, 由调用方决定是否吞掉.
     */
    public void recoverPendingTasks() {
        Set<String> pending = taskQueue.getProcessingTasks();
        if (pending.isEmpty()) {
            log.info("断点恢复: 无未完成任务");
            return;
        }
        log.info("断点恢复: 发现 {} 个未完成任务, 重新入队", pending.size());
        for (String taskId : pending) {
            taskQueue.push(taskId);
            log.debug("任务 {} 重新入队", taskId);
        }
        slowOperationRecorder.observe(Kind.REDIS, Dependency.REDIS,
                Operation.RECOVERY,
                () -> stringRedisTemplate.delete(RedisKeys.taskProcessing()));
        log.info("断点恢复完成, task:processing 已清空");
    }
}
