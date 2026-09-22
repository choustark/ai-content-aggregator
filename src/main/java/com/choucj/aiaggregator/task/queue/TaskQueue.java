package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 任务队列 — 基于 Redis List + Set + Lua 原子脚本实现,支持断点恢复与原子领取(Story 1.6 / 10.4).
 *
 * <p><b>数据结构(Story 10.4 同槽键族, 全部共享 {@code {queue}} hash tag → 同一 Cluster slot):</b>
 * <ul>
 *   <li>{@code task:{queue}:pending} (Redis List) — FIFO 待处理队列,业务侧 {@code push} 入队右端,
 *       {@code poll} 经 CLAIM 脚本从左端原子领取</li>
 *   <li>{@code task:{queue}:processing} (Redis Set) — 已领取未完成的任务集合</li>
 *   <li>{@code task:{queue}:retry} (Redis ZSET) — 10.5 重试调度占位</li>
 *   <li>{@code task:{queue}:dead-letter} (Redis Set) — 死信任务集合, 审计详情在 state Hash</li>
 *   <li>{@code task:{queue}:state:&lt;taskId&gt;} (Redis Hash) — 任务状态记录
 *       ({@code taskId/status/updatedAt} 起, AD-9 状态机)</li>
 *   <li>{@code task:{queue}:legacy-migrated} (Redis Set) — 迁移幂等账本, 仅迁移路径使用</li>
 * </ul>
 *
 * <p><b>原子领取(Story 10.4, 关闭 10.3 review deferred 的 at-most-once 丢失窗口):</b>
 * {@link #poll(long, TimeUnit)} 先只读预览队首得到候选 taskId, 再执行单个 Lua CLAIM 脚本原子完成
 * {@code LPOP pending → SADD processing → HSET state PROCESSING}。所有传入 Lua 的键位于同一 slot,
 * 领取瞬间崩溃也不会在"弹出"与"登记"之间丢失任务。
 *
 * <p><b>调用边界(AC4):</b> 调度、恢复与业务模块只能调用本类高层命令
 * ({@code push/poll/complete/markDeadLetter/recoverProcessingTasks/migrateLegacy*}),
 * 不得直接操作 LPOP/SADD/SREM 或删除任务键。
 *
 * <p><b>异常映射(沿用 Story 1.5b {@code supplyWithMapping} 模式):</b> 所有 Redis 路径经
 * {@link SlowOperationRecorder} 观测 + 异常映射(W1+W2), 业务侧拿到的是
 * {@link RetryableException} / {@link NonRetryableException}。
 *
 * <p><b>Lua 参数约定(gotcha #13):</b> args 全部传 {@link String}(StringRedisSerializer 内部强转),
 * 脚本 resultType 收敛 {@code Long.class}。所有脚本在首个写操作前校验目标键 TYPE(2.2a),
 * 类型不符返回 {@code -1} 拒绝而不修改任何键。
 *
 * <p>引用源: Story 1.6 创建;Story 10.4 原子领取改造(2026-09-20)。
 *
 * @see TaskRecoveryRunner
 * @see TaskKeyMigrator
 * @see com.choucj.aiaggregator.task.scheduler.ContentScheduler
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskQueue {

    private final StringRedisTemplate stringRedisTemplate;
    private final SlowOperationRecorder slowOperationRecorder;

    /** CLAIM 脚本返回码: 队列为空(预读候选与队首不一致的竞态兜底). */
    private static final long CLAIM_RESULT_EMPTY = 0L;
    /** CLAIM 脚本返回码: 领取成功. */
    private static final long CLAIM_RESULT_CLAIMED = 1L;
    /** CLAIM 脚本返回码: 队首竞争(预读后被其他消费者抢先). */
    private static final long CLAIM_RESULT_RACE = 2L;
    /** PUSH 脚本返回码: taskId 已在 pending 队列, 幂等跳过(不重复入队). */
    private static final long PUSH_RESULT_ALREADY_QUEUED = 0L;
    /** PUSH 脚本返回码: taskId 在途(processing 中), 拒绝覆盖在途状态. */
    private static final long PUSH_RESULT_IN_FLIGHT = 2L;
    /** PUSH 脚本返回码: taskId 已进入死信终态, 普通入队不得自动复活. */
    private static final long PUSH_RESULT_DEAD_LETTER = 3L;
    /** 所有脚本共用返回码: 键 TYPE 前置校验拒绝, 未修改任何键(2.2a). */
    private static final long SCRIPT_RESULT_TYPE_REJECTED = -1L;

    /** poll 阻塞等待模式下队首竞争的短暂停顿(避免忙等打满 CPU). */
    private static final long CLAIM_RACE_PAUSE_MS = 50L;

    /**
     * CLAIM 脚本(3 键同 slot): KEYS=[pending, processing, state:candidate], ARGV=[candidate, now].
     *
     * <p>复核队首仍为候选后 LPOP → SADD → HSET PROCESSING, 返回 0=空 / 1=领取成功 / 2=队首竞争 /
     * -1=TYPE 校验拒绝。
     */
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local pendingType = redis.call('TYPE', KEYS[1])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local processingType = redis.call('TYPE', KEYS[2])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[3])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local head = redis.call('LINDEX', KEYS[1], 0)
            if not head then return 0 end
            if head ~= ARGV[1] then return 2 end
            redis.call('LPOP', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3], 'taskId', ARGV[1], 'status', 'PROCESSING', 'updatedAt', ARGV[2])
            return 1
            """, Long.class);

    /**
     * PUSH 脚本(4 键): KEYS=[pending, processing, dead-letter, state], ARGV=[taskId, now].
     *
     * <p>入队前守卫队列一致性不变式(10.4 review 修复): taskId 已在 processing(在途)返回 2、
     * 已在 dead-letter 返回 3、已在 pending 返回 0 — 均不写任何键, 防止把在途任务或死信终态
     * 的 state 覆盖成 QUEUED
     * (否则恢复脚本只认 PROCESSING, 任务会悬挂在 processing 永不重排)以及同一任务在
     * pending 中重复入队被消费两次。返回 1=入队成功 / 0=已在队列 / 2=在途 / -1=拒绝。
     */
    private static final DefaultRedisScript<Long> PUSH_SCRIPT = new DefaultRedisScript<>("""
            local pendingType = redis.call('TYPE', KEYS[1])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local processingType = redis.call('TYPE', KEYS[2])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[3])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[4])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return 3 end
            if redis.call('LPOS', KEYS[1], ARGV[1]) then return 0 end
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[4], 'taskId', ARGV[1], 'status', 'QUEUED', 'updatedAt', ARGV[2])
            return 1
            """, Long.class);

    /**
     * COMPLETE 脚本(2 键): KEYS=[processing, state], ARGV=[taskId, now].
     *
     * <p>仅在确实从 processing 移除时写 COMPLETED 终态, 重复完成不覆盖后续状态。返回 SREM 移除数 /
     * -1=拒绝。
     */
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[2])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local removed = redis.call('SREM', KEYS[1], ARGV[1])
            if removed == 1 then
                redis.call('HSET', KEYS[2], 'status', 'COMPLETED', 'updatedAt', ARGV[2])
            end
            return removed
            """, Long.class);

    /**
     * DEAD_LETTER 脚本(3 键): KEYS=[processing, dead-letter, state], ARGV=[taskId, now, reason].
     *
     * <p>仅在确实从 processing 移除时入死信集合并写 DEAD_LETTER 终态(最小记录: taskId/时间/脱敏原因,
     * 完整失败审计归 10.5)。返回 1=移入死信 / 0=不在 processing / -1=拒绝。
     */
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[2])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[3])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local removed = redis.call('SREM', KEYS[1], ARGV[1])
            if removed == 0 then return 0 end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3], 'taskId', ARGV[1], 'status', 'DEAD_LETTER',
                'updatedAt', ARGV[2], 'deadLetteredAt', ARGV[2], 'lastErrorSummary', ARGV[3])
            return 1
            """, Long.class);

    /**
     * RECOVER 脚本(3 键): KEYS=[processing, pending, state], ARGV=[taskId, now].
     *
     * <p>同槽内重新校验 taskId 仍在 processing 且 status==PROCESSING, 才原子
     * SREM → RPUSH → HSET QUEUED, 避免与完成/死信并发时覆盖终态。返回 1=重排 / 0=跳过 / -1=拒绝。
     */
    private static final DefaultRedisScript<Long> RECOVER_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local pendingType = redis.call('TYPE', KEYS[2])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local stateType = redis.call('TYPE', KEYS[3])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then return 0 end
            local status = redis.call('HGET', KEYS[3], 'status')
            if not status or status ~= 'PROCESSING' then return 0 end
            redis.call('SREM', KEYS[1], ARGV[1])
            redis.call('RPUSH', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3], 'status', 'QUEUED', 'updatedAt', ARGV[2])
            return 1
            """, Long.class);

    /**
     * MIGRATE_PROCESSING 脚本(3 键): KEYS=[processing(新), state, legacy-migrated], ARGV=[taskId, now].
     *
     * <p>账本未标记且新 state 不存在才迁入: SADD processing + HSET PROCESSING + 打标, 返回 1=迁移 /
     * 0=已标记跳过 / 2=state 冲突(拒绝覆盖, 交运维核对) / -1=拒绝。
     */
    private static final DefaultRedisScript<Long> MIGRATE_PROCESSING_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[2])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local ledgerType = redis.call('TYPE', KEYS[3])['ok']
            if ledgerType ~= 'none' and ledgerType ~= 'set' then return -1 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[2], 'taskId', ARGV[1], 'status', 'PROCESSING', 'updatedAt', ARGV[2])
            redis.call('SADD', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    /**
     * MIGRATE_PENDING 脚本(3 键): KEYS=[pending(新), state, legacy-migrated], ARGV=[taskId, now].
     *
     * <p>账本语义同 MIGRATE_PROCESSING, 迁入目标为 pending 队列 + QUEUED 状态。
     */
    private static final DefaultRedisScript<Long> MIGRATE_PENDING_SCRIPT = new DefaultRedisScript<>("""
            local pendingType = redis.call('TYPE', KEYS[1])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local stateType = redis.call('TYPE', KEYS[2])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local ledgerType = redis.call('TYPE', KEYS[3])['ok']
            if ledgerType ~= 'none' and ledgerType ~= 'set' then return -1 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[2], 'taskId', ARGV[1], 'status', 'QUEUED', 'updatedAt', ARGV[2])
            redis.call('SADD', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    /**
     * 添加任务到队列右端(FIFO 入队), 并原子写入 QUEUED 状态记录.
     *
     * <p>幂等守卫(10.4 review 修复): taskId 已在 processing(在途)或已在 pending 时跳过写入；
     * 已在 dead-letter 时拒绝普通入队，必须由 Story 10.5 的显式人工补跑命令处理。
     * 在途任务被覆盖成 QUEUED 会破坏"processing 成员必为 PROCESSING"不变式，
     * 死信被普通 cron 覆盖则会绕过 AD-5 的人工补跑边界。调用方无须前置去重(如
     * {@code ContentScheduler.enqueueRunTaskIfAbsent} 的检查与本守卫互为双保险)。
     *
     * <p>依赖 Redis 6.2+ 的 {@code LPOS} 做队列内重复检测(redis-stack 7.x 满足)。
     *
     * @param taskId 任务 ID(业务侧生成,如 {@code "twitter:run"})
     * @throws NonRetryableException taskId 为 null / 键 TYPE 校验拒绝
     * @throws RetryableException    Redis 连接异常(可重试)
     */
    public void push(String taskId) {
        if (taskId == null) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "push 失败: taskId 不能为 null");
        }
        Long result = executeScript("push", RedisKeys.taskPending(), Operation.ENQUEUE, PUSH_SCRIPT,
                List.of(RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                        RedisKeys.taskDeadLetter(),
                        RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString());
        if (result != null && result == PUSH_RESULT_ALREADY_QUEUED) {
            log.info("任务 {} 已在 pending 队列, 跳过重复入队", taskId);
        } else if (result != null && result == PUSH_RESULT_IN_FLIGHT) {
            log.info("任务 {} 正在 processing 中, 跳过重复入队(不覆盖在途状态)", taskId);
        } else if (result != null && result == PUSH_RESULT_DEAD_LETTER) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "push 失败: 任务已在 dead-letter, 仅允许显式人工补跑: taskId=" + taskId);
        } else {
            log.debug("任务 {} 加入队列", taskId);
        }
    }

    /**
     * 从队列左端取出任务(FIFO 出队), 经 CLAIM 脚本原子登记 PROCESSING.
     *
     * <p>流程: 只读预览队首得候选 → CLAIM 脚本复核队首并原子 LPOP + SADD + HSET。
     * 预读与登记之间不再有丢失窗口(10.3 deferred 项由本实现关闭)。
     * {@code timeout<=0} 非阻塞 — 空队列或队首竞争立即返回/重试;
     * {@code timeout>0} 阻塞等待语义(脚本内禁止 BLPOP)— 空队列、预读竞态清空与队首竞争
     * 三种瞬态都在 Java 侧截止时间内重试, 期间一旦有任务入队即领取, <b>不会</b>因观察瞬间
     * 为空而提前返回 null(10.4 review 修复)。
     *
     * @param timeout 阻塞超时(0 表示非阻塞立即返回)
     * @param unit    时间单位
     * @return 任务 ID;队列空且超时返回 null
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝(数据损坏, 需运维介入)
     */
    public String poll(long timeout, TimeUnit unit) {
        boolean blocking = timeout > 0;
        Duration expectedWait = blocking
                ? Duration.ofNanos(unit.toNanos(timeout)) : Duration.ZERO;
        long deadlineNanos = blocking ? System.nanoTime() + unit.toNanos(timeout) : 0L;
        while (true) {
            String candidate = supplyWithMapping("poll-preview", RedisKeys.taskPending(),
                    Operation.POLL, expectedWait,
                    () -> stringRedisTemplate.opsForList().index(RedisKeys.taskPending(), 0));
            if (candidate == null) {
                if (!blocking || System.nanoTime() >= deadlineNanos) {
                    return null;
                }
                pauseBeforeRaceRetry();
                continue;
            }
            Long result = supplyWithMapping("poll-claim", RedisKeys.taskPending(),
                    Operation.POLL, expectedWait,
                    () -> executeScriptInternal(CLAIM_SCRIPT,
                            List.of(RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                                    RedisKeys.taskState(candidate)),
                            candidate, Instant.now().toString()));
            if (result != null && result == CLAIM_RESULT_CLAIMED) {
                log.debug("任务 {} 原子领取开始处理", candidate);
                return candidate;
            }
            if (result == null || result == CLAIM_RESULT_EMPTY) {
                // 预读到候选但 CLAIM 判空(候选被并发移除的竞态): 阻塞模式下继续等待,
                // 不能把瞬态空当作整个超时窗口为空
                if (!blocking || System.nanoTime() >= deadlineNanos) {
                    return null;
                }
                pauseBeforeRaceRetry();
                continue;
            }
            if (result != CLAIM_RESULT_RACE) {
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "poll 失败(键 TYPE 前置校验拒绝, 疑似数据损坏): key="
                                + RedisKeys.taskState(candidate));
            }
            // 队首竞争: 非阻塞立即重试; 阻塞模式重试直至成功或截止时间到
            if (!blocking) {
                continue;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return null;
            }
            pauseBeforeRaceRetry();
        }
    }

    /**
     * 标记任务完成, 原子从 processing 集合移除并写 COMPLETED 终态.
     *
     * <p>幂等: taskId 不在集合中时不报错, 且不覆盖可能已存在的后续状态。
     *
     * @param taskId 任务 ID
     * @throws RetryableException Redis 连接异常
     */
    public void complete(String taskId) {
        Long removed = executeScript("complete", RedisKeys.taskProcessing(), Operation.REMOVE,
                COMPLETE_SCRIPT,
                List.of(RedisKeys.taskProcessing(), RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString());
        log.debug("任务 {} 处理完成(removed={})", taskId, removed);
    }

    /**
     * 把不可重试失败任务移入死信终态(Story 10.4 最小记录).
     *
     * <p>原子完成: 从 processing 移除 + 入 dead-letter 集合 + state 置
     * {@code DEAD_LETTER}(含 taskId/时间/脱敏原因), 避免失败任务被误标 COMPLETED。
     * attempt/错误摘要/重放语义由 10.5 扩展。
     *
     * @param taskId 任务 ID
     * @param reason 脱敏失败原因(写入前会再做换行清洗与截断)
     * @return {@code true} 表示已移入死信;{@code false} 表示任务不在 processing(跳过)
     * @throws RetryableException Redis 连接异常
     */
    public boolean markDeadLetter(String taskId, String reason) {
        Long result = executeScript("dead-letter", RedisKeys.taskDeadLetter(), Operation.REMOVE,
                DEAD_LETTER_SCRIPT,
                List.of(RedisKeys.taskProcessing(), RedisKeys.taskDeadLetter(),
                        RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString(), sanitizeStateText(reason));
        boolean moved = result != null && result == 1L;
        if (moved) {
            log.warn("任务 {} 移入死信(dead-letter), 状态置 DEAD_LETTER", taskId);
        } else {
            log.info("任务 {} 不在 processing 集合, 跳过死信标记(result={})", taskId, result);
        }
        return moved;
    }

    /**
     * 返回 processing 集合全部成员(断点恢复用).
     *
     * @return 任务 ID 集合;空集合返回 {@link Collections#emptySet()}(非 null)
     * @throws RetryableException Redis 连接异常
     */
    public Set<String> getProcessingTasks() {
        Set<String> tasks = supplyWithMapping("getProcessingTasks", RedisKeys.taskProcessing(),
                Operation.GET, () ->
                        stringRedisTemplate.opsForSet().members(RedisKeys.taskProcessing()));
        return tasks != null ? tasks : Collections.emptySet();
    }

    /**
     * 判断待处理队列中是否已存在指定 taskId.
     *
     * <p>用于调度触发器避免把相同批量任务重复压入 pending 队列.
     *
     * @return {@code true} 表示已存在; Redis 返回 {@code null} 时按空队列处理
     * @throws RetryableException Redis 连接异常
     */
    public boolean isQueued(String taskId) {
        if (taskId == null) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "isQueued 失败: taskId 不能为 null");
        }
        List<String> tasks = supplyWithMapping("isQueued", RedisKeys.taskPending(), Operation.GET, () ->
                stringRedisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1));
        return tasks != null && tasks.contains(taskId);
    }

    /** 返回当前待处理队列长度, 供监控等只读调用方使用(TaskMetrics 依赖, 语义不变). */
    public long pendingCount() {
        Long count = supplyWithMapping("pendingCount", RedisKeys.taskPending(), Operation.GET, () ->
                stringRedisTemplate.opsForList().size(RedisKeys.taskPending()));
        return count != null ? count : 0L;
    }

    /** 返回当前 processing 集合大小, 供监控等只读调用方使用(TaskMetrics 依赖, 语义不变). */
    public long processingCount() {
        Long count = supplyWithMapping("processingCount", RedisKeys.taskProcessing(), Operation.GET, () ->
                stringRedisTemplate.opsForSet().size(RedisKeys.taskProcessing()));
        return count != null ? count : 0L;
    }

    /**
     * 断点恢复高层命令 — 把 processing 中状态为 PROCESSING 的任务原子重排回 pending 队列.
     *
     * <p>每个成员在同一个同槽 Lua 内重新校验 {@code status==PROCESSING 且仍在 processing},
     * 才原子 SREM + RPUSH + HSET QUEUED; 非 PROCESSING 成员(如已完成/已死信)跳过并 WARN,
     * 避免与完成/死信并发时覆盖终态(AC3/AC4)。
     *
     * @return 实际重排(重新入队)的任务数量
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝
     */
    public int recoverProcessingTasks() {
        Set<String> processingTasks = getProcessingTasks();
        if (processingTasks.isEmpty()) {
            log.info("断点恢复: 无未完成任务");
            return 0;
        }
        log.info("断点恢复: 发现 {} 个未完成任务, 逐个原子重排", processingTasks.size());
        int recovered = 0;
        for (String taskId : processingTasks) {
            Long result = executeScript("recover", RedisKeys.taskProcessing(), Operation.RECOVERY,
                    RECOVER_SCRIPT,
                    List.of(RedisKeys.taskProcessing(), RedisKeys.taskPending(),
                            RedisKeys.taskState(taskId)),
                    taskId, Instant.now().toString());
            if (result != null && result == 1L) {
                recovered++;
            } else {
                log.warn("断点恢复跳过非 PROCESSING 任务: taskId={}, result={}", taskId, result);
            }
        }
        log.info("断点恢复完成: 已重排 {}/{} 个任务", recovered, processingTasks.size());
        return recovered;
    }

    /**
     * 迁移高层命令 — 把旧 task:processing 中的单个任务迁入新键族(账本幂等).
     *
     * <p>仅 {@link TaskKeyMigrator} 调用; 只写新键, 不触碰旧键。
     *
     * @param taskId 旧 processing 集合中的任务 ID
     * @return 1=已迁移 / 0=账本已标记跳过 / 2=新 state 冲突(拒绝覆盖)
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝
     */
    public int migrateLegacyProcessing(String taskId) {
        Long result = executeScript("migrate-legacy-processing", RedisKeys.taskProcessing(),
                Operation.MIGRATE, MIGRATE_PROCESSING_SCRIPT,
                List.of(RedisKeys.taskProcessing(), RedisKeys.taskState(taskId),
                        RedisKeys.taskLegacyMigrated()),
                taskId, Instant.now().toString());
        return result != null ? result.intValue() : 0;
    }

    /**
     * 迁移高层命令 — 把旧 task:queue 中的单个任务迁入新 pending 队列(账本幂等).
     *
     * @param taskId 旧队列中的任务 ID
     * @return 1=已迁移 / 0=账本已标记跳过 / 2=新 state 冲突(拒绝覆盖)
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝
     */
    public int migrateLegacyPending(String taskId) {
        Long result = executeScript("migrate-legacy-pending", RedisKeys.taskPending(),
                Operation.MIGRATE, MIGRATE_PENDING_SCRIPT,
                List.of(RedisKeys.taskPending(), RedisKeys.taskState(taskId),
                        RedisKeys.taskLegacyMigrated()),
                taskId, Instant.now().toString());
        return result != null ? result.intValue() : 0;
    }

    /**
     * 执行 Lua 脚本并统一处理 TYPE 拒绝码 — 所有写路径的收口点.
     *
     * <p>脚本返回 {@code -1} 表示键 TYPE 前置校验拒绝(未修改任何键), 映射为
     * {@link NonRetryableException}(疑似数据损坏, 需运维介入)。
     */
    private Long executeScript(String operation, String key, Operation metricOperation,
                               DefaultRedisScript<Long> script, List<String> keys, String... args) {
        Long result = supplyWithMapping(operation, key, metricOperation, Duration.ZERO,
                () -> executeScriptInternal(script, keys, args));
        if (result != null && result == SCRIPT_RESULT_TYPE_REJECTED) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    operation + " 失败(键 TYPE 前置校验拒绝, 疑似数据损坏): key=" + key);
        }
        return result;
    }

    private Long executeScriptInternal(DefaultRedisScript<Long> script, List<String> keys,
                                       String... args) {
        return stringRedisTemplate.execute(script, keys, (Object[]) args);
    }

    /** 阻塞等待模式下瞬态重试(队首竞争/空队列等待)前的短暂停顿; 中断时恢复中断标记并按超时语义返回. */
    private void pauseBeforeRaceRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(CLAIM_RACE_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("poll 队首竞争停顿被中断, 按超时返回");
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    "poll 失败(竞争等待被中断)");
        }
    }

    /**
     * 写入 state Hash 前的文本净化 — 去换行防日志/记录注入, 按 code point 截断防代理对乱码(N2).
     *
     * <p>状态记录的最小失败原因不需要完整堆栈(完整审计归 10.5), 截断到 200 code points。
     */
    private String sanitizeStateText(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        int maxCodePoints = 200;
        if (flattened.codePointCount(0, flattened.length()) <= maxCodePoints) {
            return flattened;
        }
        return flattened.substring(0, flattened.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * 异常包装 helper — 沿用 Story 1.5b {@code RedisRepositoryImpl.supplyWithMapping} 模式.
     *
     * <p>注意: 本类重复实现而非抽到 common, 因 List/Set/脚本操作与 Repository CRUD 是不同抽象层级,
     * 不强行共用.
     */
    private <T> T supplyWithMapping(String operation, String key, Operation metricOperation,
                                    Supplier<T> supplier) {
        return supplyWithMapping(operation, key, metricOperation, Duration.ZERO, supplier);
    }

    private <T> T supplyWithMapping(String operation, String key, Operation metricOperation,
                                    Duration expectedWait, Supplier<T> supplier) {
        try {
            return slowOperationRecorder.observe(
                    Kind.REDIS,
                    Dependency.REDIS,
                    metricOperation, expectedWait, supplier);
        } catch (RedisConnectionFailureException | QueryTimeoutException
                 | ClusterStateFailureException e) {
            log.warn("Redis {} 异常映射为 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 连接异常): key=" + key, e);
        } catch (InvalidDataAccessApiUsageException | SerializationException e) {
            log.warn("Redis {} 异常映射为 NonRetryableException: key={}", operation, key, e);
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    operation + " 失败(Redis 数据异常): key=" + key, e);
        } catch (RedisSystemException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidDataAccessApiUsageException
                    || cause instanceof SerializationException) {
                log.warn("Redis {} 异常映射为 NonRetryableException(根因透传): key={}", operation, key, e);
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        operation + " 失败(Redis 数据异常, 根因透传): key=" + key, e);
            }
            log.warn("Redis {} 异常保守归 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 系统异常, 保守归可重试): key=" + key, e);
        }
    }
}
