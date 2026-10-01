package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.common.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.5 {@link TaskQueue} 重试/到期重投/补跑/只读计数单测 —
 * 验证 RETRY_SCHEDULE / RETRY_DISPATCH / REPLAY 三脚本的 KEYS/ARGV 组装、
 * 返回码语义映射(排期=1 / 耗尽=2 / 不在集合=0 / 损坏=-2 / 非死信=0 /
 * 原状态不一致=3 / 冲突=2 / TYPE 拒绝=-1)、退避饱和防护与净化纪律
 * (10.5 review 修复覆盖: F2/F3/F5/F6/F7/F8/F10/F11)。
 *
 * <p><b>Mock 策略:</b> 沿用 {@code TaskQueueTest} — 脚本执行经
 * {@code stringRedisTemplate.execute(RedisScript, List, Object...)} stub + ArgumentCaptor;
 * attempt 预读经 {@code opsForHash().get(state, "attempt")} stub。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TaskQueueRetryReplayTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private SlowOperationRecorder slowOperationRecorder;

    private TaskQueue taskQueue;

    private RetryPolicyProperties policy;

    @BeforeEach
    void setUp() {
        lenient().when(slowOperationRecorder.observe(
                        any(), any(), any(), any(Duration.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        taskQueue = new TaskQueue(stringRedisTemplate, slowOperationRecorder);
        policy = new RetryPolicyProperties();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubExecute(Long... results) {
        org.mockito.stubbing.OngoingStubbing<Object> stubbing =
                (org.mockito.stubbing.OngoingStubbing) when(
                        stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                                any(Object[].class)));
        for (Long result : results) {
            stubbing = stubbing.thenReturn(result);
        }
    }

    // ============ recordRetryableFailure ============

    @Test
    void should_schedule_retry_with_expected_keys_and_args() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 首次失败: attempt 预读为 0 → delayForAttempt(1) = backoff-initial-ms
        when(hashOps.get(RedisKeys.taskState("task-1"), "attempt")).thenReturn(null);
        stubExecute(1L);

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "task-1", "REDIS_CONNECTION_ERROR", "conn refused\nbad\tchars", policy);

        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.RETRY_SCHEDULED);
        assertThat(advance.attempt()).isEqualTo(1);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                argsCaptor.capture());
        // RETRY_SCHEDULE 4 键同 slot: processing / retry / dead-letter(耗尽分支) / state
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskProcessing(), RedisKeys.taskRetry(),
                RedisKeys.taskDeadLetter(), RedisKeys.taskState("task-1"));
        assertThat(argsCaptor.getValue()[0]).isEqualTo("task-1");
        // ARGV: [taskId, now, dueAt, errorCode, errorSummary, maxAttempts]
        assertThat(argsCaptor.getValue()).hasSize(6);
        long dueAt = Long.parseLong((String) argsCaptor.getValue()[2]);
        // 首次失败延迟 = backoff-initial-ms(60s), 允许测试执行耗时带来的毫秒漂移
        assertThat(dueAt).isBetween(
                System.currentTimeMillis() + policy.getBackoffInitialMs() - 5_000,
                System.currentTimeMillis() + policy.getBackoffInitialMs());
        // 审计返回: dueAtEpochMs 与脚本入参一致(W11 日志不再二次读 Redis)
        assertThat(advance.dueAtEpochMs()).isEqualTo(dueAt);
        // 净化: 换行/制表符折叠为空格(N4)
        assertThat(argsCaptor.getValue()[3]).isEqualTo("REDIS_CONNECTION_ERROR");
        assertThat(argsCaptor.getValue()[4]).isEqualTo("conn refused bad chars");
        assertThat(argsCaptor.getValue()[5]).isEqualTo("3");
    }

    @Test
    void should_pre_read_attempt_for_backoff_delay_calculation() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 已失败 1 次(attempt=1) → 下次是第 2 次 → delayForAttempt(2) = 2x initial
        when(hashOps.get(RedisKeys.taskState("task-1"), "attempt")).thenReturn("1");
        stubExecute(1L);

        TaskQueue.RetryAdvance advance =
                taskQueue.recordRetryableFailure("task-1", "E", "m", policy);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        long dueAt = Long.parseLong((String) argsCaptor.getValue()[2]);
        assertThat(dueAt).isBetween(
                System.currentTimeMillis() + 2 * policy.getBackoffInitialMs() - 5_000,
                System.currentTimeMillis() + 2 * policy.getBackoffInitialMs());
        assertThat(advance.attempt()).isEqualTo(2);
    }

    @Test
    void should_cap_due_at_long_max_when_backoff_delay_overflows() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 极端合法配置: 退避上限逼近 Long.MAX → now+delay 溢出, dueAt 必须饱和为 Long.MAX
        // 而非翻负(负 score 永不落入到期扫描区间, 任务会永久滞留 — 10.5 review F3)
        RetryPolicyProperties extremePolicy = new RetryPolicyProperties();
        extremePolicy.setBackoffInitialMs(Long.MAX_VALUE - 10);
        extremePolicy.setBackoffMaxMs(Long.MAX_VALUE - 10);
        when(hashOps.get(RedisKeys.taskState("task-1"), "attempt")).thenReturn(null);
        stubExecute(1L);

        TaskQueue.RetryAdvance advance =
                taskQueue.recordRetryableFailure("task-1", "E", "m", extremePolicy);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[2])
                .as("dueAt 溢出必须饱和为 Long.MAX 而非翻负")
                .isEqualTo(String.valueOf(Long.MAX_VALUE));
        assertThat(advance.dueAtEpochMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void should_return_dead_lettered_when_retry_exhausted() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        stubExecute(2L);

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "task-1", "E", "m", policy);

        // 脚本返回 2 = newAttempt >= maxAttempts, 同脚本原子落死信
        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.DEAD_LETTERED);
        assertThat(advance.attempt()).isEqualTo(1);
        assertThat(advance.dueAtEpochMs()).isEqualTo(-1);
    }

    @Test
    void should_return_not_in_processing_when_task_not_member() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        stubExecute(0L);

        TaskQueue.RetryAdvance advance = taskQueue.recordRetryableFailure(
                "task-1", "E", "m", policy);

        // SISMEMBER==0: 任务已被并发恢复/完成迁出 processing, 幂等跳过
        assertThat(advance.outcome()).isEqualTo(TaskQueue.RetryOutcome.NOT_IN_PROCESSING);
        assertThat(advance.attempt()).isEqualTo(-1);
    }

    @Test
    void should_throw_non_retryable_when_script_reports_corrupt_attempt_state() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        // 脚本 -2 = state attempt/maxAttempts 数值字段损坏(写前校验拒绝, 未写任何键)
        stubExecute(-2L);

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "task-1", "E", "m", policy))
                .as("损坏的 attempt 字段若照常推进会让任务脱离状态机, 必须拒绝并暴露")
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("数值字段损坏")
                .hasMessageContaining("task-1")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void should_throw_non_retryable_when_null_task_id_on_record_retryable_failure() {
        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                null, "E", "m", policy))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("taskId 不能为 null");

        verifyNoInteractions(hashOps);
    }

    @Test
    void should_throw_non_retryable_when_retry_schedule_type_check_rejected() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        stubExecute(-1L);

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "task-1", "E", "m", policy))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝");
    }

    @Test
    void should_map_connection_failure_to_retryable_on_record_retryable_failure() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> taskQueue.recordRetryableFailure(
                "task-1", "E", "m", policy))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    // ============ dispatchDueRetries ============

    @Test
    void should_dispatch_due_task_with_expected_keys() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L))).thenReturn(Set.of("task-due"));
        stubExecute(1L);

        int dispatched = taskQueue.dispatchDueRetries();

        assertThat(dispatched).isEqualTo(1);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        // RETRY_DISPATCH 5 键同 slot: retry / pending / dead-letter / state / quarantine
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskRetry(), RedisKeys.taskPending(),
                RedisKeys.taskDeadLetter(), RedisKeys.taskState("task-due"),
                RedisKeys.taskRetryQuarantine());
    }

    @Test
    void should_return_zero_without_script_when_no_due_retries() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L))).thenReturn(Set.of());

        assertThat(taskQueue.dispatchDueRetries()).isZero();
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_not_count_abnormal_state_member_as_dispatched() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L))).thenReturn(Set.of("task-odd"));
        // 脚本返回 2 = ZREM 赢了但 state 非 RETRY_SCHEDULED → 脚本未写任何键, 未重投,
        // 只告警(10.5 review F7: 异常状态处置交运维, 不得计入重投数虚高指标)
        stubExecute(2L);

        assertThat(taskQueue.dispatchDueRetries()).isZero();
    }

    @Test
    void should_not_count_lost_zrem_race_as_dispatched() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L))).thenReturn(Set.of("task-raced"));
        // 脚本返回 0 = 并发实例已 ZREM 赢家, 本轮放弃
        stubExecute(0L);

        assertThat(taskQueue.dispatchDueRetries()).isZero();
    }

    @Test
    void should_skip_corrupt_member_and_continue_batch_dispatch() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 有序集合保证坏成员先处理, 好成员后处理
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L)))
                .thenReturn(new LinkedHashSet<>(List.of("task-corrupt", "task-good")));
        // 首次调用 TYPE 拒绝(-1 → NonRetryableException), 第二次正常重投
        stubExecute(-1L, 1L);

        int dispatched = taskQueue.dispatchDueRetries();

        // F8 + F-R6: 单个损坏成员只记 ERROR 并移入 retry-quarantine 隔离区,
        // 不卡死整批(卡死/不移除都会让坏成员持续占据最早批次, 后续合法到期任务饥饿)
        assertThat(dispatched).isEqualTo(1);
        // 3 次脚本执行 = TYPE 拒绝(损坏) + 隔离区迁入 + 好成员正常重投
        verify(stringRedisTemplate, org.mockito.Mockito.times(3))
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void should_propagate_connection_failure_and_abort_batch_dispatch() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq(RedisKeys.taskRetry()), eq(Double.NEGATIVE_INFINITY), any(Double.class),
                eq(0L), eq(100L))).thenReturn(Set.of("task-a", "task-b"));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        // F8: Redis 连接异常向上传播中止本轮 — 连接不可用时逐成员继续只会级联失败
        assertThatThrownBy(() -> taskQueue.dispatchDueRetries())
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    // ============ replayDeadLetter ============

    @Test
    void should_replay_dead_letter_with_deterministic_task_id_from_request_id() {
        stubExecute(1L);

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-dead", "req-1");

        // 新 taskId 确定性派生 <orig>:replay:<requestId>(F-R1 幂等模型: 同 taskId+同
        // requestId 的重复补跑经 REPLAY 脚本 EXISTS 守卫原子拒绝, TARGET_CONFLICT 即幂等信号)
        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);
        String newTaskId = result.newTaskId();
        assertThat(newTaskId).isEqualTo("task-dead:replay:req-1");
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                argsCaptor.capture());
        // REPLAY 4 键同 slot: dead-letter / pending / state:orig / state:new
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskDeadLetter(), RedisKeys.taskPending(),
                RedisKeys.taskState("task-dead"), RedisKeys.taskState(newTaskId));
        assertThat(argsCaptor.getValue()[0]).isEqualTo("task-dead");
        assertThat(argsCaptor.getValue()[1]).isEqualTo(newTaskId);
        // ARGV[2] 是时间戳(新 state 的 updatedAt), 与 taskId 参数位不同(F9 修复)
        assertThat(argsCaptor.getValue()).hasSize(3);
        assertThat(argsCaptor.getValue()[2]).isNotEqualTo(newTaskId);
    }

    @Test
    void should_default_request_id_to_today_when_blank() {
        // null/blank requestId 缺省派生为当日日期 — 同日重复补跑天然幂等
        stubExecute(1L);
        String expectedNewTaskId = "task-dead:replay:" + java.time.LocalDate.now();

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-dead", null);

        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.REPLAYED);
        assertThat(result.newTaskId()).isEqualTo(expectedNewTaskId);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).contains(
                RedisKeys.taskState("task-dead"), RedisKeys.taskState(expectedNewTaskId));
    }

    @Test
    void should_throw_non_retryable_when_replay_request_id_invalid() {
        // requestId 含非法字符(空格/感叹号)或超 64 字符 → 写前拒绝, 零脚本执行
        assertThatThrownBy(() -> taskQueue.replayDeadLetter("task-dead", "bad id!"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("补跑请求标识含非法字符");

        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> taskQueue.replayDeadLetter("task-dead", tooLong))
                .isInstanceOf(NonRetryableException.class);

        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_return_not_dead_letter_outcome_when_task_not_in_dead_letter_set() {
        // 脚本返回 0 = orig 不在 dead-letter 集合, 仅死信可补跑
        // (F12: 拒绝语义以返回码表达, 控制器据此映射 404)
        stubExecute(0L);

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-alive", "req-1");

        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.NOT_DEAD_LETTER);
        assertThat(result.newTaskId()).isNull();
    }

    @Test
    void should_return_inconsistent_outcome_when_original_state_not_dead_letter() {
        // 脚本返回 3 = 死信集合成员但原 state 缺失/状态非 DEAD_LETTER(数据不一致, F10)
        stubExecute(3L);

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-ghost", "req-1");

        assertThat(result.outcome())
                .as("成员资格 + state 状态双重校验防幽灵任务")
                .isEqualTo(TaskQueue.ReplayOutcome.ORIGINAL_STATE_INCONSISTENT);
        assertThat(result.newTaskId()).isNull();
    }

    @Test
    void should_return_conflict_outcome_when_replay_target_state_exists() {
        // 脚本返回 2 = 新 taskId state 已存在, 拒绝覆盖
        stubExecute(2L);

        TaskQueue.ReplayResult result = taskQueue.replayDeadLetter("task-dead", "req-1");

        assertThat(result.outcome()).isEqualTo(TaskQueue.ReplayOutcome.TARGET_CONFLICT);
        assertThat(result.newTaskId()).isNull();
    }

    @Test
    void should_throw_non_retryable_when_replay_task_id_blank() {
        assertThatThrownBy(() -> taskQueue.replayDeadLetter(" ", "req-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("taskId 不能为空");

        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_throw_non_retryable_when_replay_type_check_rejected() {
        stubExecute(-1L);

        assertThatThrownBy(() -> taskQueue.replayDeadLetter("task-dead", "req-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝");
    }

    // ============ push 退避窗口守卫 ============

    @Test
    void should_skip_push_silently_when_task_in_retry_backoff_window() {
        // 脚本返回 4 = taskId 已在 retry ZSET(退避窗口) → 跳过入队保留退避节奏,
        // 不抛异常(F6: 提前入队会绕过 dueAt, 让失败任务立即重跑破坏退避语义)
        stubExecute(4L);

        taskQueue.push("task-backing-off");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskDeadLetter(), RedisKeys.taskRetry(),
                RedisKeys.taskState("task-backing-off"));
    }

    // ============ retryCount / deadLetterCount ============

    @Test
    void should_expose_retry_count_from_zset_card() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(RedisKeys.taskRetry())).thenReturn(5L);

        assertThat(taskQueue.retryCount()).isEqualTo(5);
    }

    @Test
    void should_treat_retry_count_as_zero_when_redis_returns_null() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(RedisKeys.taskRetry())).thenReturn(null);

        assertThat(taskQueue.retryCount()).isZero();
    }

    @Test
    void should_expose_dead_letter_count_from_set_size() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size(RedisKeys.taskDeadLetter())).thenReturn(3L);

        assertThat(taskQueue.deadLetterCount()).isEqualTo(3);
    }

    @Test
    void should_treat_dead_letter_count_as_zero_when_redis_returns_null() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size(RedisKeys.taskDeadLetter())).thenReturn(null);

        assertThat(taskQueue.deadLetterCount()).isZero();
    }
}
