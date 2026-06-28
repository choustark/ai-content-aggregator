package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 可重试异常 — 临时性失败,重试可能成功.
 *
 * <p><b>典型场景:</b>
 * <ul>
 *   <li>网络超时 / 连接重置(HTTP 5xx, Gateway Timeout)</li>
 *   <li>限流(Twitter API 429, LLM rate limit)</li>
 *   <li>Redis 主从切换瞬间不可用</li>
 *   <li>RSSHUB / FxTwitter 服务短暂不可用</li>
 * </ul>
 *
 * <p><b>处理策略</b>(由 {@link GlobalExceptionHandler} 实现):
 * <ul>
 *   <li>HTTP 响应:503 SERVICE_UNAVAILABLE</li>
 *   <li>日志级别:{@code log.warn}(可恢复,运维关注但不告警)</li>
 *   <li>任务队列(Story 1.6):重新入队,延迟重试(指数退避)</li>
 *   <li>Spring Retry(Story 1.7):触发 {@code @Retryable} 自动重试</li>
 * </ul>
 *
 * <p><b>errorCode 设计:</b>
 * 默认 {@link ErrorCode#RETRYABLE_ERROR},但<b>允许业务侧自定义</b>
 * (Story 1.5b 起扩展,对称 {@link NonRetryableException} 的设计,用于 Redis 连接异常
 * 使用 {@link ErrorCode#REDIS_CONNECTION_ERROR} 做细粒度分类).
 *
 * <p>引用源:Story 1.5(Redis) / Story 2.1(RSSHUB) / Story 2.2(Twitter) /
 * Story 2.4(LLM) / Story 3.1(微信 token 过期).
 */
public class RetryableException extends AggregatorException {

    @Override
    public ErrorCode getErrorCode() {
        return super.getErrorCode();
    }

    /**
     * 构造可重试异常(无 cause).
     *
     * @param message 异常消息,描述失败的上下文(如 "RSSHUB 调用超时: url=xxx")
     */
    public RetryableException(String message) {
        super(ErrorCode.RETRYABLE_ERROR, message);
    }

    /**
     * 构造可重试异常(包装底层异常堆栈).
     *
     * <p>使用场景:业务侧 catch 底层 {@code WebClientResponseException} /
     * {@code IOException} 后包装成本异常抛出,保留 cause 便于排查.
     *
     * @param message 异常消息
     * @param cause   底层异常(建议非 null)
     */
    public RetryableException(String message, Throwable cause) {
        super(ErrorCode.RETRYABLE_ERROR, message, cause);
    }

    /**
     * 构造可重试异常(自定义 errorCode,无 cause).
     *
     * <p>使用场景:业务侧需要更细粒度错误分类,如 Story 1.5b Redis 连接异常:
     * <pre>{@code
     * throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败: key=" + key);
     * }</pre>
     *
     * <p><b>设计变更(Story 1.5b delta):</b> 原 Story 1.4 设计固定 errorCode 不允许覆盖,
     * Story 1.5b 实施时为对称 {@link NonRetryableException} 的细粒度分类能力而开放.
     *
     * @param errorCode 自定义错误码(如 {@link ErrorCode#REDIS_CONNECTION_ERROR})
     * @param message   异常消息
     */
    public RetryableException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 构造可重试异常(自定义 errorCode,含 cause).
     *
     * @param errorCode 自定义错误码
     * @param message   异常消息
     * @param cause     底层异常
     */
    public RetryableException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
