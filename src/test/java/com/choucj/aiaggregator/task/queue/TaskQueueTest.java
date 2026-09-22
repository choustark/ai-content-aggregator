package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.SerializationException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency.REDIS;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation.POLL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 1.6 / 10.4 {@link TaskQueue} 单测 — 验证 Lua 原子领取(预读 + CLAIM)、
 * 同槽键族读写、死信/恢复/迁移高层命令与异常映射.
 *
 * <p><b>Mock 策略:</b> 脚本执行经 {@code stringRedisTemplate.execute(RedisScript, List, Object...)},
 * 用 ArgumentCaptor 断言 KEYS/ARGV 组装; 返回码语义 0=空 / 1=成功 / 2=队首竞争 / -1=TYPE 拒绝.
 *
 * <p><b>Mockito 严格模式:</b> 仅 stub 实际被调用的方法.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TaskQueueTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private SlowOperationRecorder slowOperationRecorder;

    private TaskQueue taskQueue;

    @BeforeEach
    void setUp() {
        lenient().when(slowOperationRecorder.observe(
                        any(), any(), any(), any(Duration.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        taskQueue = new TaskQueue(stringRedisTemplate, slowOperationRecorder);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubExecute(Long... results) {
        // raw RedisScript 匹配器使 execute 的 T 推断为 Object — 统一按 Object stub 再收窄
        org.mockito.stubbing.OngoingStubbing<Object> stubbing =
                (org.mockito.stubbing.OngoingStubbing) when(
                        stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                                any(Object[].class)));
        for (Long result : results) {
            stubbing = stubbing.thenReturn(result);
        }
    }

    // ============ push ============

    @Test
    void should_push_task_and_queued_state_when_enqueued() {
        stubExecute(1L);

        taskQueue.push("task-1");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                argsCaptor.capture());
        // PUSH 4 键同 slot: pending / processing(在途守卫) / dead-letter(终态守卫) / state
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskDeadLetter(),
                RedisKeys.taskState("task-1"));
        assertThat(argsCaptor.getValue()[0]).isEqualTo("task-1");
        assertThat(argsCaptor.getValue()).hasSize(2);
    }

    @Test
    void should_skip_push_without_exception_when_task_in_flight() {
        // 脚本返回 2 = taskId 在 processing 中(在途): 幂等跳过, 不覆盖在途 PROCESSING 状态
        stubExecute(2L);

        taskQueue.push("task-in-flight");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskDeadLetter(),
                RedisKeys.taskState("task-in-flight"));
    }

    @Test
    void should_skip_push_when_task_already_queued() {
        // 脚本返回 0 = taskId 已在 pending 队列: 幂等跳过, 不产生重复条目
        stubExecute(0L);

        taskQueue.push("task-dup");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskDeadLetter(),
                RedisKeys.taskState("task-dup"));
    }

    @Test
    void should_reject_push_when_task_is_dead_lettered() {
        // 脚本返回 3 = taskId 已是死信终态；普通 push 不得自动复活
        stubExecute(3L);

        assertThatThrownBy(() -> taskQueue.push("task-doomed"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("仅允许显式人工补跑")
                .hasMessageContaining("task-doomed");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskDeadLetter(), RedisKeys.taskState("task-doomed"));
    }

    @Test
    void should_throw_non_retryable_when_push_null_task_id() {
        assertThatThrownBy(() -> taskQueue.push(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("taskId 不能为 null")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);

        verifyNoInteractions(listOps);
    }

    @Test
    void should_map_connection_failure_to_retryable_on_push() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("conn refused"));

        assertThatThrownBy(() -> taskQueue.push("task-1"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void should_throw_non_retryable_when_push_type_check_rejected() {
        stubExecute(-1L);

        assertThatThrownBy(() -> taskQueue.push("task-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝");
    }

    // ============ poll: 原子领取 ============

    @Test
    void should_claim_head_task_atomically_when_queue_not_empty() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn("task-1");
        stubExecute(1L);

        String result = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("task-1");
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        // CLAIM 3 键同 slot: pending / processing / state:<候选>
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskProcessing(),
                RedisKeys.taskState("task-1"));
    }

    @Test
    void should_return_null_when_preview_shows_empty_queue() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn(null);

        String result = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(result).isNull();
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_retry_immediately_and_succeed_when_head_race_on_non_blocking_poll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // 第一次预读得 task-old, CLAIM 返回 2(队首竞争); 第二次预读得 task-1, CLAIM 成功
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn("task-old", "task-1");
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L, 1L);

        String result = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("task-1");
        verify(listOps, times(2)).index(RedisKeys.taskPending(), 0);
    }

    @Test
    void should_return_null_when_blocking_poll_deadline_reached_after_races() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn("task-1");
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(2L);

        // 1ns 截止时间: 竞争后立即到期 → 返回 null 而非无限竞争
        String result = taskQueue.poll(1, TimeUnit.NANOSECONDS);

        assertThat(result).isNull();
        verify(stringRedisTemplate, atLeastOnce()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_throw_non_retryable_when_claim_type_check_rejected() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn("task-1");
        stubExecute(-1L);

        // 候选 state 键被预置为错误类型(WRONGTYPE)时: 脚本拒绝且 pending 不变, Java 抛 NonRetryable
        assertThatThrownBy(() -> taskQueue.poll(0, TimeUnit.SECONDS))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝")
                .hasMessageContaining(RedisKeys.taskState("task-1"));
    }

    @Test
    void should_map_query_timeout_to_retryable_on_poll_preview() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(anyString(), eq(0L)))
                .thenThrow(new QueryTimeoutException("cmd timeout"));

        assertThatThrownBy(() -> taskQueue.poll(1, TimeUnit.SECONDS))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void should_map_cluster_state_failure_to_retryable_on_poll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(anyString(), eq(0L)))
                .thenThrow(new ClusterStateFailureException("cluster rearranging"));

        assertThatThrownBy(() -> taskQueue.poll(0, TimeUnit.SECONDS))
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(ClusterStateFailureException.class);
    }

    @Test
    void should_pass_expected_wait_to_slow_operation_recorder_on_blocking_poll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn(null);

        taskQueue.poll(1, TimeUnit.SECONDS);

        // 阻塞 poll 空队列会在截止时间内循环预读, 每轮都经观测 — 断言传参而非次数
        verify(slowOperationRecorder, atLeastOnce()).observe(
                eq(Kind.REDIS), eq(REDIS), eq(POLL),
                eq(Duration.ofSeconds(1)), any(Supplier.class));
    }

    @Test
    void should_wait_and_claim_when_task_enqueued_during_blocking_poll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // 第一次预读空(阻塞等待), 期间任务入队, 第二次预读得 task-1 并领取成功
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn(null, "task-1");
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        String result = taskQueue.poll(2, TimeUnit.SECONDS);

        assertThat(result)
                .as("阻塞 poll 期间任务入队应被领取, 不得因瞬间空队列提前返回 null")
                .isEqualTo("task-1");
        verify(listOps, times(2)).index(RedisKeys.taskPending(), 0);
    }

    @Test
    void should_wait_until_deadline_when_blocking_poll_stays_empty() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn(null);

        String result = taskQueue.poll(50, TimeUnit.MILLISECONDS);

        assertThat(result)
                .as("阻塞 poll 在整个超时窗口内队列保持为空才返回 null")
                .isNull();
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_keep_waiting_when_preview_candidate_vanishes_before_claim_on_blocking_poll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // 预读得候选但 CLAIM 判空(候选被并发移除的竞态), 随后新任务入队被领取
        when(listOps.index(RedisKeys.taskPending(), 0)).thenReturn("task-gone", null, "task-1");
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L, 1L);

        String result = taskQueue.poll(2, TimeUnit.SECONDS);

        assertThat(result)
                .as("阻塞 poll 遇预读竞态清空应继续等待而非返回 null")
                .isEqualTo("task-1");
    }

    // ============ complete ============

    @Test
    void should_complete_task_and_write_terminal_state_atomically() {
        stubExecute(1L);

        taskQueue.complete("task-1");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskProcessing(), RedisKeys.taskState("task-1"));
    }

    @Test
    void should_be_idempotent_when_complete_unknown_task() {
        stubExecute(0L);

        taskQueue.complete("unknown");

        // SREM 返回 0 时脚本不写 COMPLETED 终态, 也不抛错
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_map_serialization_to_non_retryable_on_complete() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new SerializationException("serializer fail"));

        assertThatThrownBy(() -> taskQueue.complete("task-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(SerializationException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    // ============ markDeadLetter: 失败终态 ============

    @Test
    void should_move_task_to_dead_letter_with_sanitized_reason_when_in_processing() {
        stubExecute(1L);

        boolean moved = taskQueue.markDeadLetter("task-1", "permanent\nfailure\twith  spaces");

        assertThat(moved).isTrue();
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskProcessing(), RedisKeys.taskDeadLetter(),
                RedisKeys.taskState("task-1"));
        // 原因脱敏: 换行/制表符折叠为空格
        assertThat(argsCaptor.getValue()[2]).isEqualTo("permanent failure with spaces");
    }

    @Test
    void should_skip_dead_letter_when_task_not_in_processing() {
        stubExecute(0L);

        boolean moved = taskQueue.markDeadLetter("task-1", "permanent");

        assertThat(moved).isFalse();
    }

    @Test
    void should_truncate_long_reason_by_code_points_when_marking_dead_letter() {
        stubExecute(1L);
        String longReason = "错".repeat(300);

        taskQueue.markDeadLetter("task-1", longReason);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        String sanitized = (String) argsCaptor.getValue()[2];
        assertThat(sanitized.codePointCount(0, sanitized.length())).isEqualTo(200);
    }

    // ============ getProcessingTasks / isQueued / counts(读新键) ============

    @Test
    void should_return_processing_tasks_from_new_key() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        Set<String> expected = Set.of("task-1", "task-2");
        when(setOps.members(RedisKeys.taskProcessing())).thenReturn(expected);

        assertThat(taskQueue.getProcessingTasks()).isEqualTo(expected);
    }

    @Test
    void should_return_empty_set_when_redis_returns_null() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(null);

        assertThat(taskQueue.getProcessingTasks())
                .as("Redis members 返回 null 时应转成空集合而非透传 null")
                .isEqualTo(Collections.emptySet());
    }

    @Test
    void should_map_invalid_api_usage_to_non_retryable_on_get_processing_tasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString()))
                .thenThrow(new InvalidDataAccessApiUsageException("null key"));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void should_map_bare_redis_system_exception_to_retryable_on_get_processing_tasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString()))
                .thenThrow(new RedisSystemException("unknown", new RuntimeException("x")));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("保守归可重试");
    }

    @Test
    void should_map_redis_system_exception_with_serialization_cause_to_non_retryable() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        SerializationException rootCause = new SerializationException("inner jackson fail");
        when(setOps.members(anyString()))
                .thenThrow(new RedisSystemException("wrapped", rootCause));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("根因透传");
    }

    @Test
    void should_return_true_when_task_already_queued() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(RedisKeys.taskPending(), 0, -1))
                .thenReturn(List.of("task-1", "task-2"));

        assertThat(taskQueue.isQueued("task-2")).isTrue();
    }

    @Test
    void should_return_false_when_task_not_queued() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(RedisKeys.taskPending(), 0, -1)).thenReturn(List.of("task-1"));

        assertThat(taskQueue.isQueued("task-2")).isFalse();
    }

    @Test
    void should_expose_counts_when_queue_state_is_requested() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(listOps.size(RedisKeys.taskPending())).thenReturn(4L);
        when(setOps.size(RedisKeys.taskProcessing())).thenReturn(2L);

        assertThat(taskQueue.pendingCount()).isEqualTo(4);
        assertThat(taskQueue.processingCount()).isEqualTo(2);
    }

    @Test
    void should_treat_count_as_zero_when_redis_returns_null() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.size(RedisKeys.taskPending())).thenReturn(null);

        assertThat(taskQueue.pendingCount()).isZero();
    }

    // ============ recoverProcessingTasks: 恢复高层命令 ============

    @Test
    void should_recover_processing_members_atomically_when_status_is_processing() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(RedisKeys.taskProcessing())).thenReturn(Set.of("task-a"));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        int recovered = taskQueue.recoverProcessingTasks();

        assertThat(recovered).isEqualTo(1);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        // RECOVER 3 键同 slot: processing / pending / state:<taskId>
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskProcessing(), RedisKeys.taskPending(),
                RedisKeys.taskState("task-a"));
    }

    @Test
    void should_skip_member_when_recovery_precondition_not_met() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(RedisKeys.taskProcessing())).thenReturn(Set.of("task-a"));
        // 脚本返回 0: 成员不在 processing 或状态非 PROCESSING(如已 COMPLETED/DEAD_LETTER)
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        assertThat(taskQueue.recoverProcessingTasks()).isZero();
    }

    @Test
    void should_return_zero_without_script_when_no_processing_tasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(RedisKeys.taskProcessing())).thenReturn(Collections.emptySet());

        assertThat(taskQueue.recoverProcessingTasks()).isZero();
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
    }

    @Test
    void should_throw_non_retryable_when_recovery_type_check_rejected() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(RedisKeys.taskProcessing())).thenReturn(Set.of("task-a"));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L);

        assertThatThrownBy(() -> taskQueue.recoverProcessingTasks())
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("TYPE 前置校验拒绝");
    }

    // ============ migrateLegacy*: 迁移高层命令 ============

    @Test
    void should_migrate_legacy_processing_member_with_ledger_keys() {
        stubExecute(1L);

        int result = taskQueue.migrateLegacyProcessing("task-1");

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        // 迁移 3 键同 slot: 新 processing / state / legacy-migrated 账本
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskProcessing(), RedisKeys.taskState("task-1"),
                RedisKeys.taskLegacyMigrated());
    }

    @Test
    void should_migrate_legacy_pending_member_with_ledger_keys() {
        stubExecute(1L);

        int result = taskQueue.migrateLegacyPending("task-1");

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.taskPending(), RedisKeys.taskState("task-1"),
                RedisKeys.taskLegacyMigrated());
    }

    @Test
    void should_report_conflict_code_when_state_exists_without_ledger_marker() {
        stubExecute(2L);

        assertThat(taskQueue.migrateLegacyProcessing("task-1")).isEqualTo(2);
    }

    @Test
    void should_report_skip_code_when_ledger_already_marks_task() {
        stubExecute(0L);

        assertThat(taskQueue.migrateLegacyPending("task-1")).isZero();
    }
}
