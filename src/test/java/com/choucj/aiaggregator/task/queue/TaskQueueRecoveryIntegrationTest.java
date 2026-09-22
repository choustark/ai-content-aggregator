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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 1.6 AC-5/6 + Story 10.4 端到端集成测试 — 真 Redis 上验证同槽键族、
 * Lua 原子领取、恢复、死信与迁移幂等.
 *
 * <p><b>测试场景(Story 10.4):</b>
 * <ol>
 *   <li>push + poll FIFO 顺序正确(同槽键族)</li>
 *   <li>poll 后任务进入 processing 集合与 state Hash,complete 后移除并置 COMPLETED</li>
 *   <li>processing 中 PROCESSING 任务由恢复原子重排回 pending;非 PROCESSING(如已完成)跳过</li>
 *   <li>并发消费者竞争领取: 每个任务恰被领取一次, 无丢失(at-most-once 丢失窗口关闭的证明)</li>
 *   <li>候选 state 键类型损坏时 CLAIM 拒绝且 pending 不变(2.2a TYPE 防护)</li>
 *   <li>不可重试失败 → DEAD_LETTER 终态, 启动恢复不复活死信任务</li>
 *   <li>迁移幂等: 先 processing 后 pending、账本跳过、完成后重跑不复活、冲突拒绝</li>
 * </ol>
 *
 * <p><b>跳过策略(沿用 Story 1.5a):</b> 本地无 Redis 时通过
 * {@link Assumptions#assumeTrue(boolean, String)} 跳过,不阻塞 CI.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("external")
class TaskQueueRecoveryIntegrationTest {

    @Autowired
    private TaskQueue taskQueue;

    @Autowired
    private TaskRecoveryRunner recoveryRunner;

    @Autowired
    private TaskKeyMigrator taskKeyMigrator;

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
                "本地 Redis 不可用,跳过 Story 10.4 集成测试");
    }

    @BeforeEach
    @AfterEach
    void cleanupTaskKeys() {
        // 新键族 + 迁移账本 + state:* + 旧键(迁移场景会写入旧键)
        redisTemplate.delete(RedisKeys.taskPending());
        redisTemplate.delete(RedisKeys.taskProcessing());
        redisTemplate.delete(RedisKeys.taskDeadLetter());
        redisTemplate.delete(RedisKeys.taskLegacyMigrated());
        Set<String> stateKeys = redisTemplate.keys(RedisKeys.taskState("*"));
        if (stateKeys != null && !stateKeys.isEmpty()) {
            redisTemplate.delete(stateKeys);
        }
        redisTemplate.delete(RedisKeys.legacyTaskQueue());
        redisTemplate.delete(RedisKeys.legacyTaskProcessing());
    }

    /** 写入测试用 state Hash(直接经 template, 测试自身不受 AC4 边界约束). */
    private void putState(String taskId, String status) {
        redisTemplate.<String, String>opsForHash().putAll(RedisKeys.taskState(taskId),
                Map.of("taskId", taskId, "status", status, "updatedAt", "2026-09-20T00:00:00Z"));
    }

    /** 读取 state Hash 的 status 字段. */
    private String stateStatus(String taskId) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        return hashOps.get(RedisKeys.taskState(taskId), "status");
    }

    /** 读取 state Hash 的 lastErrorSummary 字段. */
    private String stateErrorSummary(String taskId) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        return hashOps.get(RedisKeys.taskState(taskId), "lastErrorSummary");
    }

    // ============ FIFO 与状态跟踪 ============

    @Test
    void shouldEnqueueAndDequeueInFifoOrderOnSameSlotKeys() {
        taskQueue.push("task-a");
        taskQueue.push("task-b");
        taskQueue.push("task-c");
        // 新键族: pending List 落在 task:{queue}: 前缀(领取前断言)
        assertThat(redisTemplate.hasKey(RedisKeys.taskPending())).isTrue();

        String first = taskQueue.poll(0, TimeUnit.SECONDS);
        String second = taskQueue.poll(0, TimeUnit.SECONDS);
        String third = taskQueue.poll(0, TimeUnit.SECONDS);
        String fourth = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(first).isEqualTo("task-a");
        assertThat(second).isEqualTo("task-b");
        assertThat(third).isEqualTo("task-c");
        assertThat(fourth).as("队列耗尽应返回 null").isNull();
    }

    @Test
    void shouldTrackTaskInProcessingSetAndStateUntilComplete() {
        taskQueue.push("task-x");
        String polled = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(polled).isEqualTo("task-x");
        assertThat(taskQueue.getProcessingTasks())
                .as("poll 后任务应进入 processing 集合")
                .contains("task-x");
        assertThat(stateStatus("task-x"))
                .as("CLAIM 后 state 应置 PROCESSING")
                .isEqualTo("PROCESSING");

        taskQueue.complete("task-x");

        assertThat(taskQueue.getProcessingTasks())
                .as("complete 后任务应从 processing 集合移除")
                .doesNotContain("task-x");
        assertThat(stateStatus("task-x"))
                .as("complete 后 state 应置 COMPLETED 终态")
                .isEqualTo("COMPLETED");
    }

    @Test
    void shouldEnqueueWithQueuedStateOnPush() {
        taskQueue.push("task-queued");

        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .containsExactly("task-queued");
        assertThat(stateStatus("task-queued")).isEqualTo("QUEUED");
    }

    @Test
    void shouldKeepProcessingStateWhenSameTaskIdPushedAgainWhileProcessing() {
        // 10.4 review 修复: 在途任务被再次 push 不得把 state 覆盖成 QUEUED,
        // 否则恢复脚本只认 PROCESSING, 任务会悬挂在 processing 永不重排
        taskQueue.push("task-double");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("task-double");

        taskQueue.push("task-double");

        assertThat(stateStatus("task-double"))
                .as("在途任务的 state 不得被重复 push 覆盖")
                .isEqualTo("PROCESSING");
        assertThat(taskQueue.getProcessingTasks()).contains("task-double");
        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .as("在途任务不得被重复入队")
                .isEmpty();

        // 在途守卫后任务仍可正常完成
        taskQueue.complete("task-double");
        assertThat(stateStatus("task-double")).isEqualTo("COMPLETED");
    }

    @Test
    void shouldNotDuplicatePendingEntryWhenSameTaskIdPushedTwice() {
        taskQueue.push("task-once");
        taskQueue.push("task-once");

        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .as("同 taskId 重复 push 不得在 pending 产生重复条目(防双重消费)")
                .containsExactly("task-once");

        String first = taskQueue.poll(0, TimeUnit.SECONDS);
        String second = taskQueue.poll(0, TimeUnit.SECONDS);
        assertThat(first).isEqualTo("task-once");
        assertThat(second)
                .as("重复入队被守卫后, 队列只应有这一份任务")
                .isNull();
    }

    @Test
    void shouldClaimTaskEnqueuedDuringBlockingPoll() {
        // 10.4 review 修复: 阻塞 poll 不得因观察瞬间空队列提前返回 null
        try (ExecutorService enqueuer = Executors.newSingleThreadExecutor()) {
            enqueuer.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                taskQueue.push("task-late");
                return null;
            });

            String claimed = taskQueue.poll(3, TimeUnit.SECONDS);

            assertThat(claimed)
                    .as("阻塞 poll 期间任务入队应被领取")
                    .isEqualTo("task-late");
        }
    }

    // ============ 恢复 ============

    @Test
    void shouldRecoverProcessingTasksAtomicallyByRequeuing() {
        // 模拟进程崩溃后: 任务在 processing 集合且 state 仍为 PROCESSING(真实领取中断的形态)
        redisTemplate.opsForSet().add(RedisKeys.taskProcessing(), "task-stuck-1", "task-stuck-2");
        putState("task-stuck-1", "PROCESSING");
        putState("task-stuck-2", "PROCESSING");

        int recovered = taskQueue.recoverProcessingTasks();

        assertThat(recovered).isEqualTo(2);
        assertThat(redisTemplate.opsForList().size(RedisKeys.taskPending()))
                .as("恢复应把 PROCESSING 任务重排回 pending")
                .isEqualTo(2);
        assertThat(redisTemplate.opsForSet().size(RedisKeys.taskProcessing()))
                .as("恢复后 processing 集合应清空")
                .isZero();
        assertThat(stateStatus("task-stuck-1"))
                .as("恢复后 state 应翻转回 QUEUED")
                .isEqualTo("QUEUED");
    }

    @Test
    void shouldBeNoOpWhenProcessingIsEmpty() {
        recoveryRunner.recoverPendingTasks();

        assertThat(redisTemplate.opsForList().size(RedisKeys.taskPending()))
                .as("空 processing 集合下恢复不应入队任何任务")
                .isZero();
    }

    @Test
    void shouldSkipMemberWhoseStateIsNotProcessingWhenRecovering() {
        // 成员挂在 processing 但 state 已是 COMPLETED(与完成并发后的残留形态) — 恢复不得复活
        redisTemplate.opsForSet().add(RedisKeys.taskProcessing(), "task-done-already");
        putState("task-done-already", "COMPLETED");

        int recovered = taskQueue.recoverProcessingTasks();

        assertThat(recovered).as("非 PROCESSING 成员应被跳过").isZero();
        assertThat(redisTemplate.opsForList().size(RedisKeys.taskPending()))
                .as("已完成的任务不得被重排回 pending")
                .isZero();
        assertThat(stateStatus("task-done-already"))
                .as("跳过时不得覆盖终态")
                .isEqualTo("COMPLETED");
    }

    // ============ 原子领取与无丢失证明 ============

    @Test
    void shouldClaimEachTaskExactlyOnceUnderConcurrentConsumers() {
        int taskCount = 30;
        int consumerCount = 8;
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            tasks.add("race-task-" + i);
            taskQueue.push("race-task-" + i);
        }

        Set<String> claimed = ConcurrentHashMap.newKeySet();
        List<String> duplicated = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        // 每个消费者领到第一个任务后停在 release 前 — 任务留在 processing(未完成),
        // 让主线程能在"领取瞬间"断言互斥与守恒(10.4 review 修复: 终态断言观察不到中间态)
        CountDownLatch allHeld = new CountDownLatch(consumerCount);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(consumerCount);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            for (int i = 0; i < consumerCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    boolean heldOnce = false;
                    while (true) {
                        String taskId = taskQueue.poll(1, TimeUnit.SECONDS);
                        if (taskId == null) {
                            return;
                        }
                        if (!claimed.add(taskId)) {
                            duplicated.add(taskId);
                        }
                        if (!heldOnce) {
                            heldOnce = true;
                            allHeld.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        taskQueue.complete(taskId);
                    }
                });
            }
            start.countDown();
            assertThat(allHeld.await(30, TimeUnit.SECONDS))
                    .as("所有消费者应各领取到一个任务并停在 processing 持有态")
                    .isTrue();

            // ===== 领取瞬间中间态断言(AC5 无丢失证明的核心) =====
            List<String> pendingNow =
                    redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1);
            Set<String> processingNow = taskQueue.getProcessingTasks();
            assertThat(Collections.disjoint(
                    new HashSet<>(pendingNow), processingNow))
                    .as("领取瞬间 pending 与 processing 必须互斥(无任务同时存在于两者)")
                    .isTrue();
            assertThat(pendingNow.size() + processingNow.size())
                    .as("领取瞬间总数守恒: pending + processing = 入队总数(无任务丢失或复制)")
                    .isEqualTo(taskCount);
            assertThat(claimed)
                    .as("已领取集合必须与 processing 集合精确一致(原子领取即登记)")
                    .containsExactlyInAnyOrderElementsOf(processingNow);
            assertThat(processingNow)
                    .allSatisfy(id -> assertThat(stateStatus(id))
                            .as("领取瞬间每个 processing 成员的 state 必须已登记 PROCESSING")
                            .isEqualTo("PROCESSING"));

            // 放行消费者完成全部消费
            release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                timedOut.set(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("并发领取测试被中断");
        } finally {
            pool.shutdownNow();
        }

        assertThat(timedOut.get()).as("并发消费应在超时内完成").isFalse();
        assertThat(duplicated)
                .as("并发竞争下每个任务只能被一个消费者领取(无重复领取)")
                .isEmpty();
        assertThat(claimed)
                .as("所有任务应被领取且各恰一次(无丢失)")
                .containsExactlyInAnyOrderElementsOf(tasks);
        assertThat(taskQueue.pendingCount())
                .as("全部领取后 pending 应清空")
                .isZero();
        assertThat(taskQueue.processingCount())
                .as("全部完成后 processing 应清空")
                .isZero();
        assertThat(taskQueue.getProcessingTasks()).isEmpty();
    }

    @Test
    void shouldRejectClaimAndKeepPendingWhenCandidateStateKeyHasWrongType() {
        taskQueue.push("task-poisoned");
        // 预置候选 state 键为 String 类型(损坏形态) — CLAIM 的 TYPE 校验应拒绝
        redisTemplate.opsForValue().set(RedisKeys.taskState("task-poisoned"), "junk");

        assertThatThrownBy(() -> taskQueue.poll(0, TimeUnit.SECONDS))
                .as("TYPE 校验拒绝应映射 NonRetryableException(数据损坏需运维介入)")
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝");

        // 拒绝路径不得修改任何键: 任务仍留在 pending 队首, 未进入 processing
        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .as("脚本拒绝时 pending 队列不得被修改")
                .containsExactly("task-poisoned");
        assertThat(taskQueue.getProcessingTasks())
                .as("脚本拒绝时 processing 集合不得被修改")
                .isEmpty();
    }

    // ============ 失败终态 ============

    @Test
    void shouldMoveFailedTaskToDeadLetterAndNeverReviveItOnRecovery() {
        taskQueue.push("task-doomed");
        assertThat(taskQueue.poll(0, TimeUnit.SECONDS)).isEqualTo("task-doomed");

        boolean moved = taskQueue.markDeadLetter("task-doomed", "permanent\nfailure");

        assertThat(moved).as("在 processing 中的任务应能移入死信").isTrue();
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .contains("task-doomed");
        assertThat(stateStatus("task-doomed"))
                .as("死信终态应为 DEAD_LETTER 而非 COMPLETED")
                .isEqualTo("DEAD_LETTER");
        assertThat(stateErrorSummary("task-doomed"))
                .as("失败原因应脱敏(换行折叠为空格)后入账")
                .isEqualTo("permanent failure");

        // 死信后重复 complete 是幂等 no-op 且不覆盖 DEAD_LETTER 终态
        taskQueue.complete("task-doomed");
        assertThat(stateStatus("task-doomed")).isEqualTo("DEAD_LETTER");

        // 启动恢复不得把死信任务重新投递
        int recovered = taskQueue.recoverProcessingTasks();
        assertThat(recovered).isZero();
        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .as("死信任务不得重新入队")
                .isEmpty();

        // 普通 cron/push 也不得绕过人工补跑边界自动复活固定 taskId
        assertThatThrownBy(() -> taskQueue.push("task-doomed"))
                .as("死信任务只能由显式人工补跑命令重新入队")
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("仅允许显式人工补跑");
        assertThat(stateStatus("task-doomed")).isEqualTo("DEAD_LETTER");
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .contains("task-doomed");
        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .isEmpty();
    }

    @Test
    void shouldReturnFalseWhenMarkingDeadLetterForUnknownTask() {
        assertThat(taskQueue.markDeadLetter("task-ghost", "reason")).isFalse();
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskDeadLetter()))
                .as("不在 processing 的任务不得入死信")
                .isNullOrEmpty();
    }

    // ============ 端到端 ============

    @Test
    void shouldCompleteProcessAllScenarioEndToEnd() {
        taskQueue.push("e2e-1");
        taskQueue.push("e2e-2");

        Set<String> polled = new HashSet<>();
        String taskId;
        while ((taskId = taskQueue.poll(0, TimeUnit.SECONDS)) != null) {
            polled.add(taskId);
            taskQueue.complete(taskId);
        }

        assertThat(polled).containsExactlyInAnyOrder("e2e-1", "e2e-2");
        assertThat(taskQueue.getProcessingTasks())
                .as("所有任务 complete 后 processing 集合应空")
                .isEmpty();
    }

    // ============ 迁移幂等 ============

    @Test
    void shouldMigrateLegacyKeysWithProcessingFirstAndLedgerIdempotency() {
        // 旧键形态(Story 10.4 之前): task:processing Set + task:queue List
        redisTemplate.opsForSet().add(RedisKeys.legacyTaskProcessing(), "legacy-in-flight");
        redisTemplate.opsForList().rightPushAll(RedisKeys.legacyTaskQueue(),
                List.of("legacy-queued", "legacy-in-flight"));

        TaskKeyMigrator.MigrationSummary first = taskKeyMigrator.migrate();

        // processing 先迁先打标(1); pending 中 legacy-queued 迁入(2), 同 ID 的 pending 形态
        // 因账本已标记被跳过 — processing 形态优先, 不重复入队
        assertThat(first.legacyProcessingCount()).isEqualTo(1);
        assertThat(first.legacyPendingCount()).isEqualTo(2);
        assertThat(first.migratedCount()).isEqualTo(2);
        assertThat(first.skippedCount()).isEqualTo(1);
        assertThat(first.conflictCount()).isZero();

        // 新键族落地验证: in-flight 只出现一次, 在 processing 集合而非 pending
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskProcessing()))
                .contains("legacy-in-flight");
        assertThat(redisTemplate.opsForList().range(RedisKeys.taskPending(), 0, -1))
                .containsExactly("legacy-queued");
        assertThat(stateStatus("legacy-in-flight")).isEqualTo("PROCESSING");
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskLegacyMigrated()))
                .containsExactlyInAnyOrder("legacy-in-flight", "legacy-queued");

        // 消费迁移结果(模拟真实处理): 先消费迁入的 pending, 再经恢复重排在处理中的
        // legacy-in-flight 并完成 — 覆盖"迁移后任务全部处理完"的真实时序
        String taskId;
        while ((taskId = taskQueue.poll(0, TimeUnit.SECONDS)) != null) {
            taskQueue.complete(taskId);
        }
        recoveryRunner.recoverPendingTasks();
        while ((taskId = taskQueue.poll(0, TimeUnit.SECONDS)) != null) {
            taskQueue.complete(taskId);
        }
        assertThat(taskQueue.getProcessingTasks()).isEmpty();
        assertThat(taskQueue.pendingCount()).isZero();

        // 重跑迁移: 账本命中全部跳过 — 已完成任务不得复活入队(幂等证明)
        TaskKeyMigrator.MigrationSummary rerun = taskKeyMigrator.migrate();

        assertThat(rerun.migratedCount())
                .as("账本打标后重跑迁移应全部跳过")
                .isZero();
        assertThat(rerun.skippedCount()).isEqualTo(3);
        assertThat(redisTemplate.opsForList().size(RedisKeys.taskPending()))
                .as("已完成任务不得被迁移复活")
                .isZero();
        assertThat(taskQueue.pendingCount()).isZero();
    }

    @Test
    void shouldRejectOverwriteWhenNewStateExistsWithoutLedgerMarker() {
        redisTemplate.opsForList().rightPushAll(RedisKeys.legacyTaskQueue(), List.of("clash"));
        // 新键族已存在同 ID 的 state(新版已处理过该固定 ID)
        putState("clash", "COMPLETED");

        TaskKeyMigrator.MigrationSummary summary = taskKeyMigrator.migrate();

        assertThat(summary.conflictCount())
                .as("新 state 已存在且账本未标记 → 拒绝覆盖计入冲突")
                .isEqualTo(1);
        assertThat(summary.migratedCount()).isZero();
        assertThat(stateStatus("clash"))
                .as("冲突路径不得改写现有新 state")
                .isEqualTo("COMPLETED");
        assertThat(redisTemplate.opsForSet().members(RedisKeys.taskLegacyMigrated()))
                .as("冲突路径不得打迁移账本")
                .isNullOrEmpty();
        assertThat(redisTemplate.opsForList().size(RedisKeys.taskPending()))
                .as("冲突路径不得把任务迁入新 pending")
                .isZero();
    }

    @Test
    void shouldReturnZeroSummaryWhenLegacyKeysDoNotExist() {
        TaskKeyMigrator.MigrationSummary summary = taskKeyMigrator.migrate();

        assertThat(summary.legacyProcessingCount()).isZero();
        assertThat(summary.legacyPendingCount()).isZero();
        assertThat(summary.migratedCount()).isZero();
    }
}
