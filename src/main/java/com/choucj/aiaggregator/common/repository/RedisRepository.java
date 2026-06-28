package com.choucj.aiaggregator.common.repository;

import java.time.Duration;

/**
 * Redis 键值访问 Repository — 业务侧统一入口.
 *
 * <p>提供两条路径:
 * <ul>
 *   <li><b>String 路径(Story 1.5a):</b> {@link #set(String, String)} / {@link #get(String)} 等 6 方法,
 *       封装 {@code StringRedisTemplate},服务纯字符串(任务队列 taskId / 计数器)</li>
 *   <li><b>JSON 路径(Story 1.5b):</b> {@link #setObject(String, Object, Duration)} /
 *       {@link #getObject(String, Class)} 2 泛型方法,封装 {@code RedisTemplate<String, Object>},
 *       服务复杂对象(DO 缓存 — TaskOrderDO / RssArticleDO / LlmResponseDO)</li>
 * </ul>
 *
 * <p><b>异常处理(Story 1.5b 升级):</b>
 * 实现层包装所有 Redis 底层异常为业务异常,业务侧不再拿到裸
 * {@code RedisSystemException}:
 * <ul>
 *   <li>{@code RedisConnectionFailureException} / {@code QueryTimeoutException} /
 *       {@code ClusterStateException} → {@code RetryableException(REDIS_CONNECTION_ERROR)}</li>
 *   <li>{@code InvalidDataAccessApiUsageException} / {@code SerializationException} →
 *       {@code NonRetryableException(REDIS_DATA_ERROR)}</li>
 *   <li>裸 {@code RedisSystemException} → 保守归 {@code RetryableException},
 *       但根因为 NonRetryable 子类时透传归类</li>
 * </ul>
 *
 * <p>引用源: Story 1.5a 创建(String 6 方法); Story 1.5b 扩展(JSON 2 方法 + 异常映射);
 * 消费方包括 Story 1.6(任务队列)、2.x(数据缓存).
 *
 * @see com.choucj.aiaggregator.common.repository.RedisRepositoryImpl
 */
public interface RedisRepository {

    /**
     * 写入键值(无 TTL,永久存储).
     *
     * @param key   Redis 键,遵循 {@code namespace:sub:key} 命名(架构 L1301)
     * @param value 字符串值
     */
    void set(String key, String value);

    /**
     * 写入键值(带过期时间).
     *
     * @param key   Redis 键
     * @param value 字符串值
     * @param ttl   过期时间(不能为 null 或负数)
     */
    void set(String key, String value, Duration ttl);

    /**
     * 读取键值.
     *
     * @param key Redis 键
     * @return 值;键不存在返回 null
     */
    String get(String key);

    /**
     * 删除键.
     *
     * @param key Redis 键
     */
    void delete(String key);

    /**
     * 检查键是否存在.
     *
     * @param key Redis 键
     * @return true 表示键存在
     */
    boolean exists(String key);

    /**
     * 设置键的过期时间(键必须已存在).
     *
     * @param key Redis 键
     * @param ttl 过期时间(不能为 null 或负数)
     */
    void expire(String key, Duration ttl);

    /**
     * Story 1.5b: 写入复杂对象(JSON 序列化).
     *
     * <p>使用 {@code GenericJackson2JsonRedisSerializer} 序列化,Redis 中存储为含 {@code @class}
     * 元信息的 JSON 字符串,可被 {@code redis-cli} 直接阅读.
     *
     * @param key   Redis 键,遵循 {@code namespace:sub:key} 命名
     * @param value 任意可序列化对象(推荐 POJO;避免 {@code Object} 无属性类型)
     * @param ttl   过期时间(不能为 null 或负数)
     * @param <T>   对象类型
     * @throws com.choucj.aiaggregator.common.exception.RetryableException Redis 连接失败(可重试)
     * @throws com.choucj.aiaggregator.common.exception.NonRetryableException 序列化失败 / 参数非法(不可重试)
     */
    <T> void setObject(String key, T value, Duration ttl);

    /**
     * Story 1.5b: 读取复杂对象(JSON 反序列化).
     *
     * <p>从 Redis 读 JSON,按 {@link GenericJackson2JsonRedisSerializer} 反序列化为 {@code T}.
     * 业务侧必须显式传 {@code type} 用于类型安全(避免 {@code Map<String,Object>} 反序列化陷阱).
     *
     * @param key  Redis 键
     * @param type 期望类型 Class(用于类型 cast,非 Jackson 反序列化的目标类 — 后者由 {@code @class} 元信息决定)
     * @param <T>  对象类型
     * @return 反序列化对象;键不存在返回 null
     * @throws com.choucj.aiaggregator.common.exception.RetryableException Redis 连接失败
     * @throws com.choucj.aiaggregator.common.exception.NonRetryableException 类型不匹配 / 序列化失败
     */
    <T> T getObject(String key, Class<T> type);
}
