package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 资源不存在异常 — REST 查询语义的 404 分支.
 *
 * <p>用于"查询目标不存在或已过期"这类客户端可理解的空结果, 与
 * {@link NonRetryableException} 的参数/业务规则错误 (400) 区分.
 */
public class NotFoundException extends AggregatorException {

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
