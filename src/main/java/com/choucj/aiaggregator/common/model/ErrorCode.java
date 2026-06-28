package com.choucj.aiaggregator.common.model;

/**
 * 全局错误码枚举.
 *
 * <p>Story 1.3 仅定义核心 4 类(架构文档 1304-1309 要求), 后续 Story 按需在自己的范围内扩展,
 * 不在本枚举中预测性枚举(避免 YAGNI):
 *
 * <ul>
 *   <li>Story 1.5: {@code REDIS_CONNECTION_ERROR} / {@code REDIS_CLUSTER_ERROR}</li>
 *   <li>Story 2.1: {@code RSSHUB_TIMEOUT} / {@code RSSHUB_PARSE_ERROR}</li>
 *   <li>Story 2.2: {@code TWITTER_FETCH_FAILED} / {@code TWITTER_RATE_LIMIT}</li>
 *   <li>Story 2.4: {@code LLM_TIMEOUT} / {@code LLM_RATE_LIMIT} / {@code LLM_INVALID_RESPONSE}</li>
 *   <li>Story 3.1: {@code WECHAT_API_ERROR} / {@code WECHAT_TOKEN_EXPIRED} / {@code WECHAT_INVALID_CREDENTIAL}</li>
 * </ul>
 *
 * <p>引用源:Story 1.4(异常类基类) / Story 1.5(Redis 异常) / Story 2.x(Twitter+LLM) / Story 3.1(微信)。
 */
public enum ErrorCode {
    /** 可重试错误 — 网络抖动、限流、临时不可用等. */
    RETRYABLE_ERROR,

    /** 不可重试错误 — 参数错误、配置缺失、业务规则违反等. */
    NON_RETRYABLE_ERROR,

    /** 需要降级 — 如 LLM 不可用时跳过 AI 评分,直接用规则筛选. */
    DEGRADATION_NEEDED,

    /** 内部错误 — 未知异常,通常对应未被捕获的 Throwable. */
    INTERNAL_ERROR,

    /**
     * Redis 连接相关失败(可重试场景).
     *
     * <p>Story 1.5b 引入,用于 {@code RetryableException} 包装 Spring Data Redis 的:
     * <ul>
     *   <li>{@code RedisConnectionFailureException} — Lettuce 连接失败</li>
     *   <li>{@code QueryTimeoutException} / {@code RedisCommandTimeoutException} — 命令超时</li>
     *   <li>{@code ClusterStateException} — 集群拓扑变化中</li>
     *   <li>裸 {@code RedisSystemException} — 根因不确定时保守归类</li>
     * </ul>
     *
     * <p>HTTP 响应 503 + 触发 {@code @Retryable}(Story 1.7)自动重试.
     */
    REDIS_CONNECTION_ERROR,

    /**
     * Redis 数据相关失败(不可重试场景).
     *
     * <p>Story 1.5b 引入,用于 {@code NonRetryableException} 包装 Spring Data Redis 的:
     * <ul>
     *   <li>{@code InvalidDataAccessApiUsageException} — 参数错(null key / 类型不匹配)</li>
     *   <li>{@code SerializationException} — Jackson 序列化/反序列化失败(DO 结构问题)</li>
     *   <li>{@code RedisSystemException} 根因为上述 NonRetryable 子类时透传归类</li>
     * </ul>
     *
     * <p>HTTP 响应 400 + 任务队列(Story 1.6)进死信,不再重试.
     */
    REDIS_DATA_ERROR,

    /**
     * 外部 API 调用失败(可重试或不可重试, 由抛出的异常类型决定).
     *
     * <p>Story 2.1 引入,用于包装 RSSHub / FxTwitter / LLM / 微信等第三方 HTTP API 的失败:
     * <ul>
     *   <li>连接异常 / 超时 — {@code RetryableException(EXTERNAL_API_ERROR)}, 切下一实例或重试</li>
     *   <li>HTTP 429 / 5xx — {@code RetryableException(EXTERNAL_API_ERROR)}, 切下一实例</li>
     *   <li>HTTP 4xx(除 429) — {@code NonRetryableException(EXTERNAL_API_ERROR)}, 跳过该实例</li>
     *   <li>全部实例失败 — {@code RetryableException(EXTERNAL_API_ERROR)}, 由调用方决定是否重试</li>
     * </ul>
     *
     * <p>设计上不区分 RSSHUB_TIMEOUT / TWITTER_FETCH_FAILED 等具体来源, 因为重试策略相同(切实例 + 退避),
     * 区分来源由日志消息(message)承担. 后续 Story 若需独立重试策略再扩展.
     *
     * <p>架构 delta: 见 architecture.md Story 2.1 Delta 段落.
     */
    EXTERNAL_API_ERROR
}
