package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.4 {@link TaskKeyMigrator} 单测 — 一次性迁移编排:
 * 只读旧键、先 processing 后 pending、账本幂等计数与摘要输出(AC1/W11).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TaskKeyMigratorTest {

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private SlowOperationRecorder slowOperationRecorder;

    private TaskKeyMigrator migrator;

    @BeforeEach
    void setUp() {
        lenient().when(slowOperationRecorder.observe(
                        any(), any(), any(), any(Duration.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        migrator = new TaskKeyMigrator(taskQueue, stringRedisTemplate, slowOperationRecorder);
    }

    @Test
    void should_migrate_processing_first_then_pending_and_count_migrated() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // LinkedHashSet 固定迭代顺序, 保证 InOrder 断言确定性
        when(setOps.members(RedisKeys.legacyTaskProcessing()))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("p-1", "p-2")));
        when(listOps.range(RedisKeys.legacyTaskQueue(), 0, -1)).thenReturn(List.of("q-1"));
        when(taskQueue.migrateLegacyProcessing("p-1")).thenReturn(1);
        when(taskQueue.migrateLegacyProcessing("p-2")).thenReturn(1);
        when(taskQueue.migrateLegacyPending("q-1")).thenReturn(1);

        TaskKeyMigrator.MigrationSummary summary = migrator.migrate();

        // 先 processing 后 pending
        InOrder inOrder = inOrder(taskQueue);
        inOrder.verify(taskQueue).migrateLegacyProcessing("p-1");
        inOrder.verify(taskQueue).migrateLegacyProcessing("p-2");
        inOrder.verify(taskQueue).migrateLegacyPending("q-1");
        assertThat(summary.legacyProcessingCount()).isEqualTo(2);
        assertThat(summary.legacyPendingCount()).isEqualTo(1);
        assertThat(summary.migratedCount()).isEqualTo(3);
        assertThat(summary.skippedCount()).isZero();
        assertThat(summary.conflictCount()).isZero();
        assertThat(summary.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void should_count_ledger_hits_as_skipped_and_conflicts_separately() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(setOps.members(RedisKeys.legacyTaskProcessing()))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("done", "conflict")));
        when(listOps.range(RedisKeys.legacyTaskQueue(), 0, -1)).thenReturn(List.of("dup"));
        // 账本已标记 → 跳过; 新 state 已存在 → 冲突; 重复迁移 → 跳过
        when(taskQueue.migrateLegacyProcessing("done")).thenReturn(0);
        when(taskQueue.migrateLegacyProcessing("conflict")).thenReturn(2);
        when(taskQueue.migrateLegacyPending("dup")).thenReturn(0);

        TaskKeyMigrator.MigrationSummary summary = migrator.migrate();

        assertThat(summary.migratedCount()).isZero();
        assertThat(summary.skippedCount()).isEqualTo(2);
        assertThat(summary.conflictCount()).isEqualTo(1);
    }

    @Test
    void should_return_zero_summary_and_skip_queue_commands_when_legacy_keys_absent() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(setOps.members(RedisKeys.legacyTaskProcessing())).thenReturn(null);
        when(listOps.range(RedisKeys.legacyTaskQueue(), 0, -1)).thenReturn(null);

        TaskKeyMigrator.MigrationSummary summary = migrator.migrate();

        assertThat(summary.legacyProcessingCount()).isZero();
        assertThat(summary.legacyPendingCount()).isZero();
        assertThat(summary.migratedCount()).isZero();
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_isolate_blank_task_ids_and_continue_rest_of_migration() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // 旧数据不可信: 混入空字符串/空白/ null 条目
        when(setOps.members(RedisKeys.legacyTaskProcessing()))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("", "p-1", "  ")));
        when(listOps.range(RedisKeys.legacyTaskQueue(), 0, -1))
                .thenReturn(new java.util.ArrayList<>(List.of("q-1", "")));
        when(taskQueue.migrateLegacyProcessing("p-1")).thenReturn(1);
        when(taskQueue.migrateLegacyPending("q-1")).thenReturn(1);

        TaskKeyMigrator.MigrationSummary summary = migrator.migrate();

        // 无效条目被隔离跳过并计入 invalid, 其后有效任务继续迁移(不被阻断)
        assertThat(summary.invalidCount()).isEqualTo(3);
        assertThat(summary.migratedCount()).isEqualTo(2);
        verify(taskQueue).migrateLegacyProcessing("p-1");
        verify(taskQueue).migrateLegacyPending("q-1");
    }

    @Test
    void should_read_legacy_keys_through_slow_operation_recorder_with_migrate_operation() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(setOps.members(RedisKeys.legacyTaskProcessing())).thenReturn(Set.of());
        when(listOps.range(RedisKeys.legacyTaskQueue(), 0, -1)).thenReturn(List.of());

        migrator.migrate();

        // 旧 processing + 旧 pending 各一次 MIGRATE 观测
        verify(slowOperationRecorder, times(2)).observe(
                eq(Kind.REDIS), eq(Dependency.REDIS), eq(Operation.MIGRATE),
                eq(Duration.ZERO), any(Supplier.class));
    }
}
