package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 一次性任务键迁移器 — 把旧 {@code task:queue}/{@code task:processing} 数据迁入 Story 10.4
 * 同槽新键族(AC1).
 *
 * <p><b>迁移边界(AC1/AC4):</b>
 * <ul>
 *   <li>只读旧键({@code SMEMBERS}/{@code LRANGE}), 写入经 {@link TaskQueue} 高层迁移命令 —
 *       旧新键 hash tag 不同(Cluster 下不同 slot), <b>不在任何 Lua 脚本中同时访问旧新键</b></li>
 *   <li><b>先 processing 后 pending</b>; 同 ID 同时存在旧 processing/pending 时 processing 优先
 *       (账本打标后 pending 迁移自动跳过)</li>
 *   <li>幂等账本 {@code task:{queue}:legacy-migrated}: 已迁移 ID 打标跳过 — 仅查新 pending/processing
 *       不能保证幂等, 任务消费完成后重跑迁移会把已完成任务复活入队</li>
 *   <li>新 state 已存在但账本未标记 → 拒绝覆盖并计入冲突, 由运维核对(防止复用固定 {@code twitter:run}
 *       ID 把现有新任务状态改回旧状态)</li>
 * </ul>
 *
 * <p><b>触发方式:</b> 手动受控触发(维护窗口停旧版写入并暂停新版入队/消费后执行),
 * <b>禁止启动时自动执行</b>(避免与 recovery 时序竞争 + A7 风险)。HTTP 入口由
 * {@link TaskKeyMigrationController} 承担, 默认关闭, 需 {@code task.migration.enabled=true}
 * 显式开启。旧键删除不在业务代码内, 迁移验证后由运维按 runbook 执行。
 *
 * <p>引用源: Story 10.4 创建(2026-09-20)。
 *
 * @see TaskQueue#migrateLegacyProcessing(String)
 * @see TaskQueue#migrateLegacyPending(String)
 * @see TaskKeyMigrationController
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskKeyMigrator {

    private final TaskQueue taskQueue;
    private final StringRedisTemplate stringRedisTemplate;
    private final SlowOperationRecorder slowOperationRecorder;

    /**
     * 执行一次性迁移, 返回结构化摘要(审计用).
     *
     * <p>每个旧键单独读取, 逐任务经 TaskQueue 高层命令迁入新键族; 结果含 W11 要求的
     * 旧 pending 数/旧 processing 数/迁移数/跳过数/冲突数/耗时。
     *
     * @return 迁移摘要
     */
    public MigrationSummary migrate() {
        long started = System.currentTimeMillis();
        Set<String> legacyProcessing = readLegacyProcessing();
        List<String> legacyPending = readLegacyPending();
        log.info("任务键迁移开始: 旧 processing={} 个, 旧 pending={} 个",
                legacyProcessing.size(), legacyPending.size());

        int migrated = 0;
        int skipped = 0;
        int conflicts = 0;
        int invalid = 0;
        // 先 processing 后 pending — 同 ID 时 processing 优先(账本先打标, pending 迁移自动跳过)
        for (String taskId : legacyProcessing) {
            if (isBlankTaskId(taskId, "processing")) {
                invalid++;
                continue;
            }
            int result = taskQueue.migrateLegacyProcessing(taskId);
            migratedSkippedOrConflicted(taskId, "processing", result);
            int[] counters = count(result, migrated, skipped, conflicts);
            migrated = counters[0];
            skipped = counters[1];
            conflicts = counters[2];
        }
        for (String taskId : legacyPending) {
            if (isBlankTaskId(taskId, "pending")) {
                invalid++;
                continue;
            }
            int result = taskQueue.migrateLegacyPending(taskId);
            migratedSkippedOrConflicted(taskId, "pending", result);
            int[] counters = count(result, migrated, skipped, conflicts);
            migrated = counters[0];
            skipped = counters[1];
            conflicts = counters[2];
        }
        long elapsedMs = System.currentTimeMillis() - started;
        MigrationSummary summary = new MigrationSummary(
                legacyPending.size(), legacyProcessing.size(), migrated, skipped, conflicts,
                invalid, elapsedMs);
        log.info("任务键迁移完成: legacyProcessing={}, legacyPending={}, migrated={}, skipped={}, "
                        + "conflicts={}, invalid={}, 耗时={}ms",
                summary.legacyProcessingCount(), summary.legacyPendingCount(),
                summary.migratedCount(), summary.skippedCount(), summary.conflictCount(),
                summary.invalidCount(), summary.elapsedMs());
        return summary;
    }

    /**
     * 旧键条目有效性隔离(10.4 review 修复): 旧数据不可信, 空 taskId 会因
     * {@code RedisKeys.taskState} 拒绝空值而抛异常, 若不隔离则阻断其后的全部迁移且每次重跑
     * 都在同一位置失败 — 记 WARN 并计入 {@code invalidCount} 后继续.
     */
    private boolean isBlankTaskId(String taskId, String source) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("旧 {} 键发现无效空 taskId, 跳过并计入 invalid(需运维核对该条目)", source);
            return true;
        }
        return false;
    }

    /** 迁移结果计数归类 — 返回 [migrated, skipped, conflicts] 累计值(1=迁移/0=跳过/2=冲突). */
    private int[] count(int result, int migrated, int skipped, int conflicts) {
        return switch (result) {
            case 1 -> new int[]{migrated + 1, skipped, conflicts};
            case 0 -> new int[]{migrated, skipped + 1, conflicts};
            case 2 -> new int[]{migrated, skipped, conflicts + 1};
            default -> new int[]{migrated, skipped, conflicts};
        };
    }

    private void migratedSkippedOrConflicted(String taskId, String source, int result) {
        switch (result) {
            case 1 -> log.info("旧 {} 任务迁移成功: taskId={}", source, taskId);
            case 0 -> log.info("旧 {} 任务已迁移过(账本命中), 跳过: taskId={}", source, taskId);
            case 2 -> log.warn("旧 {} 任务新 state 冲突, 拒绝覆盖, 需运维核对: taskId={}", source, taskId);
            default -> log.warn("旧 {} 任务迁移返回未知码 {}: taskId={}", source, result, taskId);
        }
    }

    /** 只读旧 processing 集合(观测接线; null 归一为空集合 — 键不存在时防御 client 实现差异). */
    private Set<String> readLegacyProcessing() {
        Set<String> members = slowOperationRecorder.observe(Kind.REDIS, Dependency.REDIS,
                Operation.MIGRATE, Duration.ZERO, () ->
                        stringRedisTemplate.opsForSet().members(RedisKeys.legacyTaskProcessing()));
        return members != null ? members : Set.of();
    }

    /** 只读旧 pending 队列(观测接线; 旧键不存在时 Redis 返回空列表). */
    private List<String> readLegacyPending() {
        List<String> tasks = slowOperationRecorder.observe(Kind.REDIS, Dependency.REDIS,
                Operation.MIGRATE, Duration.ZERO, () ->
                        stringRedisTemplate.opsForList().range(RedisKeys.legacyTaskQueue(), 0, -1));
        return tasks != null ? tasks : List.of();
    }

    /**
     * 迁移摘要 — 结构化审计记录.
     *
     * @param legacyPendingCount    旧 pending 队列任务数
     * @param legacyProcessingCount 旧 processing 集合任务数
     * @param migratedCount         实际迁入新键族的任务数
     * @param skippedCount          账本命中跳过的任务数
     * @param conflictCount         新 state 冲突拒绝覆盖的任务数(需运维核对)
     * @param invalidCount          旧键中无效空 taskId 条目数(已隔离跳过, 需运维核对)
     * @param elapsedMs             迁移总耗时
     */
    public record MigrationSummary(int legacyPendingCount, int legacyProcessingCount,
                                   int migratedCount, int skippedCount, int conflictCount,
                                   int invalidCount, long elapsedMs) {
    }
}
