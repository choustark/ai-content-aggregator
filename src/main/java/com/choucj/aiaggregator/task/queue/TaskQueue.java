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
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 *   <li>{@code task:{queue}:retry} (Redis ZSET) — 10.5 延迟重试调度, score=dueAt(epoch millis)</li>
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
 * ({@code push/poll/complete/markDeadLetter/recordRetryableFailure/dispatchDueRetries/
 * replayDeadLetter/recoverProcessingTasks/migrateLegacy*}),
 * 不得直接操作 LPOP/SADD/SREM/ZADD/ZREM 或删除任务键(Story 10.5)。
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
    /** PUSH 脚本返回码: taskId 已在 retry ZSET(退避窗口), 提前入队会绕过退避节奏. */
    private static final long PUSH_RESULT_RETRY_SCHEDULED = 4L;
    /** PUSH 脚本返回码: taskId 已完成, 同一逻辑任务不得在终态后复活. */
    private static final long PUSH_RESULT_COMPLETED = 5L;
    /** 所有脚本共用返回码: 键 TYPE 前置校验拒绝, 未修改任何键(2.2a). */
    private static final long SCRIPT_RESULT_TYPE_REJECTED = -1L;

    /** poll 阻塞等待模式下队首竞争的短暂停顿(避免忙等打满 CPU). */
    private static final long CLAIM_RACE_PAUSE_MS = 50L;

    /** RETRY_SCHEDULE 脚本返回码: 重试已排期(RETRY_SCHEDULED, 进入 retry ZSET). */
    private static final long RETRY_RESULT_SCHEDULED = 1L;
    /** RETRY_SCHEDULE 脚本返回码: 尝试次数耗尽, 同脚本原子落死信(DEAD_LETTER). */
    private static final long RETRY_RESULT_EXHAUSTED = 2L;
    /** RETRY_SCHEDULE 脚本返回码: 任务不在 processing 集合(跳过, 不推进状态). */
    private static final long RETRY_RESULT_NOT_IN_PROCESSING = 0L;
    /** RETRY_SCHEDULE 脚本返回码: state attempt/maxAttempts 字段损坏(非数字/负数/越界), 写前拒绝. */
    private static final long RETRY_RESULT_CORRUPT_STATE = -2L;
    /** RETRY_DISPATCH 脚本返回码: 到期任务已原子转回 pending(QUEUED). */
    private static final long DISPATCH_RESULT_DISPATCHED = 1L;
    /** RETRY_DISPATCH 脚本返回码: ZSET 成员被并发轮询抢先移除(ZREM 唯一赢家语义). */
    private static final long DISPATCH_RESULT_RACE_LOST = 0L;
    /** RETRY_DISPATCH 脚本返回码: state 状态异常(非 RETRY_SCHEDULED), 已原子隔离. */
    private static final long DISPATCH_RESULT_ABNORMAL_STATE = 2L;
    /** REPLAY 脚本返回码: 补跑成功(新 pending 任务已创建). */
    private static final long REPLAY_RESULT_REPLAYED = 1L;
    /** REPLAY 脚本返回码: 原 taskId 不是死信任务(仅死信可补跑). */
    private static final long REPLAY_RESULT_NOT_DEAD_LETTER = 0L;
    /** REPLAY 脚本返回码: 新 taskId 已存在 state 记录(冲突拒绝覆盖). */
    private static final long REPLAY_RESULT_CONFLICT = 2L;
    /** REPLAY 脚本返回码: 原 state 不一致(缺失或状态非 DEAD_LETTER, 拒绝补跑防幽灵任务). */
    private static final long REPLAY_RESULT_ORIGINAL_NOT_DEAD_LETTER = 3L;
    /** dispatchDueRetries 单次扫描的到期批次上限(超出部分有界滞留, 下个 tick 继续). */
    private static final long DISPATCH_BATCH_LIMIT = 100L;

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
     * PUSH 脚本(5 键): KEYS=[pending, processing, dead-letter, retry, state], ARGV=[taskId, now].
     *
     * <p>入队前守卫队列一致性不变式(10.4 review 修复, 10.5 review 补 retry ZSET 守卫):
     * taskId 已在 processing(在途)返回 2、已在 dead-letter 返回 3、已在 retry ZSET(退避窗口)
     * 返回 4、已在 pending 返回 0 — 均不写任何键, 防止把在途任务或死信终态
     * 的 state 覆盖成 QUEUED
     * (否则恢复脚本只认 PROCESSING, 任务会悬挂在 processing 永不重排)、同一任务在
     * pending 中重复入队被消费两次, 以及在退避窗口内提前把 RETRY_SCHEDULED 任务放回 pending
     * 并在 retry ZSET 留下陈旧成员。返回 1=入队成功 / 0=已在队列 / 2=在途 / 3=死信 /
     * 4=重试排期中 / 5=已完成终态 / -1=拒绝。
     */
    private static final DefaultRedisScript<Long> PUSH_SCRIPT = new DefaultRedisScript<>("""
            local pendingType = redis.call('TYPE', KEYS[1])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local processingType = redis.call('TYPE', KEYS[2])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[3])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local retryType = redis.call('TYPE', KEYS[4])['ok']
            if retryType ~= 'none' and retryType ~= 'zset' then return -1 end
            local stateType = redis.call('TYPE', KEYS[5])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return 3 end
            if redis.call('ZSCORE', KEYS[4], ARGV[1]) then return 4 end
            if redis.call('LPOS', KEYS[1], ARGV[1]) then return 0 end
            local status = redis.call('HGET', KEYS[5], 'status')
            if status == 'DEAD_LETTER' then return 3 end
            if status == 'COMPLETED' then return 5 end
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[5], 'taskId', ARGV[1], 'status', 'QUEUED', 'updatedAt', ARGV[2])
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
     * DEAD_LETTER 脚本(3 键): KEYS=[processing, dead-letter, state],
     * ARGV=[taskId, now, errorCode, reason].
     *
     * <p>仅在确实从 processing 移除时入死信集合并写 DEAD_LETTER 终态(AC3 可审计字段全集:
     * 任务标识/尝试次数/最后错误摘要/进入时间/本次错误码)。10.5 review 修复: 审计的是
     * <b>本次执行</b> — {@code attempt} 写前校验(tonumber 整数/非负/上限, 拒绝路径零写入,
     * 防"先 SREM 后 HINCRBY 报错"把任务永久移出运行队列)后递增写入(首次不可重试即
     * attempt=1 而非 0), {@code lastErrorCode} 用本次传入值覆盖(不再保留上一次瞬态错误码)。
     * 返回 1=移入死信 / 0=不在 processing / -1=拒绝 / -2=state attempt 字段损坏。
     */
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[2])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[3])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local rawAttempt = redis.call('HGET', KEYS[3], 'attempt')
            local attemptNum = 0
            if rawAttempt then
                local parsed = tonumber(rawAttempt)
                if not parsed or parsed < 0 or parsed == math.huge
                    or parsed ~= math.floor(parsed) or parsed > 1e15 then return -2 end
                attemptNum = parsed
            end
            local removed = redis.call('SREM', KEYS[1], ARGV[1])
            if removed == 0 then return 0 end
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('HSET', KEYS[3], 'taskId', ARGV[1], 'status', 'DEAD_LETTER',
                'attempt', attemptNum + 1, 'lastErrorCode', ARGV[3],
                'updatedAt', ARGV[2], 'deadLetteredAt', ARGV[2], 'lastErrorSummary', ARGV[4])
            return 1
            """, Long.class);

    /**
     * 文章交付失败记录脚本(2 键): 直接创建文章级死信审计任务，不污染日期批次任务。
     * 已存在同一 DEAD_LETTER 记录时幂等成功；其他既有状态拒绝覆盖。
     */
    private static final DefaultRedisScript<Long> DELIVERY_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local deadLetterType = redis.call('TYPE', KEYS[1])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[2])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local status = redis.call('HGET', KEYS[2], 'status')
            if status == 'DEAD_LETTER' and redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return 0 end
            if status then return 2 end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[2], 'taskId', ARGV[1], 'status', 'DEAD_LETTER',
                'attempt', 1, 'lastErrorCode', ARGV[3], 'lastErrorSummary', ARGV[4],
                'updatedAt', ARGV[2], 'deadLetteredAt', ARGV[2], 'articleId', ARGV[5])
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
     * QUARANTINE 脚本(2 键): KEYS=[retry, retry-quarantine], ARGV=[taskId, now].
     *
     * <p>重投扫描的坏成员隔离(10.5 review: 成员级容错只 catch 日志不移除时, TYPE 损坏成员
     * 会持续占据 ZRANGEBYSCORE 最早 100 条, 令后续合法到期任务永久饥饿)。原子完成
     * ZREM retry → ZADD quarantine(score=移入时间, 自带审计), 移出后后续到期成员即可被
     * 扫描到。返回 1=已隔离 / 0=成员已消失(并发竞态, 无需隔离) / -1=TYPE 拒绝。
     */
    private static final DefaultRedisScript<Long> QUARANTINE_SCRIPT = new DefaultRedisScript<>("""
            local retryType = redis.call('TYPE', KEYS[1])['ok']
            if retryType ~= 'none' and retryType ~= 'zset' then return -1 end
            local quarantineType = redis.call('TYPE', KEYS[2])['ok']
            if quarantineType ~= 'none' and quarantineType ~= 'zset' then return -1 end
            if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
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
     * <p>幂等守卫(10.4 review 修复, 10.5 review 补 retry 与终态守卫): taskId 已在 processing(在途)、
     * 已在 pending 或已在 retry ZSET(退避窗口)时跳过写入；
     * 已在 dead-letter 时拒绝普通入队，必须由 Story 10.5 的显式人工补跑命令处理。
     * state 已是 {@code COMPLETED} 时同样跳过写入, 保证日期化逻辑任务同日完成后不被重新触发复活；
     * 在途任务被覆盖成 QUEUED 会破坏"processing 成员必为 PROCESSING"不变式，
     * 死信被普通 cron 覆盖则会绕过 AD-5 的人工补跑边界，retry ZSET 成员被提前放回 pending
     * 则会绕过退避节奏并在 ZSET 留下陈旧成员(10.5 review)。调用方无须前置去重(如
     * {@code ContentScheduler.enqueueRunTaskIfAbsent} 的检查与本守卫互为双保险)。
     *
     * <p>依赖 Redis 6.2+ 的 {@code LPOS} 做队列内重复检测(redis-stack 7.x 满足)。
     *
     * @param taskId 任务 ID(业务侧生成,如 {@code "twitter:run:{yyyy-MM-dd}"})
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
                        RedisKeys.taskDeadLetter(), RedisKeys.taskRetry(),
                        RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString());
        if (result != null && result == PUSH_RESULT_ALREADY_QUEUED) {
            log.info("任务 {} 已在 pending 队列, 跳过重复入队", taskId);
        } else if (result != null && result == PUSH_RESULT_IN_FLIGHT) {
            log.info("任务 {} 正在 processing 中, 跳过重复入队(不覆盖在途状态)", taskId);
        } else if (result != null && result == PUSH_RESULT_DEAD_LETTER) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "push 失败: 任务已在 dead-letter, 仅允许显式人工补跑: taskId=" + taskId);
        } else if (result != null && result == PUSH_RESULT_RETRY_SCHEDULED) {
            log.info("任务 {} 已在 retry ZSET(退避窗口), 跳过入队(保留退避节奏)", taskId);
        } else if (result != null && result == PUSH_RESULT_COMPLETED) {
            log.info("任务 {} 已完成, 跳过同 ID 重复入队", taskId);
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
     * 把不可重试失败任务移入死信终态(Story 10.4 创建, Story 10.5 扩展记录字段).
     *
     * <p>原子完成: 从 processing 移除 + 入 dead-letter 集合 + state 置
     * {@code DEAD_LETTER}, 避免失败任务被误标 COMPLETED。10.5 起死信记录含
     * taskId/attempt/lastErrorCode/时间/脱敏原因(AC3 可审计字段全集); 10.5 review 修复:
     * attempt 在脚本内<b>写前校验后递增</b>(审计的是本次执行, 首次不可重试即 attempt=1),
     * {@code lastErrorCode} 用本次传入值覆盖 — 不再透传上一次瞬态错误码。
     * 人工补跑走 {@link #replayDeadLetter(String, String)}, 本方法不可逆。
     *
     * @param taskId    任务 ID
     * @param errorCode 脱敏错误码(如 ErrorCode 枚举名或异常类 simpleName, 审计本次失败原因)
     * @param reason    脱敏失败原因(写入前会再做换行清洗与截断)
     * @return {@code true} 表示已移入死信;{@code false} 表示任务不在 processing(跳过)
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝或 state attempt 字段损坏(数据损坏, 需运维介入)
     */
    public boolean markDeadLetter(String taskId, String errorCode, String reason) {
        Long result = executeScript("dead-letter", RedisKeys.taskDeadLetter(), Operation.REMOVE,
                DEAD_LETTER_SCRIPT,
                List.of(RedisKeys.taskProcessing(), RedisKeys.taskDeadLetter(),
                        RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString(),
                sanitizeStateText(errorCode), sanitizeStateText(reason));
        boolean moved = result != null && result == 1L;
        if (moved) {
            log.warn("任务 {} 移入死信(dead-letter), 状态置 DEAD_LETTER: errorCode={}",
                    taskId, sanitizeStateText(errorCode));
        } else {
            log.info("任务 {} 不在 processing 集合, 跳过死信标记(result={})", taskId, result);
        }
        return moved;
    }

    /** 原子创建文章级交付失败死信；保留日期批次任务，供显式补跑复用既有 REPLAY 契约。 */
    public boolean recordDeliveryFailure(String taskId, String articleId, String errorCode, String reason) {
        Long result = executeScript("delivery-failure", RedisKeys.taskDeadLetter(), Operation.REMOVE,
                DELIVERY_FAILURE_SCRIPT,
                List.of(RedisKeys.taskDeadLetter(), RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString(), sanitizeStateText(errorCode),
                sanitizeStateText(reason), sanitizeStateText(articleId));
        if (result != null && (result == 1L || result == 0L)) {
            return true;
        }
        if (result != null && result == 2L) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "文章交付失败任务状态冲突: taskId=" + taskId);
        }
        return false;
    }

    /**
     * 可重试失败推进(Story 10.5 AC1/AC3) — 单个原子状态迁移把 PROCESSING 任务
     * 转为 RETRY_SCHEDULED(未达上限)或 DEAD_LETTER(已达上限).
     *
     * <p>流程: 预读 state 当前 {@code attempt}(任务由本消费者独占, 无并发推进) →
     * 按 {@code RetryPolicyProperties.delayForAttempt(attempt+1)} 计算 dueAt
     * (饱和加法防 long 溢出翻负 — 10.5 review: 负 score 成员永远不会落入到期扫描区间) →
     * RETRY_SCHEDULE 脚本原子完成校验 + SREM + attempt 递增 + 分支推进。
     * {@code errorCode}/{@code errorSummary} 写入前经 {@code sanitizeStateText} 净化(N4)。
     *
     * @param taskId       任务 ID
     * @param errorCode    脱敏错误码(如 ErrorCode 枚举名或异常类 simpleName)
     * @param errorSummary 脱敏错误摘要(写入前会再做换行清洗与截断)
     * @param policy       重试策略配置(上限/退避)
     * @return 推进结果(含 attempt/dueAt 审计字段, 供调用方 W11 日志):
     *         RETRY_SCHEDULED / DEAD_LETTERED / NOT_IN_PROCESSING(幂等跳过)
     * @throws RetryableException    Redis 连接异常
     * @throws NonRetryableException 键 TYPE 校验拒绝或 state 数值字段损坏(数据损坏, 需运维介入)
     */
    public RetryAdvance recordRetryableFailure(String taskId, String errorCode,
                                               String errorSummary, RetryPolicyProperties policy) {
        if (taskId == null) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "recordRetryableFailure 失败: taskId 不能为 null");
        }
        long currentAttempt = readAttempt(taskId);
        // 下一次执行是第 (currentAttempt+1) 次; 首次失败(currentAttempt=0)延迟 initial
        long delayMs = policy.delayForAttempt((int) Math.min(currentAttempt + 1, Integer.MAX_VALUE));
        long nowMs = Instant.now().toEpochMilli();
        // 饱和加法: 极端合法配置(backoff-max-ms 逼近 Long.MAX)下 dueAt 不得翻负,
        // 负 score 会脱离 [0, now] 到期扫描区间使任务永不到期(10.5 review)
        long dueAt = delayMs > Long.MAX_VALUE - nowMs ? Long.MAX_VALUE : nowMs + delayMs;
        Long result = executeScript("retry-schedule", RedisKeys.taskRetry(),
                Operation.RETRY_SCHEDULE, RETRY_SCHEDULE_SCRIPT,
                List.of(RedisKeys.taskProcessing(), RedisKeys.taskRetry(),
                        RedisKeys.taskDeadLetter(), RedisKeys.taskState(taskId)),
                taskId, Instant.now().toString(), String.valueOf(dueAt),
                sanitizeStateText(errorCode), sanitizeStateText(errorSummary),
                String.valueOf(policy.getMaxAttempts()));
        if (result != null && result == RETRY_RESULT_SCHEDULED) {
            log.warn("任务 {} 可重试失败, 已排期重试: attempt={}, dueAt={}, delayMs={}, errorCode={}",
                    taskId, currentAttempt + 1, dueAt, delayMs, sanitizeStateText(errorCode));
            return RetryAdvance.scheduled(currentAttempt + 1, dueAt);
        }
        if (result != null && result == RETRY_RESULT_EXHAUSTED) {
            log.warn("任务 {} 可重试失败且尝试次数耗尽(maxAttempts={}), 落死信终态 DEAD_LETTER",
                    taskId, policy.getMaxAttempts());
            return RetryAdvance.deadLettered(currentAttempt + 1);
        }
        if (result != null && result == RETRY_RESULT_CORRUPT_STATE) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "recordRetryableFailure 失败(state attempt/maxAttempts 数值字段损坏, 拒绝推进"
                            + "以防任务脱离状态机, 需运维介入): taskId=" + taskId);
        }
        log.info("任务 {} 不在 processing 集合, 跳过重试推进(result={})", taskId, result);
        return RetryAdvance.notInProcessing();
    }

    /**
     * 到期重投(Story 10.5 AC2) — 扫描 retry ZSET 中 dueAt 已到期的任务并原子转回 pending.
     *
     * <p>每批最多 {@code DISPATCH_BATCH_LIMIT}(100) 个到期成员, 超出部分有界滞留、
     * 下个调度 tick 继续(调度间隔秒级, 滞留窗口可忽略)。扫描下界用 {@code -inf}
     * (10.5 review 二轮: 损坏/遗留的负 score 成员也纳入处置视野, 用 0 会让其永远不可见)。
     * 并发安全性由脚本内 ZREM 唯一赢家语义保证: 多实例同时扫描同一到期任务时仅一个
     * ZREM 成功并重投。
     *
     * <p>成员级容错与坏成员隔离(10.5 review 二轮): 单个成员处理抛
     * {@link NonRetryableException}(如 TYPE 拒绝的数据损坏)时, 经 QUARANTINE 脚本把该成员
     * <b>原子移出 retry ZSET</b> 落入 {@code task:{queue}:retry-quarantine} 隔离区
     * (score=移入时间自带审计)并记 ERROR 后继续批内后续成员 — 只 catch 日志不移除的话,
     * 坏成员会持续占据最早 100 条, 令后续合法到期任务永久饥饿;
     * 隔离本身再失败(TYPE 级损坏)则仅 ERROR 告警交运维。{@link RetryableException}
     * (Redis 连接异常)向上传播 — 连接不可用时继续逐成员执行只会级联失败。
     * 状态异常成员(脚本返回 2)<b>不计入</b>重投数(未发生 pending 新增), 仅告警供运维介入。
     *
     * @return 实际重投(pending 新增)的任务数量
     * @throws RetryableException    Redis 连接异常
     */
    public int dispatchDueRetries() {
        long now = Instant.now().toEpochMilli();
        Set<String> dueTasks = supplyWithMapping("retry-due-scan", RedisKeys.taskRetry(),
                Operation.GET, () -> stringRedisTemplate.opsForZSet()
                        .rangeByScore(RedisKeys.taskRetry(),
                                Double.NEGATIVE_INFINITY, now, 0, DISPATCH_BATCH_LIMIT));
        if (dueTasks == null || dueTasks.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        for (String taskId : dueTasks) {
            Long result;
            try {
                result = executeScript("retry-dispatch", RedisKeys.taskRetry(),
                        Operation.RETRY_DISPATCH, RETRY_DISPATCH_SCRIPT,
                        List.of(RedisKeys.taskRetry(), RedisKeys.taskPending(),
                                RedisKeys.taskDeadLetter(), RedisKeys.taskState(taskId),
                                RedisKeys.taskRetryQuarantine()),
                        taskId, Instant.now().toString(), String.valueOf(now));
            } catch (NonRetryableException e) {
                // 坏成员必须移出 retry ZSET, 否则每个 tick 都被同一批坏成员占满扫描窗口
                quarantineRetryMember(taskId, now);
                continue;
            }
            if (result != null && result == DISPATCH_RESULT_DISPATCHED) {
                dispatched++;
            } else if (result != null && result == DISPATCH_RESULT_ABNORMAL_STATE) {
                log.error("重投任务 {} state 非 RETRY_SCHEDULED, 已从 retry 原子移入 retry-quarantine, "
                        + "未重投, 需运维核查 state", taskId);
            } else {
                log.info("重投任务 {} 未由本轮重投(result={}, 并发轮询竞态或成员已消失)", taskId, result);
            }
        }
        if (dispatched > 0) {
            log.info("到期重投完成: {}/{} 个到期任务已转回 pending", dispatched, dueTasks.size());
        }
        return dispatched;
    }

    /**
     * 把重投扫描中的损坏成员原子移入隔离区(10.5 review 二轮) —
     * 移出后后续合法到期成员不再被坏成员堵塞。隔离动作自身失败仅 ERROR 告警不抛
     * (成员级容错语义: 单个坏成员的处理异常不得影响批次内其他成员)。
     */
    private void quarantineRetryMember(String taskId, long nowMs) {
        try {
            Long quarantined = executeScript("retry-quarantine", RedisKeys.taskRetryQuarantine(),
                    Operation.RETRY_DISPATCH, QUARANTINE_SCRIPT,
                    List.of(RedisKeys.taskRetry(), RedisKeys.taskRetryQuarantine()),
                    taskId, String.valueOf(nowMs));
            if (quarantined != null && quarantined == 1L) {
                log.error("重投任务 {} 数据损坏, 已移入 retry-quarantine 隔离区(score={}), "
                        + "需运维核查处置(不设 TTL, 不会自动重投)", taskId, nowMs);
            } else {
                log.warn("重投任务 {} 数据损坏且隔离时成员已消失(result={}, 并发竞态), 跳过隔离", taskId, quarantined);
            }
        } catch (NonRetryableException e) {
            log.error("重投任务 {} 隔离失败(隔离区键 TYPE 损坏?), 坏成员仍留在 retry ZSET, "
                    + "需运维立即核查: {}", taskId, e.getMessage());
        }
    }

    /**
     * 人工补跑(Story 10.5 AC4) — 把死信任务复制为可关联的新 pending 任务(幂等请求标识版).
     *
     * <p>原死信审计记录(state + dead-letter 集合成员)<b>完整保留</b>; 新任务 state 写
     * {@code replayedFrom} 指向原 taskId 表达关联。新 taskId 确定性生成
     * {@code <原taskId>:replay:<replayRequestId>}(10.5 review 二轮任务身份决策:
     * 推翻一轮 review 的随机 UUID 后缀 — 随机后缀让重复/并发补跑必然各自创建
     * 一个 pending 任务; 确定性 ID 使重复补跑命中 REPLAY 脚本 {@code EXISTS state:new}
     * 冲突拒绝, 天然幂等)。{@code replayRequestId} 为补跑幂等请求标识: 客户端显式传入
     * (如操作工单号)或缺省当日日期(同一天内对同一死信的补跑至多创建一个 pending 任务)。
     * 合法字符集 {@code [A-Za-z0-9._-]{1,64}}(防 Redis key 注入), 非法抛
     * {@link NonRetryableException}。确定性 ID 不与原 taskId 相同, 不触发
     * {@code push} 的死信复活守卫。
     *
     * <p>拒绝语义以返回码表达(10.5 review: 调用方控制器需把「非死信 404 /
     * 数据不一致 409 / 目标冲突 409」区分映射, 异常类型折叠损失语义):
     * 成员资格 + 原 state {@code status==DEAD_LETTER} 双重校验在脚本内原子完成;
     * TARGET_CONFLICT 在幂等模型下是<b>正常重复补跑信号</b>(不再只是异常冲突)。
     *
     * @param originalTaskId  死信任务 ID
     * @param replayRequestId 补跑幂等请求标识({@code [A-Za-z0-9._-]{1,64}}; null/空白取当日日期)
     * @return 补跑结果(含 outcome 与新任务 ID; 非成功 outcome 时 newTaskId 为 null)
     * @throws NonRetryableException taskId 为空 / requestId 非法 / 键 TYPE 校验拒绝(数据损坏)
     * @throws RetryableException    Redis 连接异常
     */
    public ReplayResult replayDeadLetter(String originalTaskId, String replayRequestId) {
        if (originalTaskId == null || originalTaskId.isBlank()) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "replayDeadLetter 失败: taskId 不能为空");
        }
        String requestId = sanitizeReplayRequestId(replayRequestId);
        String newTaskId = originalTaskId + ":replay:" + requestId;
        Long result = executeScript("replay", RedisKeys.taskDeadLetter(), Operation.REPLAY,
                REPLAY_SCRIPT,
                List.of(RedisKeys.taskDeadLetter(), RedisKeys.taskPending(),
                        RedisKeys.taskState(originalTaskId), RedisKeys.taskState(newTaskId)),
                originalTaskId, newTaskId, Instant.now().toString());
        if (result != null && result == REPLAY_RESULT_REPLAYED) {
            log.warn("死信任务 {} 人工补跑: 新任务 {} 已入 pending(replayedFrom={}, requestId={})",
                    originalTaskId, newTaskId, originalTaskId, requestId);
            return new ReplayResult(ReplayOutcome.REPLAYED, newTaskId);
        }
        if (result != null && result == REPLAY_RESULT_NOT_DEAD_LETTER) {
            log.warn("补跑请求被拒: 任务 {} 不是死信成员, 仅死信可补跑", originalTaskId);
            return new ReplayResult(ReplayOutcome.NOT_DEAD_LETTER, null);
        }
        if (result != null && result == REPLAY_RESULT_ORIGINAL_NOT_DEAD_LETTER) {
            log.error("补跑请求被拒: 任务 {} 在死信集合但原 state 缺失或状态非 DEAD_LETTER(数据不一致)", originalTaskId);
            return new ReplayResult(ReplayOutcome.ORIGINAL_STATE_INCONSISTENT, null);
        }
        if (result != null && result == REPLAY_RESULT_CONFLICT) {
            log.warn("补跑请求被拒(幂等重复补跑或目标冲突): newTaskId={} 已存在", newTaskId);
            return new ReplayResult(ReplayOutcome.TARGET_CONFLICT, null);
        }
        throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                "replayDeadLetter 失败(未预期的脚本返回码): taskId=" + originalTaskId
                        + ", result=" + result);
    }

    /**
     * 补跑幂等请求标识净化 — null/空白取当日日期(默认幂等窗口: 一天), 其余校验
     * {@code [A-Za-z0-9._-]{1,64}} 白名单(防 Redis key 注入/路径穿越), 非法抛
     * {@link NonRetryableException}。
     */
    /** 读取补跑执行器所需的显式路由元数据，避免从 taskId 字符串反推业务标识。 */
    public ReplayMetadata getReplayMetadata(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "getReplayMetadata 失败: taskId 不能为空");
        }
        String stateKey = RedisKeys.taskState(taskId);
        Object articleId = supplyWithMapping("replay-metadata", stateKey, Operation.GET,
                () -> stringRedisTemplate.opsForHash().get(stateKey, "articleId"));
        Object replayedFrom = supplyWithMapping("replay-metadata", stateKey, Operation.GET,
                () -> stringRedisTemplate.opsForHash().get(stateKey, "replayedFrom"));
        Object draftMediaId = supplyWithMapping("replay-metadata", stateKey, Operation.GET,
                () -> stringRedisTemplate.opsForHash().get(stateKey, "draftMediaId"));
        if (!(articleId instanceof String article) || article.isBlank()
                || !(replayedFrom instanceof String original) || original.isBlank()) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "补跑任务缺少 articleId/replayedFrom 路由元数据: taskId=" + taskId);
        }
        String receipt = draftMediaId instanceof String value && !value.isBlank() ? value : null;
        return new ReplayMetadata(article, original, receipt);
    }

    /** 持久化微信草稿成功收据；后续补跑只能据此完成本地对账，不能再次调用微信。 */
    public void recordReplayDraftReceipt(String taskId, String mediaId) {
        if (taskId == null || taskId.isBlank() || mediaId == null || mediaId.isBlank()) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "记录补跑草稿收据失败: taskId/mediaId 不能为空");
        }
        String stateKey = RedisKeys.taskState(taskId);
        supplyWithMapping("replay-draft-receipt", stateKey, Operation.SET, () -> {
            stringRedisTemplate.opsForHash().put(stateKey, "draftMediaId", mediaId);
            return null;
        });
    }

    /**
     * Story 10.8: 读取任务 state Hash 中的 {@code articleId} 上下文 (只读, 供调度器耗尽收敛钩子
     * 定位失败文章)。无记录/空白 taskId 或字段缺失/空白返回 {@link Optional#empty()}。
     *
     * <p>{@code articleId} 由 {@link #recordDeliveryRetryContext} 在 RETRY_SCHEDULED 时写入;
     * RETRY_SCHEDULE Lua 脚本只 HSET 自身键, 不清除该额外字段, 故耗尽落死信时仍可读。
     */
    public Optional<String> readStateArticleId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        String stateKey = RedisKeys.taskState(taskId);
        Object value = supplyWithMapping("read-state-articleId", stateKey, Operation.GET,
                () -> stringRedisTemplate.opsForHash().get(stateKey, "articleId"));
        return value instanceof String articleId && !articleId.isBlank()
                ? Optional.of(articleId) : Optional.empty();
    }

    /**
     * Story 10.8: 可重试媒体交付失败时把 {@code articleId} 记入任务 state Hash —
     * 使重试耗尽落死信时调度器耗尽收敛钩子能凭 state 定位失败文章并触发四层收敛
     * (先权威状态迁移、后旁路上下文写入, 不改 Lua 契约与 10.5 状态机语义)。
     */
    public void recordDeliveryRetryContext(String taskId, String articleId) {
        if (taskId == null || taskId.isBlank() || articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "记录媒体交付重试上下文失败: taskId/articleId 不能为空");
        }
        String stateKey = RedisKeys.taskState(taskId);
        supplyWithMapping("record-delivery-retry-context", stateKey, Operation.SET, () -> {
            stringRedisTemplate.opsForHash().put(stateKey, "articleId", articleId);
            return null;
        });
    }

    private String sanitizeReplayRequestId(String replayRequestId) {
        if (replayRequestId == null || replayRequestId.isBlank()) {
            return LocalDate.now().toString();
        }
        String trimmed = replayRequestId.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "replayDeadLetter 失败: 补跑请求标识含非法字符或超长(仅允许"
                            + " [A-Za-z0-9._-]{1,64})");
        }
        return trimmed;
    }

    /** 返回 retry ZSET 大小(重试积压), 供监控等只读调用方使用(TaskMetrics 依赖). */
    public long retryCount() {
        Long count = supplyWithMapping("retryCount", RedisKeys.taskRetry(), Operation.GET, () ->
                stringRedisTemplate.opsForZSet().zCard(RedisKeys.taskRetry()));
        return count != null ? count : 0L;
    }

    /** 返回 dead-letter 集合大小(死信积压), 供监控等只读调用方使用(TaskMetrics 依赖). */
    public long deadLetterCount() {
        Long count = supplyWithMapping("deadLetterCount", RedisKeys.taskDeadLetter(),
                Operation.GET, () ->
                        stringRedisTemplate.opsForSet().size(RedisKeys.taskDeadLetter()));
        return count != null ? count : 0L;
    }

    /**
     * 预读 state Hash 当前 attempt — 写前校验与脚本口径一致(10.5 review 二轮).
     *
     * <p>无记录/空白按 0(首次失败); 数值形态与脚本 {@code tonumber} 口径对齐 —
     * {@code "1.0"}/{@code "1e0"} 等整数值的小数/指数形态接受(与 HSET 写回规范化一致),
     * 非数字、非整数、负数或超出脚本同款上限({@code 1e15})时抛
     * {@link NonRetryableException} — 不再按 0 静默吞掉(旧口径会把损坏 attempt 当首次失败
     * 重置重试预算, 与脚本 -2 拒绝码分裂)。调用方(调度器)对该异常做批次内隔离, 不中断后续任务。
     */
    private long readAttempt(String taskId) {
        String attempt = supplyWithMapping("read-attempt", RedisKeys.taskState(taskId),
                Operation.GET, () -> (String) stringRedisTemplate.opsForHash()
                        .get(RedisKeys.taskState(taskId), "attempt"));
        if (attempt == null || attempt.isBlank()) {
            return 0L;
        }
        String trimmed = attempt.trim();
        // 与 Redis Lua 5.1 tonumber 字面量口径一致(十进制整数/小数/指数, 不含 NaN/Inf/十六进制)
        if (!trimmed.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "readAttempt 失败(state attempt 字段非数字, 拒绝推进以防任务脱离状态机,"
                            + " 需运维介入): taskId=" + taskId);
        }
        double parsed = Double.parseDouble(trimmed);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)
                || parsed != Math.rint(parsed)
                || parsed < 0 || parsed > 1_000_000_000_000_000L) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "readAttempt 失败(state attempt 字段越界, 拒绝推进以防任务脱离状态机,"
                            + " 需运维介入): taskId=" + taskId);
        }
        return (long) parsed;
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
     * RETRY_SCHEDULE 脚本(4 键): KEYS=[processing, retry, dead-letter, state],
     * ARGV=[taskId, now, dueAt, lastErrorCode, lastErrorSummary, maxAttempts].
     *
     * <p>单个原子状态迁移(AC1/AC3): 复核任务仍在 processing 且 state 为 PROCESSING
     * (10.5 review: 陈旧成员不得覆盖终态; state 缺失同样拒绝, 避免生成无 taskId 的孤儿审计)
     * 后, <b>在任何写操作之前</b>校验 attempt/maxAttempts 数值合法性
     * (10.5 review: Lua 无回滚, 拒绝路径必须零写入, 否则任务可能永久脱离状态机),
     * 再 SREM → 分支推进 — 未达 maxAttempts: HSET attempt=attemptNum+1
     * (10.5 review 二轮: 弃用 HINCRBY — tonumber 接受的 "1.0"/"1e0" 是 HINCRBY 拒绝的
     * 数值字符串, SREM 后 HINCRBY 报错无回滚会把任务永久移出运行队列; 解析后直接写
     * 规范化整数同时完成"解析+递增+规范化") + ZADD retry(dueAt score)
     * + HSET RETRY_SCHEDULED(含脱敏错误字段); 已达 maxAttempts: SADD dead-letter +
     * HSET DEAD_LETTER(含本次错误码), 杜绝"先重试再判死信"的双步窗口。
     * 返回 1=重试排期 / 2=耗尽落死信 / 0=不在 processing 或非 PROCESSING 状态 /
     * -1=TYPE 拒绝 / -2=state 数值字段损坏。
     */
    private static final DefaultRedisScript<Long> RETRY_SCHEDULE_SCRIPT = new DefaultRedisScript<>("""
            local processingType = redis.call('TYPE', KEYS[1])['ok']
            if processingType ~= 'none' and processingType ~= 'set' then return -1 end
            local retryType = redis.call('TYPE', KEYS[2])['ok']
            if retryType ~= 'none' and retryType ~= 'zset' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[3])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[4])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then return 0 end
            local status = redis.call('HGET', KEYS[4], 'status')
            if not status or status ~= 'PROCESSING' then return 0 end
            local maxAttempts = tonumber(ARGV[6])
            if not maxAttempts or maxAttempts < 1 then return -2 end
            local rawAttempt = redis.call('HGET', KEYS[4], 'attempt')
            local attemptNum = 0
            if rawAttempt then
                local parsed = tonumber(rawAttempt)
                if not parsed or parsed < 0 or parsed == math.huge
                    or parsed ~= math.floor(parsed) or parsed > 1e15 then return -2 end
                attemptNum = parsed
            end
            if attemptNum + 1 >= maxAttempts then
                redis.call('SREM', KEYS[1], ARGV[1])
                redis.call('SADD', KEYS[3], ARGV[1])
                redis.call('HSET', KEYS[4], 'taskId', ARGV[1], 'status', 'DEAD_LETTER',
                    'attempt', attemptNum + 1, 'lastErrorCode', ARGV[4],
                    'updatedAt', ARGV[2], 'deadLetteredAt', ARGV[2], 'lastErrorSummary', ARGV[5])
                return 2
            end
            redis.call('SREM', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[4], 'attempt', attemptNum + 1)
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            redis.call('HSET', KEYS[4], 'taskId', ARGV[1], 'status', 'RETRY_SCHEDULED',
                'dueAt', ARGV[3], 'lastErrorCode', ARGV[4], 'lastErrorSummary', ARGV[5],
                'updatedAt', ARGV[2])
            return 1
            """, Long.class);

    /**
     * RETRY_DISPATCH 脚本(5 键): KEYS=[retry, pending, dead-letter, state, retry-quarantine],
     * ARGV=[taskId, now, quarantineScore].
     *
     * <p>到期重投(AC2): ZREM 唯一赢家 — 并发轮询时仅一个调用方 ZREM 成功, 其余返回 0 跳过,
     * 天然防重复投入。若 state 非 RETRY_SCHEDULED, 同一脚本把成员原子移入
     * retry-quarantine, 避免从所有可恢复索引中消失。返回 1=已重投 / 0=竞态落败或不存在 /
     * 2=异常状态且已隔离 / -1=TYPE 拒绝。
     */
    private static final DefaultRedisScript<Long> RETRY_DISPATCH_SCRIPT = new DefaultRedisScript<>("""
            local retryType = redis.call('TYPE', KEYS[1])['ok']
            if retryType ~= 'none' and retryType ~= 'zset' then return -1 end
            local pendingType = redis.call('TYPE', KEYS[2])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local deadLetterType = redis.call('TYPE', KEYS[3])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local stateType = redis.call('TYPE', KEYS[4])['ok']
            if stateType ~= 'none' and stateType ~= 'hash' then return -1 end
            local quarantineType = redis.call('TYPE', KEYS[5])['ok']
            if quarantineType ~= 'none' and quarantineType ~= 'zset' then return -1 end
            if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then return 0 end
            local status = redis.call('HGET', KEYS[4], 'status')
            if status == 'RETRY_SCHEDULED' then
                redis.call('RPUSH', KEYS[2], ARGV[1])
                redis.call('HSET', KEYS[4], 'status', 'QUEUED', 'updatedAt', ARGV[2])
                return 1
            end
            redis.call('ZADD', KEYS[5], ARGV[3], ARGV[1])
            return 2
            """, Long.class);

    /**
     * REPLAY 脚本(4 键): KEYS=[dead-letter, pending, state:orig, state:new],
     * ARGV=[origTaskId, newTaskId, now].
     *
     * <p>人工补跑(AC4): 死信集合成员资格 + 原 state {@code status==DEAD_LETTER}
     * 双重校验(10.5 review — 成员在集合但 state 缺失/状态漂移属不一致,
     * 补跑会制造幽灵任务)。全部校验通过后才创建<b>可关联的新</b> pending 任务
     * (新 state 写 {@code replayedFrom} 指向原 taskId, {@code updatedAt} 用 ARGV[3]
     * 时间戳 — ARGV[2] 是新 taskId), <b>不修改</b>原 state、<b>不移除</b>原死信集合成员。
     * 返回 1=补跑成功 / 0=非死信任务 / 2=新 taskId 冲突(state 已存在, 拒绝覆盖) /
     * 3=原 state 不一致(缺失或状态非 DEAD_LETTER) / -1=TYPE 拒绝。
     */
    private static final DefaultRedisScript<Long> REPLAY_SCRIPT = new DefaultRedisScript<>("""
            local deadLetterType = redis.call('TYPE', KEYS[1])['ok']
            if deadLetterType ~= 'none' and deadLetterType ~= 'set' then return -1 end
            local pendingType = redis.call('TYPE', KEYS[2])['ok']
            if pendingType ~= 'none' and pendingType ~= 'list' then return -1 end
            local origStateType = redis.call('TYPE', KEYS[3])['ok']
            if origStateType ~= 'none' and origStateType ~= 'hash' then return -1 end
            local newStateType = redis.call('TYPE', KEYS[4])['ok']
            if newStateType ~= 'none' and newStateType ~= 'hash' then return -1 end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then return 0 end
            local origStatus = redis.call('HGET', KEYS[3], 'status')
            if not origStatus or origStatus ~= 'DEAD_LETTER' then return 3 end
            if redis.call('EXISTS', KEYS[4]) == 1 then return 2 end
            local articleId = redis.call('HGET', KEYS[3], 'articleId')
            redis.call('RPUSH', KEYS[2], ARGV[2])
            redis.call('HSET', KEYS[4], 'taskId', ARGV[2], 'status', 'QUEUED',
                'replayedFrom', ARGV[1], 'updatedAt', ARGV[3])
            if articleId then redis.call('HSET', KEYS[4], 'articleId', articleId) end
            return 1
            """, Long.class);

    /** 可重试失败推进结果(AC1/AC3) — 调用方据此记录指标与日志. */
    public enum RetryOutcome {
        /** 已排期重试(RETRY_SCHEDULED, 进入 retry ZSET 按 dueAt 等待). */
        RETRY_SCHEDULED,
        /** 尝试次数耗尽, 已落死信终态(DEAD_LETTER). */
        DEAD_LETTERED,
        /** 任务不在 processing 集合, 未推进任何状态(幂等跳过). */
        NOT_IN_PROCESSING
    }

    /**
     * 可重试失败推进的审计结果(10.5 review: 携带 attempt/dueAt, 供调用方
     * W11 结构化日志记录「重试到点」而不必二次读 Redis).
     *
     * @param outcome       推进结果
     * @param attempt       推进后尝试次数(耗尽即死信 attempt; NOT_IN_PROCESSING 时为 -1)
     * @param dueAtEpochMs  重试到点时间(epoch millis, 排期分支才有意义, 其余为 -1)
     */
    public record RetryAdvance(RetryOutcome outcome, long attempt, long dueAtEpochMs) {

        /** 排期重试分支: attempt 已在脚本内递增后的值. */
        public static RetryAdvance scheduled(long attempt, long dueAtEpochMs) {
            return new RetryAdvance(RetryOutcome.RETRY_SCHEDULED, attempt, dueAtEpochMs);
        }

        /** 耗尽死信分支: attempt 为落死信时的最终次数. */
        public static RetryAdvance deadLettered(long attempt) {
            return new RetryAdvance(RetryOutcome.DEAD_LETTERED, attempt, -1L);
        }

        /** 幂等跳过分支: 未推进任何状态, 审计字段无意义. */
        public static RetryAdvance notInProcessing() {
            return new RetryAdvance(RetryOutcome.NOT_IN_PROCESSING, -1L, -1L);
        }
    }

    /** 人工补跑结果(10.5 review: 拒绝语义以返回码表达, 供控制器区分 404/409). */
    public enum ReplayOutcome {
        /** 补跑成功, 新任务已入 pending. */
        REPLAYED,
        /** 非死信任务(不在 dead-letter 集合), 仅死信可补跑. */
        NOT_DEAD_LETTER,
        /** 死信集合成员但原 state 缺失或状态非 DEAD_LETTER(数据不一致, 拒绝补跑防幽灵任务). */
        ORIGINAL_STATE_INCONSISTENT,
        /**
         * 新 taskId state 已存在 — 幂等模型(10.5 review 二轮)下的<b>正常重复补跑信号</b>:
         * 同一 (原任务, 请求标识) 的重复/并发补跑至多创建一个 pending 任务, 重复方命中本返回码.
         */
        TARGET_CONFLICT
    }

    /**
     * 人工补跑结果(10.5 review).
     *
     * @param outcome   补跑结果
     * @param newTaskId 新创建的待处理任务 ID(仅 {@link ReplayOutcome#REPLAYED} 非空, 其余为 null)
     */
    public record ReplayResult(ReplayOutcome outcome, String newTaskId) {
    }

    /** 文章交付补跑任务的显式业务路由字段。 */
    public record ReplayMetadata(String articleId, String replayedFrom, String draftMediaId) {
        public ReplayMetadata(String articleId, String replayedFrom) {
            this(articleId, replayedFrom, null);
        }
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
