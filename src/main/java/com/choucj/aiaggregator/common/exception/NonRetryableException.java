package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 不可重试异常 — 永久性失败,重试无意义.
 *
 * <p><b>典型场景:</b>
 * <ul>
 *   <li>参数非法(URL 格式错,JSON 解析失败)</li>
 *   <li>配置错误(API key 缺失,RSS feed URL 错误)</li>
 *   <li>业务规则违反(创新评分 &lt; 阈值,内容已存在)</li>
 *   <li>凭证失效需人工介入(微信 AppSecret 错误,Twitter cookie 全部失效)</li>
 *   <li>客户端错误(HTTP 4xx:400/401/403/404)</li>
 * </ul>
 *
 * <p><b>处理策略</b>(由 {@link GlobalExceptionHandler} 实现):
 * <ul>
 *   <li>HTTP 响应:400 BAD_REQUEST</li>
 *   <li>日志级别:{@code log.error}(业务规则违反,需立即排查)</li>
 *   <li>任务队列(Story 1.6):进入死信队列,不再重试</li>
 * </ul>
 *
 * <p><b>errorCode 设计:</b>
 * 默认 {@link ErrorCode#NON_RETRYABLE_ERROR},但<b>允许业务侧自定义</b>
 * (因业务侧错误类型多样,后续 Story 会扩展
 * {@code INVALID_CONFIG} / {@code INVALID_INPUT} / {@code BUSINESS_RULE_VIOLATION} 等).
 *
 * <p>引用源:Story 1.5(Redis 配置错) / Story 2.1(URL 非法) /
 * Story 2.3(评分低于阈值) / Story 3.1(微信凭证错).
 */
public class NonRetryableException extends AggregatorException {

    @Override
    public ErrorCode getErrorCode() {
        return super.getErrorCode();
    }

    /**
     * 构造不可重试异常(默认 errorCode,无 cause).
     *
     * <p>使用场景:业务侧未指定具体 ErrorCode,使用通用的 NON_RETRYABLE_ERROR.
     *
     * @param message 异常消息,描述违反的具体业务规则
     */
    public NonRetryableException(String message) {
        super(ErrorCode.NON_RETRYABLE_ERROR, message);
    }

    /**
     * 构造不可重试异常(默认 errorCode,含 cause).
     *
     * @param message 异常消息
     * @param cause   底层异常
     */
    public NonRetryableException(String message, Throwable cause) {
        super(ErrorCode.NON_RETRYABLE_ERROR, message, cause);
    }

    /**
     * 构造不可重试异常(自定义 errorCode,无 cause).
     *
     * <p>使用场景:业务侧需要更细粒度错误分类,如:
     * <pre>{@code
     * throw new NonRetryableException(ErrorCode.INVALID_CONFIG, "API key 未配置");
     * }</pre>
     *
     * @param errorCode 自定义错误码(后续 Story 扩展的 ErrorCode 值)
     * @param message   异常消息
     */
    public NonRetryableException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 构造不可重试异常(自定义 errorCode,含 cause).
     *
     * @param errorCode 自定义错误码
     * @param message   异常消息
     * @param cause     底层异常
     */
    public NonRetryableException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
