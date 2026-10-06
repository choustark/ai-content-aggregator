package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.util.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 10.5 端到端集成测试 — 真 Redis 上验证延迟重试、到期重投、
 * 尝试耗尽落死信、重启恢复边界与人工补跑(AC1-AC4).
 *
 * <p><b>测试场景:</b>
 * <ol>
 *   <li>AC1 全链路: PROCESSING → recordRetryableFailure → retry ZSET 成员/dueAt/attempt
 *       与 state RETRY_SCHEDULED(processing 移除)</li>
 *   <li>AC2 到期重投: past dueAt 原子转回 pending(QUEUED); future dueAt 不投</li>
 *   <li>AC2 并发防重: 两线程同时 dispatchDueRetries, ZREM 唯一赢家, pending 恰好 1 份</li>
 *   <li>AC3 耗尽落死信: attempt 达 maxAttempts 时同脚本 DEAD_LETTERED,
 *       state 保留 attempt/lastErrorSummary/deadLetteredAt</li>
 *   <li>AC3 重启边界: recoverProcessingTasks 只重排 PROCESSING 成员,
 *       不重投 RETRY_SCHEDULED / DEAD_LETTER 成员</li>
 *   <li>AC4 补跑: 原死信审计完整保留 + 新任务 QUEUED + replayedFrom 关联; 非死信拒绝</li>
 * </ol>
 *
 * <p><b>跳过策略(沿用 10.4):</b> 本地无 Redis 时经 {@link Assumptions#assumeTrue} 跳过。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("external")
class TaskQueueRetryIntegrationTest {

    @Test
    void delivery_failure_replay_should_propagate_article_metadata() {
        assertThat(taskQueue.recordDeliveryFailure("delivery:tw-42", "tw-42", "MEDIA", "safe")).isTrue();
        TaskQueue.ReplayResult replay = taskQueue.replayDeadLetter("delivery:tw-42", "req-42");
        TaskQueue.ReplayMetadata metadata = taskQueue.getReplayMetadata(replay.newTaskId());
        assertThat(metadata.articleId()).isEqualTo("tw-42");
        assertThat(metadata.replayedFrom()).isEqualTo("delivery:tw-42");
    }

    @Autowired
    private TaskQueue taskQueue;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void requireRedisOnline() {
        boolean reachable;
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            reachable = "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "本地 Redis 不可用, 跳过 Story 10.5 集成测试");
    }

    @BeforeEach
    @AfterEach
    void cleanupTaskKeys() {
        redisTemplate.delete(RedisKeys.taskPending());
        redisTemplate.delete(RedisKeys.taskProcessing());
        redisTemplate.delete(RedisKeys.taskRetry());
        redisTemplate.delete(RedisKeys.taskRetryQuarantine());
        redisTemplate.delete(RedisKeys.taskDeadLetter());
        Set<String> stateKeys = redisTemplate.keys(RedisKeys.taskState("*"));
        if (stateKeys != null && !stateKeys.isEmpty()) {
            redisTemplate.delete(stateKeys);
        }
    }

    /** 立即到期策略 — backoff=0ms 使 dueAt≈now, dispatch 扫描即可命中. */
    private RetryPolicyProperties immediatePolicy() {
        RetryPolicyProperties policy = new RetryPolicyProperties();
        policy.setBackoffInitialMs(0L);
        policy.setBackoffMaxMs(0L);
        policy.setMaxAttempts(3);
        return policy;
    }

    private void putState(String taskId, String status, String attempt) {
        Map<String, String> fields = new java.util.HashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", status);
        fields.put("updatedAt", "2026-09-22T00:00:00Z");
        if (attempt != null) {
            fields.put("attempt", attempt);
        }
        redisTemplate.<String, String>opsForHash().putAll(RedisKeys.taskState(taskId), fields);
    }

    private HashOperations<String, String, String> hashOps() {
        return redisTemplate.opsForHash();
    }

    private String stateField(String taskId, String field) {
        return hashOps().get(RedisKeys.taskState(taskId), field);
    }

    private List<String> pendingMembers() {
        return redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1);
    }

    // ============ AC1: 全链路 PROCESSING → RETRY_SCHEDULED ============

    @Test
    void shouldScheduleRetryAtomicallyWhenRetryableFailureRecorded() {
        RetryPolicyProperties policy = immediatePolicy();
        taskQueue.push("retry-a");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-a");

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "retry-a", "REDIS_CONNECTION_ERROR", "connection refused", policy);

        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.RETRY_SCHEDULED);
        // ZSET 成员存在且 dueAt 已登记(score≈now+0)
        Double dueAt = redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "retry-a");
        assertThat(dueAt)
                .as("重试任务应进入 retry ZSET 并携带 dueAt score")
                .isNotNull();
        // state: processing 移除 + RETRY_SCHEDULED + attempt 递增 + 错误字段入账
        assertThat(taskQueue.getProcessingTasks())
                .as("重试排期后任务应离开 processing 集合")
                .doesNotContain("retry-a");
        assertThat(stateField("retry-a", "status")).isEqualTo("RETRY_SCHEDULED");
        assertThat(stateField("retry-a", "attempt")).isEqualTo("1");
        assertThat(stateField("retry-a", "lastErrorCode")).isEqualTo("REDIS_CONNECTION_ERROR");
        assertThat(stateField("retry-a", "lastErrorSummary")).isEqualTo("connection refused");
        assertThat(stateField("retry-a", "dueAt")).as("state 应登记 dueAt").isNotNull();
        // 不在 pending, 不在死信
        assertThat(pendingMembers()).isEmpty();
        assertThat(taskQueue.deadLetterCount()).isZero();
    }

    // ============ AC2: 到期重投 ============

    @Test
    void shouldDispatchDueRetryBackToPendingAndQueueState() {
        RetryPolicyProperties policy = immediatePolicy();
        taskQueue.push("retry-due");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-due");
        taskQueue.recordRetryableFailure("retry-due", "E", "transient", policy);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).isEqualTo(1);
        assertThat(pendingMembers())
                .as("到期重试应原子转回 pending 队列")
                .containsExactly("retry-due");
        assertThat(stateField("retry-due", "status")).isEqualTo("QUEUED");
        assertThat(redisTemplate.opsForZSet().zCard(RedisKeys.taskRetry())).isZero();
        assertThat(taskQueue.retryCount()).isZero();
    }

    @Test
    void shouldNotDispatchRetryBeforeDueAt() {
        // 未来到期策略: backoff=1h → dueAt 在远未来, 本轮扫描不得投递
        RetryPolicyProperties futurePolicy = new RetryPolicyProperties();
        futurePolicy.setBackoffInitialMs(3_600_000L);
        taskQueue.push("retry-future");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-future");
        taskQueue.recordRetryableFailure("retry-future", "E", "later", futurePolicy);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).as("dueAt 未到的重试不得被投递").isZero();
        assertThat(pendingMembers()).isEmpty();
        assertThat(stateField("retry-future", "status")).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void shouldDispatchEachDueRetryExactlyOnceUnderConcurrentDispatchers() {
        RetryPolicyProperties policy = immediatePolicy();
        taskQueue.push("retry-race");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-race");
        taskQueue.recordRetryableFailure("retry-race", "E", "race", policy);

        // 两线程同时 dispatchDueRetries — ZREM 唯一赢家语义保证恰好重投一份
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalDispatched = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    totalDispatched.addAndGet(taskQueue.dispatchDueRetries());
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    fail("并发重投未在超时内完成");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("并发重投测试被中断");
            }
        }

        assertThat(pendingMembers())
                .as("并发 dispatch 下任务在 pending 中必须恰好一份(ZREM 唯一赢家)")
                .containsExactly("retry-race");
        assertThat(stateField("retry-race", "status")).isEqualTo("QUEUED");
        assertThat(totalDispatched.get())
                .as("两轮扫描合计只应有一个赢家报告重投")
                .isEqualTo(1);
    }

    // ============ AC3: 尝试耗尽落死信 + 重启边界 ============

    @Test
    void shouldDeadLetterWhenAttemptsExhaustedAndPreserveAuditFields() {
        RetryPolicyProperties policy = immediatePolicy();
        // 全链路: 3 次尝试(初始 + 2 次重投), 第 3 次失败时 newAttempt=3 >= maxAttempts → 死信
        taskQueue.push("retry-doomed");
        for (int round = 0; round < 2; round++) {
            assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-doomed");
            assertThat(taskQueue.recordRetryableFailure(
                    "retry-doomed", "E", "fail-round-" + round, policy).outcome())
                    .isEqualTo(TaskQueue.RetryOutcome.RETRY_SCHEDULED);
            assertThat(taskQueue.dispatchDueRetries()).isEqualTo(1);
        }
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-doomed");

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "retry-doomed", "FATAL_ERROR", "final failure", policy);

        assertThat(advance.outcome())
                .as("attempt 达 maxAttempts 时应同脚本落死信而非再排期")
                .isEqualTo(TaskQueue.RetryOutcome.DEAD_LETTERED);
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .contains("retry-doomed");
        assertThat(stateField("retry-doomed", "status")).isEqualTo("DEAD_LETTER");
        assertThat(stateField("retry-doomed", "attempt")).isEqualTo("3");
        assertThat(stateField("retry-doomed", "lastErrorSummary")).isEqualTo("final failure");
        assertThat(stateField("retry-doomed", "lastErrorCode"))
                .as("死信审计应保留最终错误码(10.5 review F4)")
                .isEqualTo("FATAL_ERROR");
        assertThat(stateField("retry-doomed", "taskId"))
                .as("死信 state 应保留 taskId 审计字段")
                .isEqualTo("retry-doomed");
        assertThat(stateField("retry-doomed", "deadLetteredAt"))
                .as("死信记录应保留进入时间")
                .isNotNull();
        assertThat(taskQueue.retryCount()).as("耗尽后 retry ZSET 应清空").isZero();
    }

    @Test
    void shouldNotReDispatchRetryOrDeadLetterMembersOnRecoveryRestartBoundary() {
        RetryPolicyProperties policy = immediatePolicy();
        // RETRY_SCHEDULED 成员(AC3: 重启恢复不重新投递延迟重试任务)
        taskQueue.push("retry-scheduled");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-scheduled");
        taskQueue.recordRetryableFailure("retry-scheduled", "E", "pending retry", policy);

        // DEAD_LETTER 成员
        taskQueue.push("retry-dead");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead");
        taskQueue.markDeadLetter("retry-dead", "E_TEST", "permanent");

        // 真实 PROCESSING 成员(模拟崩溃残留, 应被恢复)
        redisTemplate.opsForSet().add(RedisKeys.taskProcessing(), "task-stuck");
        putState("task-stuck", "PROCESSING", null);

        int recovered = taskQueue.recoverProcessingTasks();

        assertThat(recovered).as("恢复只应重排 PROCESSING 成员").isEqualTo(1);
        assertThat(pendingMembers())
                .as("重启恢复不得重投 RETRY_SCHEDULED / DEAD_LETTER 成员")
                .containsExactly("task-stuck");
        assertThat(stateField("retry-scheduled", "status"))
                .as("重试排期任务的 state 不得被恢复触碰")
                .isEqualTo("RETRY_SCHEDULED");
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "retry-scheduled"))
                .as("retry ZSET 成员应原样保留, 由调度器按 dueAt 重投")
                .isNotNull();
        assertThat(stateField("retry-dead", "status")).isEqualTo("DEAD_LETTER");
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .contains("retry-dead");
    }

    // ============ AC4: 人工补跑 ============

    @Test
    void shouldReplayDeadLetterWithOriginalAuditPreservedAndCorrelation() {
        taskQueue.push("retry-dead3");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead3");
        taskQueue.markDeadLetter("retry-dead3", "E_TEST", "permanent failure");

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("retry-dead3", "manual-1");

        // 新任务: QUEUED + pending + replayedFrom 关联(F-R1: 新 taskId 由 requestId 确定性派生)
        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);
        String newTaskId = result.newTaskId();
        assertThat(newTaskId).isEqualTo("retry-dead3:replay:manual-1");
        assertThat(stateField(newTaskId, "updatedAt"))
                .as("新任务 updatedAt 必须是合法时间戳而非 taskId(F9 参数位回归守卫)")
                .isNotBlank();
        assertThat(pendingMembers()).containsExactly(newTaskId);
        assertThat(stateField(newTaskId, "status")).isEqualTo("QUEUED");
        assertThat(stateField(newTaskId, "replayedFrom")).isEqualTo("retry-dead3");

        // 原死信审计完整保留: dead-letter 集合成员 + DEAD_LETTER 终态 + 失败摘要
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .contains("retry-dead3");
        assertThat(stateField("retry-dead3", "status")).isEqualTo("DEAD_LETTER");
        assertThat(stateField("retry-dead3", "lastErrorSummary")).isEqualTo("permanent failure");
        assertThat(stateField("retry-dead3", "deadLetteredAt")).isNotNull();
    }

    @Test
    void shouldRejectReplayForNonDeadLetterTask() {
        taskQueue.push("task-alive");
        assertThat(stateField("task-alive", "status")).isEqualTo("QUEUED");

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-alive", null);

        assertThat(result.outcome())
                .as("仅死信任务可补跑, 非死信必须拒绝")
                .isEqualTo(TaskQueue.ReplayOutcome.NOT_DEAD_LETTER);
        assertThat(result.newTaskId()).isNull();
        assertThat(pendingMembers())
                .as("拒绝路径不得入队任何任务")
                .containsExactly("task-alive");
    }

    @Test
    void shouldRejectReplayWhenDeadLetterMemberStateInconsistent() {
        // F10: 死信集合成员但 state 缺失(数据不一致) → 拒绝补跑防幽灵任务
        redisTemplate.opsForSet().add(RedisKeys.taskDeadLetter(), "task-ghost");
        // 不写 state: 模拟成员在集合但 state 丢失的不一致形态

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-ghost", null);

        assertThat(result.outcome())
                .isEqualTo(TaskQueue.ReplayOutcome.ORIGINAL_STATE_INCONSISTENT);
        assertThat(result.newTaskId()).isNull();
        assertThat(pendingMembers()).as("不一致拒绝路径不得入队").isEmpty();
    }

    @Test
    void shouldNotDeadLetterAbnormalRetryMemberOnDispatch() {
        // retry 成员存在但 state 非 RETRY_SCHEDULED(异常) → 原子隔离, 不自动定死信终态
        redisTemplate.opsForZSet().add(RedisKeys.taskRetry(), "task-odd",
                System.currentTimeMillis() - 1_000);
        putState("task-odd", "PROCESSING", null);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).as("异常状态成员不计入重投数").isZero();
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .as("异常状态重投不得自动落死信(10.5 review F7)")
                .isEmpty();
        assertThat(stateField("task-odd", "status"))
                .as("异常状态成员的 state 不得被重投脚本改写")
                .isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "task-odd"))
                .as("异常成员已由原子迁移清除, 不再滞留扫描区间")
                .isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetryQuarantine(), "task-odd"))
                .as("异常 state 成员必须保留在持久隔离区, 不得从所有索引中消失")
                .isNotNull();
    }

    @Test
    void shouldQuarantineRetryMemberWhenItsStateIsMissing() {
        redisTemplate.opsForZSet().add(RedisKeys.taskRetry(), "task-missing-state",
                System.currentTimeMillis() - 1_000);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).isZero();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "task-missing-state"))
                .isNull();
        assertThat(redisTemplate.opsForZSet().score(
                RedisKeys.taskRetryQuarantine(), "task-missing-state"))
                .as("缺 state 的到期成员仍须保留可审计处置路径")
                .isNotNull();
        assertThat(pendingMembers()).isEmpty();
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter())).isEmpty();
    }

    @Test
    void shouldThrowNonRetryableAndWriteNothingWhenAttemptFieldCorrupt() {
        // F2/F5: state attempt 字段损坏 → 写前校验拒绝, 任务不脱离状态机
        taskQueue.push("retry-corrupt");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-corrupt");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("retry-corrupt"), "attempt", "not-a-number");

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "retry-corrupt", "E", "m", immediatePolicy()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("attempt 字段非数字");

        assertThat(stateField("retry-corrupt", "status"))
                .as("损坏拒绝路径不得改写状态")
                .isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "retry-corrupt"))
                .as("损坏拒绝路径不得排期重试")
                .isNull();
        assertThat(taskQueue.retryCount()).isZero();
    }

    @Test
    void shouldSkipPushWithoutErrorWhenTaskInRetryBackoffWindow() {
        // F6: 已在 retry ZSET 的任务再次 push → 幂等跳过保留退避节奏, 不入 pending
        RetryPolicyProperties policy = immediatePolicy();
        taskQueue.push("retry-backoff");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-backoff");
        taskQueue.recordRetryableFailure("retry-backoff", "E", "m", policy);
        assertThat(taskQueue.retryCount()).isEqualTo(1);

        taskQueue.push("retry-backoff");

        assertThat(pendingMembers())
                .as("退避窗口内的重复 push 不得提前入队")
                .isEmpty();
        assertThat(stateField("retry-backoff", "status"))
                .as("退避节奏保留: 状态仍是 RETRY_SCHEDULED")
                .isEqualTo("RETRY_SCHEDULED");
        assertThat(taskQueue.retryCount()).isEqualTo(1);
    }

    // ============ 10.5 review 二轮: F-R2 attempt 数值规范化 / F-R6 隔离区 / F-R7 负 score ============

    @Test
    void shouldNormalizeDecimalAttemptStringWhenSchedulingRetry() {
        // F-R2: "1.0" 是 tonumber 接受而 HINCRBY 拒绝的数值字符串 —
        // HSET 写回规范化整数, 同时完成解析+递增+规范化, 任务不脱离状态机
        taskQueue.push("retry-decimal");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-decimal");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("retry-decimal"), "attempt", "1.0");

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "retry-decimal", "E", "m", immediatePolicy());

        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.RETRY_SCHEDULED);
        assertThat(stateField("retry-decimal", "attempt"))
                .as("小数形态 attempt 应被规范化为整数 2")
                .isEqualTo("2");
    }

    @Test
    void shouldNormalizeExponentAttemptStringWhenSchedulingRetry() {
        // F-R2: "1e0" 同属 tonumber 接受的数值形态 → 规范化为整数
        taskQueue.push("retry-exponent");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-exponent");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("retry-exponent"), "attempt", "1e0");

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "retry-exponent", "E", "m", immediatePolicy());

        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.RETRY_SCHEDULED);
        assertThat(stateField("retry-exponent", "attempt")).isEqualTo("2");
    }

    @Test
    void shouldRejectNegativeAttemptStringWhenSchedulingRetry() {
        // F-R2: 负数 attempt 越界 → 写前拒绝零写入, 任务保持 PROCESSING
        taskQueue.push("retry-negative");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-negative");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("retry-negative"), "attempt", "-5");

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "retry-negative", "E", "m", immediatePolicy()))
                .isInstanceOf(NonRetryableException.class);

        assertThat(stateField("retry-negative", "status")).isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "retry-negative"))
                .as("负数拒绝路径不得排期重试")
                .isNull();
        assertThat(taskQueue.retryCount()).isZero();
    }

    @Test
    void shouldRejectBeyondBoundAttemptStringWhenSchedulingRetry() {
        // F-R2: 超过 1e15 上界的 attempt → 写前拒绝(与 Java readAttempt 阈值对齐)
        taskQueue.push("retry-huge");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-huge");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("retry-huge"), "attempt", "10000000000000000");

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "retry-huge", "E", "m", immediatePolicy()))
                .isInstanceOf(NonRetryableException.class);

        assertThat(stateField("retry-huge", "status")).isEqualTo("PROCESSING");
        assertThat(taskQueue.retryCount()).isZero();
    }

    @Test
    void shouldRecordAttemptIncrementAndErrorCodeWhenDeadLettering() {
        // F-R4: markDeadLetter 审计<b>本次执行</b>的 errorCode 与递增后 attempt
        taskQueue.push("dl-audit");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("dl-audit");
        redisTemplate.<String, String>opsForHash()
                .put(RedisKeys.taskState("dl-audit"), "attempt", "2");

        boolean moved = taskQueue.markDeadLetter("dl-audit", "API_TIMEOUT", "upstream timeout");

        assertThat(moved).isTrue();
        assertThat(stateField("dl-audit", "status")).isEqualTo("DEAD_LETTER");
        assertThat(stateField("dl-audit", "attempt"))
                .as("死信审计 attempt 应为推进后的 3(2+1)")
                .isEqualTo("3");
        assertThat(stateField("dl-audit", "lastErrorCode"))
                .as("死信审计应记录本次执行的 errorCode")
                .isEqualTo("API_TIMEOUT");
        assertThat(stateField("dl-audit", "lastErrorSummary")).isEqualTo("upstream timeout");
        assertThat(stateField("dl-audit", "deadLetteredAt")).isNotNull();
    }

    @Test
    void shouldQuarantineTypeCorruptMembersAndStillDispatchHealthyMember() {
        // F-R6: TYPE 损坏(state 键是 String 而非 hash)的到期成员 → 移入 retry-quarantine
        // 隔离区而非滞留扫描窗口; 同批健康成员正常重投(饥饿解除)
        long past = System.currentTimeMillis() - 1_000;
        for (int i = 0; i < 100; i++) {
            String badId = "task-bad-type-" + i;
            redisTemplate.opsForZSet().add(RedisKeys.taskRetry(), badId, past);
            redisTemplate.opsForValue().set(RedisKeys.taskState(badId), "corrupted-to-string");
        }
        RetryPolicyProperties policy = immediatePolicy();
        taskQueue.push("task-healthy");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("task-healthy");
        taskQueue.recordRetryableFailure("task-healthy", "E", "m", policy);

        // 首轮扫描: 前 100 个成员全是坏成员 → 全部隔离, 无重投
        int firstRound = taskQueue.dispatchDueRetries();
        assertThat(firstRound).as("坏成员占据的首轮扫描不得计入重投").isZero();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetryQuarantine(), "task-bad-type-0"))
                .as("坏成员应移入隔离区(score=移入时间)")
                .isNotNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.taskRetry(), "task-bad-type-0"))
                .as("坏成员应移出 retry ZSET, 不再占据最早批次")
                .isNull();

        // 次轮扫描: 健康成员可见且被重投(无隔离区方案时会被坏成员永久饿死)
        int secondRound = taskQueue.dispatchDueRetries();
        assertThat(secondRound).isEqualTo(1);
        assertThat(pendingMembers()).containsExactly("task-healthy");
        assertThat(stateField("task-healthy", "status")).isEqualTo("QUEUED");
    }

    @Test
    void shouldDispatchRetryMemberWithNegativeDueAtScore() {
        // F-R7: 旧数据/时钟回拨造成的负 score 成员同样到期可见(扫描下界为 -inf), 不被隐匿
        redisTemplate.opsForZSet().add(RedisKeys.taskRetry(), "task-negscore", -5_000.0);
        putState("task-negscore", "RETRY_SCHEDULED", null);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).as("负 score 成员已到期, 必须被重投").isEqualTo(1);
        assertThat(pendingMembers()).containsExactly("task-negscore");
        assertThat(stateField("task-negscore", "status")).isEqualTo("QUEUED");
        assertThat(redisTemplate.opsForZSet().zCard(RedisKeys.taskRetry())).isZero();
    }

    @Test
    void shouldNotRequeueCompletedSameDayTaskWhenPushedAgain() {
        String sameDayTaskId = "twitter:run:2026-10-01";
        taskQueue.push(sameDayTaskId);
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo(sameDayTaskId);
        taskQueue.complete(sameDayTaskId);

        taskQueue.push(sameDayTaskId);

        assertThat(stateField(sameDayTaskId, "status"))
                .as("同一逻辑任务完成后再次触发不得覆盖终态")
                .isEqualTo("COMPLETED");
        assertThat(pendingMembers()).as("不得同日重复运行")
                .doesNotContain(sameDayTaskId);
    }

    // ============ 10.5 review 二轮: F-R1 补跑幂等与可消费性 ============

    @Test
    void shouldReturnTargetConflictWhenSameRequestIdReplayedTwice() {
        // F-R1: 同 taskId + 同 requestId 的重复补跑(如网络重试)经 EXISTS 守卫原子拒绝
        taskQueue.push("retry-dead5");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead5");
        taskQueue.markDeadLetter("retry-dead5", "E_TEST", "permanent");

        TaskQueue.ReplayResult first = taskQueue.replayDeadLetter("retry-dead5", "manual-9");
        assertThat(first.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);
        assertThat(first.newTaskId()).isEqualTo("retry-dead5:replay:manual-9");

        TaskQueue.ReplayResult second = taskQueue.replayDeadLetter("retry-dead5", "manual-9");
        assertThat(second.outcome())
                .as("幂等重复补跑返回 TARGET_CONFLICT(正常重复信号, 原死信记录不受影响)")
                .isEqualTo(TaskQueue.ReplayOutcome.TARGET_CONFLICT);
        assertThat(second.newTaskId()).isNull();
        assertThat(pendingMembers())
                .as("重复补跑至多创建一份 pending 任务")
                .containsExactly("retry-dead5:replay:manual-9");
    }

    @Test
    void shouldThrowNonRetryableWhenReplayRequestIdInvalid() {
        // F-R1: requestId 非法字符 → 写前拒绝, 零脚本执行(原死信记录不受影响)
        taskQueue.push("retry-dead6");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead6");
        taskQueue.markDeadLetter("retry-dead6", "E_TEST", "permanent");

        assertThatThrownBy(() -> taskQueue.replayDeadLetter("retry-dead6", "bad id!"))
                .isInstanceOf(NonRetryableException.class);
        assertThat(pendingMembers())
                .as("非法 requestId 拒绝路径不得入队")
                .isEmpty();
        assertThat(stateField("retry-dead6", "status")).isEqualTo("DEAD_LETTER");
    }

    @Test
    void shouldConsumeReplayedTaskThroughFullLifecycle() {
        // F-R1: 死信后补跑的新任务可被正常消费(poll → complete 全链路)
        taskQueue.push("retry-dead7");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead7");
        taskQueue.markDeadLetter("retry-dead7", "E_TEST", "permanent");
        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("retry-dead7", "req-x");
        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);

        assertThat(taskQueue.poll(0, TimeUnit.SECONDS))
                .as("补跑产生的 pending 任务应可正常领取")
                .isEqualTo("retry-dead7:replay:req-x");
        taskQueue.complete("retry-dead7:replay:req-x");
        assertThat(stateField("retry-dead7:replay:req-x", "status"))
                .as("补跑任务可走完整生命周期直至 COMPLETED")
                .isEqualTo("COMPLETED");
        assertThat(stateField("retry-dead7:replay:req-x", "replayedFrom"))
                .as("COMPLETED 后 replayedFrom 审计关联保留")
                .isEqualTo("retry-dead7");
    }

    @Test
    void shouldDeriveTodayRequestIdWhenReplayRequestIdBlank() {
        // F-R1: null requestId 缺省派生当日日期 — 同日重复补跑天然幂等
        taskQueue.push("retry-dead8");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("retry-dead8");
        taskQueue.markDeadLetter("retry-dead8", "E_TEST", "permanent");

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("retry-dead8", null);
        String expectedNewTaskId = "retry-dead8:replay:"
                + java.time.LocalDate.now();

        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);
        assertThat(result.newTaskId()).isEqualTo(expectedNewTaskId);
        assertThat(pendingMembers()).containsExactly(expectedNewTaskId);
    }
}
