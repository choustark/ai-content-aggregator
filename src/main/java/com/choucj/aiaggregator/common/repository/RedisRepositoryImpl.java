package com.choucj.aiaggregator.common.repository;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link RedisRepository} 默认实现 — String + JSON 双路径,异常映射层(Story 1.5b).
 *
 * <p><b>构造器注入:</b> 遵循架构 L1490-1495 强制构造器注入规范,
 * {@link RequiredArgsConstructor} 自动生成,字段 {@code final}.
 *
 * <p><b>双路径(Story 1.5b 扩展):</b>
 * <ul>
 *   <li>{@link StringRedisTemplate} — 服务 1.5a 的 6 个 String 方法</li>
 *   <li>{@link RedisTemplate}<{@link String}, {@link Object}> — 服务 1.5b 的 setObject/getObject</li>
 * </ul>
 *
 * <p><b>异常映射(Story 1.5b 核心交付):</b>
 * 所有方法经过 {@link #supplyWithMapping(Supplier, String, String)} 包装,
 * 底层 Spring Data Redis 异常包装为业务异常,业务侧不再裸接 {@link RedisSystemException}:
 *
 * <table border="1">
 *   <tr><th>Spring Data Redis 异常</th><th>业务异常</th><th>ErrorCode</th></tr>
 *   <tr><td>{@link RedisConnectionFailureException}</td>
 *       <td>{@link RetryableException}</td><td>{@link ErrorCode#REDIS_CONNECTION_ERROR}</td></tr>
 *   <tr><td>{@link QueryTimeoutException}</td>
 *       <td>{@link RetryableException}</td><td>{@link ErrorCode#REDIS_CONNECTION_ERROR}</td></tr>
 *   <tr><td>{@link ClusterStateFailureException}</td>
 *       <td>{@link RetryableException}</td><td>{@link ErrorCode#REDIS_CONNECTION_ERROR}</td></tr>
 *   <tr><td>{@link InvalidDataAccessApiUsageException}</td>
 *       <td>{@link NonRetryableException}</td><td>{@link ErrorCode#REDIS_DATA_ERROR}</td></tr>
 *   <tr><td>{@link SerializationException}</td>
 *       <td>{@link NonRetryableException}</td><td>{@link ErrorCode#REDIS_DATA_ERROR}</td></tr>
 *   <tr><td>裸 {@link RedisSystemException}</td>
 *       <td>{@link RetryableException}(保守原则)</td>
 *       <td>{@link ErrorCode#REDIS_CONNECTION_ERROR}</td></tr>
 *   <tr><td>{@link RedisSystemException}(根因为 NonRetryable 子类)</td>
 *       <td>{@link NonRetryableException}(根因透传)</td>
 *       <td>{@link ErrorCode#REDIS_DATA_ERROR}</td></tr>
 * </table>
 *
 * <p><b>类名注记:</b> Story 1.5b 决策表用 {@code ClusterStateException},实施时确认
 * Spring Data Redis 3.5.11 中实际类名为 {@link ClusterStateFailureException}
 * (集群拓扑变化中,如 MOVED/ASK 重定向超限).
 *
 * <p><b>保守原则:</b> {@link RedisSystemException} 根因不确定时归 {@link RetryableException},
 * 让 Story 1.7 {@code @Retryable} 在重试上限内自动消化,优于误判为 NonRetryable 直接进死信.
 *
 * <p>引用源: Story 1.5a 创建(透传);Story 1.5b 升级(异常映射 + JSON 路径).
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class RedisRepositoryImpl implements RedisRepository {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> objectRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final DefaultRedisScript<Number> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>("""
            local total = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return total
            """, Number.class);

    @Override
    public void set(String key, String value) {
        runWithMapping(() -> stringRedisTemplate.opsForValue().set(key, value), key, "set");
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        runWithMapping(() -> stringRedisTemplate.opsForValue().set(key, value, ttl), key, "set(ttl)");
    }

    @Override
    public void setKeepingTtl(String key, String value) {
        runWithMapping(() -> stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Expiration.keepTtl(),
                        RedisStringCommands.SetOption.UPSERT)), key, "set(keepTtl)");
    }

    @Override
    public String get(String key) {
        return supplyWithMapping(() -> stringRedisTemplate.opsForValue().get(key), key, "get");
    }

    @Override
    public void delete(String key) {
        runWithMapping(() -> stringRedisTemplate.delete(key), key, "delete");
    }

    @Override
    public boolean exists(String key) {
        Boolean result = supplyWithMapping(() -> stringRedisTemplate.hasKey(key), key, "exists");
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void expire(String key, Duration ttl) {
        runWithMapping(() -> stringRedisTemplate.expire(key, ttl), key, "expire");
    }

    @Override
    public long incrementBy(String key, long delta, Duration ttl) {
        Number total = supplyWithMapping(() -> stringRedisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT, List.of(key), delta, ttl.toMillis()), key, "incrementBy");
        return total == null ? delta : total.longValue();
    }

    @Override
    public <T> void setObject(String key, T value, Duration ttl) {
        runWithMapping(() -> objectRedisTemplate.opsForValue().set(key, value, ttl), key, "setObject");
    }

    @Override
    public <T> T getObject(String key, Class<T> type) {
        Object raw = supplyWithMapping(
                () -> objectRedisTemplate.opsForValue().get(key), key, "getObject");
        if (raw == null) {
            return null;
        }
        if (!type.isInstance(raw)) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    String.format("Redis 类型不匹配: key=%s, expected=%s, actual=%s",
                            key, type.getName(), raw.getClass().getName()));
        }
        return type.cast(raw);
    }

    @Override
    public void rPush(String key, String value) {
        runWithMapping(() -> stringRedisTemplate.opsForList().rightPush(key, value), key, "rPush");
    }

    @Override
    public String lPop(String key) {
        return supplyWithMapping(() -> stringRedisTemplate.opsForList().leftPop(key), key, "lPop");
    }

    @Override
    public long listLength(String key) {
        Long size = supplyWithMapping(() -> stringRedisTemplate.opsForList().size(key), key, "listLength");
        return size == null ? 0L : size;
    }

    @Override
    public <T> List<T> lRange(String key, long start, long end, Class<T> type) {
        List<String> values = supplyWithMapping(
                () -> stringRedisTemplate.opsForList().range(key, start, end), key, "lRange");
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (type == String.class) {
                result.add(type.cast(value));
                continue;
            }
            try {
                result.add(objectMapper.readValue(value, type));
            } catch (JsonProcessingException e) {
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "lRange 反序列化失败(Redis 数据异常): key=" + key
                                + ", expected=" + type.getName(),
                        e);
            }
        }
        return result;
    }

    /**
     * 包装无返回值的 Redis 操作,统一异常映射(委托给 {@link #supplyWithMapping}).
     */
    private void runWithMapping(Runnable action, String key, String operation) {
        supplyWithMapping(() -> {
            action.run();
            return null;
        }, key, operation);
    }

    /**
     * 包装有返回值的 Redis 操作,统一异常映射为业务异常.
     *
     * <p>异常映射顺序(由具体到通用):
     * <ol>
     *   <li>具体 Retryable 子类({@link RedisConnectionFailureException} 等)→ {@link RetryableException}</li>
     *   <li>具体 NonRetryable 子类({@link InvalidDataAccessApiUsageException} 等)→ {@link NonRetryableException}</li>
     *   <li>{@link RedisSystemException} 兜底 → 检查根因:NonRetryable 子类透传归类,否则保守归 Retryable</li>
     * </ol>
     *
     * <p>注: catch 顺序很重要,具体子类必须先于父类 {@link RedisSystemException} 捕获,
     * 否则会被兜底吞掉(Java 多态 catch 不区分父子,只看声明类型).
     */
    private <T> T supplyWithMapping(Supplier<T> action, String key, String operation) {
        try {
            return action.get();
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
