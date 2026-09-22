package com.choucj.aiaggregator.task.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.4 {@link TaskRecoveryRunner} 单测 — 启动恢复已瘦身为纯编排器:
 * 只委托 {@link TaskQueue#recoverProcessingTasks()} 高层原子命令(AC4),
 * 不再直接操作 Redis 键(SREM/DEL/LPUSH 均下沉至 Lua 脚本).
 */
@ExtendWith(MockitoExtension.class)
class TaskRecoveryRunnerTest {

    @Mock
    private TaskQueue taskQueue;

    @InjectMocks
    private TaskRecoveryRunner runner;

    @Test
    void should_delegate_to_atomic_recover_command_when_processing_members_exist() {
        when(taskQueue.recoverProcessingTasks()).thenReturn(2);

        runner.recoverPendingTasks();

        verify(taskQueue).recoverProcessingTasks();
    }

    @Test
    void should_log_noop_when_no_processing_members() {
        when(taskQueue.recoverProcessingTasks()).thenReturn(0);

        runner.recoverPendingTasks();

        verify(taskQueue).recoverProcessingTasks();
    }

    @Test
    void should_propagate_queue_command_result_when_recovery_fails_at_redis_layer() {
        // TaskQueue 内部异常映射后上抛 — 编排器不吞异常, 由 ContentScheduler.onStartup 兜底
        when(taskQueue.recoverProcessingTasks())
                .thenThrow(new com.choucj.aiaggregator.common.exception.RetryableException(
                        com.choucj.aiaggregator.common.model.ErrorCode.REDIS_CONNECTION_ERROR,
                        "redis down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.recoverPendingTasks())
                .isInstanceOf(com.choucj.aiaggregator.common.exception.RetryableException.class);
        verify(taskQueue).recoverProcessingTasks();
        verifyNoMoreInteractions(taskQueue);
    }
}
