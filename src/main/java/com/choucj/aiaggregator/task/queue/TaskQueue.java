package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 任务队列 — 基于 Redis List + Set 实现,支持断点恢复(Story 1.6).
 *
 * <p><b>数据结构:</b>
 * <ul>
 *   <li>{@code task:queue} (Redis List) — FIFO 待处理队列,业务侧 {@code push} 到右端,
 *       {@code poll} 从左端取出</li>
 *   <li>{@code task:processing} (Redis Set) — 已取出但未完成的任务集合,
 *       进程崩溃后启动时由 {@link TaskRecoveryRunner} 检查并重新入队</li>
 * </ul>
 *
 * <p><b>异常映射(沿用 Story 1.5b {@code supplyWithMapping} 模式):</b>
 * TaskQueue 直接注入 {@link StringRedisTemplate} 而非 {@code RedisRepository},
 * 因 List/Set 操作是数据结构特定操作,不属于 Repository CRUD 范畴.
 * 但本类内部自己实现异常包装,业务侧拿到的是 {@link RetryableException} / {@link NonRetryableException}
 * 而非裸 {@link RedisSystemException}.
 *
 * <p><b>构造器注入:</b> 遵循架构 L1490-1495 强制构造器注入规范,
 * {@link RequiredArgsConstructor} 自动生成,字段 {@code final}.
 *
 * <p>引用源: Story 1.6 创建;消费方 Story 2.1 (RSSHUB 抓取入队) / Story 2.6 (Pipeline 编排).
 *
 * @see TaskRecoveryRunner
 * @see com.choucj.aiaggregator.task.scheduler.ContentScheduler
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskQueue {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 添加任务到队列右端(FIFO 入队).
     *
     * @param taskId 任务 ID(业务侧生成,如 {@code "rsshub:openai:1719489600"})
     * @throws NonRetryableException taskId 为 null
     * @throws RetryableException    Redis 连接异常(可重试)
     */
    public void push(String taskId) {
        if (taskId == null) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "push 失败: taskId 不能为 null");
        }
        supplyWithMapping("push", RedisKeys.taskQueue(), () ->
                stringRedisTemplate.opsForList().rightPush(RedisKeys.taskQueue(), taskId));
        log.debug("任务 {} 加入队列", taskId);
    }

    /**
     * 从队列左端阻塞取出任务(FIFO 出队),并加入 processing 集合.
     *
     * <p>取出后任务仍在 Redis 中(processing 集合),直到 {@link #complete(String)} 调用才移除.
     * 进程崩溃时未 complete 的任务由 {@link TaskRecoveryRunner} 重入队.
     *
     * @param timeout 阻塞超时(0 表示非阻塞立即返回)
     * @param unit    时间单位
     * @return 任务 ID;队列空且超时返回 null
     * @throws RetryableException Redis 连接异常
     */
    public String poll(long timeout, TimeUnit unit) {
        String task = supplyWithMapping("poll", RedisKeys.taskQueue(), () ->
                stringRedisTemplate.opsForList().leftPop(RedisKeys.taskQueue(), timeout, unit));
        if (task != null) {
            String finalTask = task;
            supplyWithMapping("poll-add-processing", RedisKeys.taskProcessing(), () ->
                    stringRedisTemplate.opsForSet().add(RedisKeys.taskProcessing(), finalTask));
            log.debug("任务 {} 开始处理", task);
        }
        return task;
    }

    /**
     * 标记任务完成,从 processing 集合移除.
     *
     * <p>幂等: taskId 不在集合中时不报错({@code srem} 返回 0).
     *
     * @param taskId 任务 ID
     * @throws RetryableException Redis 连接异常
     */
    public void complete(String taskId) {
        supplyWithMapping("complete", RedisKeys.taskProcessing(), () ->
                stringRedisTemplate.opsForSet().remove(RedisKeys.taskProcessing(), taskId));
        log.debug("任务 {} 处理完成", taskId);
    }

    /**
     * 返回 processing 集合全部成员(断点恢复用).
     *
     * @return 任务 ID 集合;空集合返回 {@link Collections#emptySet()}(非 null)
     * @throws RetryableException Redis 连接异常
     */
    public Set<String> getProcessingTasks() {
        Set<String> tasks = supplyWithMapping("getProcessingTasks", RedisKeys.taskProcessing(), () ->
                stringRedisTemplate.opsForSet().members(RedisKeys.taskProcessing()));
        return tasks != null ? tasks : Collections.emptySet();
    }

    /**
     * 异常包装 helper — 沿用 Story 1.5b {@code RedisRepositoryImpl.supplyWithMapping} 模式.
     *
     * <p>注意: 本类重复实现而非抽到 common, 因 List/Set 操作与 Repository CRUD 是不同抽象层级,
     * 不强行共用. 未来若引入更多 Redis 数据结构操作(Pub/Sub / Sorted Set), 可考虑抽到
     * {@code common.repository.RedisOperationTemplate} 工具类.
     */
    private <T> T supplyWithMapping(String operation, String key, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException
                 | ClusterStateFailureException e) {
            log.warn("Redis {} 异常映射为 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 连接异常): key=" + key, e);
        } catch (InvalidDataAccessApiUsageException | SerializationException e) {
            log.warn("Redis {} 异常映射为 NonRetryableException: key={}", operation, key, e);
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    operation + " 失败(Redis 数据异常): key=" + key, e);
        } catch (RedisSystemException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidDataAccessApiUsageException
                    || cause instanceof SerializationException) {
                log.warn("Redis {} 异常映射为 NonRetryableException(根因透传): key={}", operation, key, e);
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        operation + " 失败(Redis 数据异常, 根因透传): key=" + key, e);
            }
            log.warn("Redis {} 异常保守归 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 系统异常, 保守归可重试): key=" + key, e);
        }
    }
}
