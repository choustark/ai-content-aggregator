package com.choucj.aiaggregator.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一错误响应模型.
 *
 * <p>由 Story 1.4 的 {@code GlobalExceptionHandler} 包装返回给客户端. 字段最小化, 只包含
 * {@code code} + {@code message} 两字段(架构文档 1498-1503 要求). {@code timestamp} /
 * {@code path} / {@code traceId} 等诊断字段留待 P1 增强, 本 story 不引入.
 *
 * <p>引用源:Story 1.4(GlobalExceptionHandler 返回类型)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** 错误码, 对应 {@link ErrorCode} 枚举值. */
    private ErrorCode code;

    /** 人类可读错误消息, 已国际化处理(后续 P1). */
    private String message;
}
