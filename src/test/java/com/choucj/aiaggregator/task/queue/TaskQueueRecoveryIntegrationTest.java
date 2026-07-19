package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.util.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 1.6 AC-5/6 端到端集成测试 — 验证 TaskQueue + TaskRecoveryRunner 协作.
 *
 * <p><b>测试场景:</b>
 * <ol>
 *   <li>push + poll FIFO 顺序正确</li>
 *   <li>poll 后任务进入 processing 集合,complete 后移除</li>
 *   <li>processing 集合非空时, recoverPendingTasks 重新入队并清空集合</li>
 *   <li>processing 集合空时, recoverPendingTasks 是 no-op</li>
 * </ol>
 *
 * <p><b>跳过策略(沿用 Story 1.5a):</b> 本地无 Redis Cluster 时通过
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
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void requireRedisClusterOnline() {
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
                "本地 Redis Cluster 不可用,跳过 Story 1.6 集成测试");
    }

    @BeforeEach
    @AfterEach
    void cleanupQueueAndProcessing() {
        redisTemplate.delete(RedisKeys.taskQueue());
        redisTemplate.delete(RedisKeys.taskProcessing());
    }

    @Test
    void shouldEnqueueAndDequeueInFifoOrder() {
        taskQueue.push("task-a");
        taskQueue.push("task-b");
        taskQueue.push("task-c");

        String first = taskQueue.poll(0, TimeUnit.SECONDS);
        String second = taskQueue.poll(0, TimeUnit.SECONDS);
        String third = taskQueue.poll(0, TimeUnit.SECONDS);
        String fourth = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(first).isEqualTo("task-a");
        assertThat(second).isEqualTo("task-b");
        assertThat(third).isEqualTo("task-c");
        assertThat(fourth).isNull();
    }

    @Test
    void shouldTrackTaskInProcessingSetUntilComplete() {
        taskQueue.push("task-x");
        String polled = taskQueue.poll(0, TimeUnit.SECONDS);

        assertThat(polled).isEqualTo("task-x");
        assertThat(taskQueue.getProcessingTasks())
                .as("poll 后任务应进入 processing 集合")
                .contains("task-x");

        taskQueue.complete("task-x");

        assertThat(taskQueue.getProcessingTasks())
                .as("complete 后任务应从 processing 集合移除")
                .doesNotContain("task-x");
    }

    @Test
    void shouldRecoverPendingTasksByRequeuing() {
        // 模拟进程崩溃后:任务在 processing 集合但不在 task:queue
        redisTemplate.opsForSet().add(RedisKeys.taskProcessing(), "task-stuck-1", "task-stuck-2");

        recoveryRunner.recoverPendingTasks();

        // 两个任务应重新入队
        assertThat(redisTemplate.opsForList().size(RedisKeys.taskQueue()))
                .as("recoverPendingTasks 应把 processing 集合中的任务重新入队")
                .isEqualTo(2);
        // processing 集合应被清空
        assertThat(redisTemplate.opsForSet().size(RedisKeys.taskProcessing()))
                .as("recoverPendingTasks 后 processing 集合应被清空")
                .isZero();
    }

    @Test
    void shouldBeNoOpWhenProcessingIsEmpty() {
        // processing 集合为空时, recoverPendingTasks 不应报错
        recoveryRunner.recoverPendingTasks();

        assertThat(redisTemplate.opsForList().size(RedisKeys.taskQueue()))
                .as("空 processing 集合下 recoverPendingTasks 不应入队任何任务")
                .isZero();
    }

    @Test
    void shouldCompleteProcessAllScenarioEndToEnd() {
        // 端到端: push N 个任务 → poll 并 complete 全部 → processing 应空
        taskQueue.push("e2e-1");
        taskQueue.push("e2e-2");

        Set<String> polled = new java.util.HashSet<>();
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
}
