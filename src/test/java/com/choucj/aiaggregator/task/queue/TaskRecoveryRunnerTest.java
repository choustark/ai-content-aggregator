package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.function.Supplier;

import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency.REDIS;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation.RECOVERY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRecoveryRunnerTest {

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SlowOperationRecorder slowOperationRecorder;

    private TaskRecoveryRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(slowOperationRecorder.observe(any(), any(), any(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        runner = new TaskRecoveryRunner(taskQueue, stringRedisTemplate, slowOperationRecorder);
    }

    @Test
    void should_record_recovery_delete_when_pending_tasks_are_requeued() {
        when(taskQueue.getProcessingTasks()).thenReturn(Set.of("task-1"));

        runner.recoverPendingTasks();

        verify(taskQueue).push("task-1");
        verify(slowOperationRecorder).observe(
                eq(Kind.REDIS),
                eq(REDIS), eq(RECOVERY), any(Supplier.class));
        verify(stringRedisTemplate).delete("task:processing");
    }
}
