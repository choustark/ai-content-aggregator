package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.data.redis.serializer.SerializationException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 1.6 AC-1/2/3/4 {@link TaskQueue} 单测 — 验证 List + Set 操作 + 异常映射.
 *
 * <p>覆盖 4 个方法(push / poll / complete / getProcessingTasks) happy path 与异常路径.
 *
 * <p><b>Mockito 严格模式:</b> 仅 stub 实际被调用的方法.
 */
@ExtendWith(MockitoExtension.class)
class TaskQueueTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private SetOperations<String, String> setOps;

    private TaskQueue taskQueue;

    @BeforeEach
    void setUp() {
        taskQueue = new TaskQueue(stringRedisTemplate);
    }

    // ============ AC-1: push ============

    @Test
    void shouldPushTaskToQueueRightEnd() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPush(anyString(), eq("task-1"))).thenReturn(1L);

        taskQueue.push("task-1");

        verify(listOps).rightPush("task:queue", "task-1");
    }

    @Test
    void shouldThrowNonRetryableWhenPushNullTaskId() {
        assertThatThrownBy(() -> taskQueue.push(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("taskId 不能为 null")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);

        verifyNoInteractions(listOps);
    }

    @Test
    void shouldMapConnectionFailureToRetryableOnPush() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPush(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("conn refused"));

        assertThatThrownBy(() -> taskQueue.push("task-1"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    // ============ AC-2: poll ============

    @Test
    void shouldPollTaskAndAddToProcessingSet() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(listOps.leftPop("task:queue")).thenReturn("task-1");
        when(setOps.add(anyString(), eq("task-1"))).thenReturn(1L);

        String result = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("task-1");
        verify(listOps).leftPop("task:queue");
        verify(setOps).add("task:processing", "task-1");
    }

    @Test
    void shouldUseNonBlockingLeftPopWhenTimeoutIsZero() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop("task:queue")).thenReturn(null);

        String result = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(result).isNull();
        verify(listOps).leftPop("task:queue");
        verifyNoInteractions(setOps);
    }

    @Test
    void shouldReturnNullWhenQueueEmptyWithoutTouchingProcessingSet() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(null);

        String result = taskQueue.poll(1, TimeUnit.SECONDS);

        assertThat(result).isNull();
        verifyNoInteractions(setOps);
    }

    @Test
    void shouldMapQueryTimeoutToRetryableOnPoll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new QueryTimeoutException("cmd timeout"));

        assertThatThrownBy(() -> taskQueue.poll(1, TimeUnit.SECONDS))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void shouldMapClusterStateFailureToRetryableOnPoll() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new ClusterStateFailureException("cluster rearranging"));

        assertThatThrownBy(() -> taskQueue.poll(1, TimeUnit.SECONDS))
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(ClusterStateFailureException.class);
    }

    // ============ AC-3: complete ============

    @Test
    void shouldRemoveTaskFromProcessingSetOnComplete() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.remove(anyString(), eq("task-1"))).thenReturn(1L);

        taskQueue.complete("task-1");

        verify(setOps).remove("task:processing", "task-1");
    }

    @Test
    void shouldBeIdempotentWhenCompleteUnknownTask() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.remove(anyString(), eq("unknown"))).thenReturn(0L);

        taskQueue.complete("unknown");

        verify(setOps).remove("task:processing", "unknown");
    }

    @Test
    void shouldMapSerializationToNonRetryableOnComplete() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        doThrow(new SerializationException("serializer fail"))
                .when(setOps).remove(anyString(), any());

        assertThatThrownBy(() -> taskQueue.complete("task-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(SerializationException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    // ============ AC-4: getProcessingTasks ============

    @Test
    void shouldReturnProcessingTasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        Set<String> expected = Set.of("task-1", "task-2");
        when(setOps.members(anyString())).thenReturn(expected);

        assertThat(taskQueue.getProcessingTasks()).isEqualTo(expected);
    }

    @Test
    void shouldReturnEmptySetWhenRedisReturnsNull() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(null);

        assertThat(taskQueue.getProcessingTasks())
                .as("Redis members 返回 null 时应转成空集合而非透传 null")
                .isEqualTo(Collections.emptySet());
    }

    @Test
    void shouldMapInvalidApiUsageToNonRetryableOnGetProcessingTasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString()))
                .thenThrow(new InvalidDataAccessApiUsageException("null key"));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(NonRetryableException.class)
                .hasCauseInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void shouldMapBareRedisSystemExceptionToRetryableOnGetProcessingTasks() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString()))
                .thenThrow(new RedisSystemException("unknown", new RuntimeException("x")));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("保守归可重试");
    }

    @Test
    void shouldMapRedisSystemExceptionWithSerializationCauseToNonRetryable() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        SerializationException rootCause = new SerializationException("inner jackson fail");
        when(setOps.members(anyString()))
                .thenThrow(new RedisSystemException("wrapped", rootCause));

        assertThatThrownBy(() -> taskQueue.getProcessingTasks())
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("根因透传");
    }

    @Test
    void shouldReturnTrueWhenTaskAlreadyQueued() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range("task:queue", 0, -1)).thenReturn(List.of("task-1", "task-2"));

        assertThat(taskQueue.isQueued("task-2")).isTrue();
    }

    @Test
    void shouldReturnFalseWhenTaskNotQueued() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range("task:queue", 0, -1)).thenReturn(List.of("task-1"));

        assertThat(taskQueue.isQueued("task-2")).isFalse();
    }
}
