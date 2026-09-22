package com.choucj.aiaggregator.task.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 断点恢复业务 Bean — 把 {@code task:{queue}:processing} 集合中状态为 PROCESSING 的未完成任务
 * 原子重排回 {@code task:{queue}:pending}(Story 1.6 AC-6 / Story 10.4 恢复边界改造).
 *
 * <p><b>触发时机:</b> 由 {@link com.choucj.aiaggregator.task.scheduler.ContentScheduler#onStartup()}
 * 在应用启动时显式调用 — <b>本类不监听 {@code ApplicationReadyEvent}</b>(A7:
 * {@code ContentScheduler.onStartup()} 是全项目唯一启动监听器).
 *
 * <p><b>Story 10.4 改造(AC4 调用边界):</b> 旧实现直接 {@code stringRedisTemplate.delete(task:processing)}
 * 删键 — 违反"业务代码不得直接删除任务键"边界且在重排与清空之间存在丢失窗口。现改为调用
 * {@link TaskQueue#recoverProcessingTasks()} 高层命令: 每个任务在同槽 Lua 内重新校验
 * {@code status==PROCESSING 且仍在 processing} 后原子 SREM + RPUSH + HSET QUEUED, 本类不再持有
 * {@code StringRedisTemplate}.
 *
 * <p><b>异常容忍:</b> 调用方({@code ContentScheduler.onStartup})对 {@link com.choucj.aiaggregator.common.exception.RetryableException}
 * 与任意 {@link Exception} 都做了 try/catch, Redis 不可用不阻塞应用启动.
 *
 * <p>引用源: Story 1.6 创建;CR W1 修复(2026-06-27);Story 10.4 高层命令化(2026-09-20).
 *
 * @see TaskQueue#recoverProcessingTasks()
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskRecoveryRunner {

    private final TaskQueue taskQueue;

    /**
     * 断点恢复核心逻辑 — 委托 {@link TaskQueue} 高层命令原子重排 PROCESSING 任务.
     *
     * <p>与旧实现不同, 不再有"先重排再删集合"的两步操作: 重排与状态翻转在同一 Lua 脚本内原子完成,
     * 且只重排状态仍为 PROCESSING 的成员(已完成/已死信的成员自动跳过).
     */
    public void recoverPendingTasks() {
        int recovered = taskQueue.recoverProcessingTasks();
        if (recovered == 0) {
            log.info("断点恢复: 无需要重排的任务");
            return;
        }
        log.info("断点恢复完成: {} 个任务重新入队", recovered);
    }
}
