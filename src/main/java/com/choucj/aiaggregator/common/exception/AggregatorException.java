package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;
import lombok.Getter;
import lombok.NonNull;

/**
 * 业务异常基类 — 所有自定义异常的统一根.
 *
 * <p>继承 {@link RuntimeException}(非受检),避免业务侧强制 try/catch;
 * 持有 {@link ErrorCode} 用于 {@link GlobalExceptionHandler} 分类返回 HTTP 响应.
 *
 * <p><b>不直接抛出本类</b>(abstract),使用三个具体子类:
 * <ul>
 *   <li>{@link RetryableException} — 临时性失败,重试可能成功(网络抖动/限流/服务短暂不可用)</li>
 *   <li>{@link NonRetryableException} — 永久性失败,重试无意义(参数错/配置错/凭证失效)</li>
 *   <li>{@link DegradationException} — 主方案失败但有备用方案(LLM 超时切规则筛选)</li>
 * </ul>
 *
 * <p>引用源:Story 1.4(异常体系基类) / Story 1.5(Redis 异常分类抛出) /
 * Story 2.x(Twitter+LLM)/ Story 3.x(微信 API 错误).
 */
@Getter
public abstract class AggregatorException extends RuntimeException {

    /** 错误码(决定 HTTP 响应体 {@code code} 字段 + 日志分类). */
    private final ErrorCode errorCode;

    /**
     * 构造异常(无 cause).
     *
     * @param errorCode 错误码(非 null)
     * @param message   异常消息(可 null,但业务侧应避免)
     */
    protected AggregatorException(@NonNull ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造异常(含 cause,用于包装底层异常堆栈).
     *
     * @param errorCode 错误码(非 null)
     * @param message   异常消息
     * @param cause     底层异常(可为 null)
     */
    protected AggregatorException(@NonNull ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
