package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.model.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 — 拦截所有 {@code @RestController} 抛出的异常,统一转 HTTP 响应.
 *
 * <p><b>4 类异常映射:</b>
 *
 * <table border="1">
 *   <tr><th>异常类型</th><th>HTTP</th><th>日志</th><th>说明</th></tr>
 *   <tr><td>{@link RetryableException}</td><td>503</td><td>warn</td>
 *       <td>临时失败,运维关注但不告警;触发任务重试</td></tr>
 *   <tr><td>{@link NonRetryableException}</td><td>400</td><td>error</td>
 *       <td>业务规则违反,需立即排查;进入死信队列</td></tr>
 *   <tr><td>{@link DegradationException}</td><td>503</td><td>warn</td>
 *       <td>主方案失败且未被业务侧接住,返回通用降级消息(防信息泄漏)</td></tr>
 *   <tr><td>{@link Exception}(兜底)</td><td>500</td><td>error</td>
 *       <td>未预期异常,隐藏 stacktrace 信息泄露</td></tr>
 * </table>
 *
 * <p><b>设计决策(架构文档同步):</b>
 * <ul>
 *   <li>{@link #handleDegradation(DegradationException)} 直接返回 503 + 通用消息 "服务降级,请稍后重试",
 *       不重抛 — Spring MVC 的 {@code ExceptionHandlerExceptionResolver} 不会将重抛异常路由到
 *       同 advice 内其他 handler,重抛会以 {@code ServletException} 形式泄漏到容器,
 *       导致响应体为空 + 错误消息暴露(违反 AR8 信息泄漏防护).</li>
 *   <li>DegradationException 的<b>正常处理路径</b>是: 异步任务/Service 层显式 catch 后切换备用方案,
 *       不应让异常流到 RestControllerAdvice. 此 handler 仅作"REST 入口兜底安全网".</li>
 *   <li>{@link #handleUnexpected(Exception)} 返回通用消息 "系统错误",不暴露原始 stacktrace
 *       — 合规要求 AR8 / 架构 L1498-1503 防止信息泄露</li>
 * </ul>
 *
 * <p><b>引用源:</b>Story 1.4 创建;被所有 RestController 隐式使用.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理可重试异常.
     *
     * <p>记 warn 级别日志(含完整 cause stacktrace,便于运维排查),
     * 返回 503 + ErrorResponse.
     *
     * @param e 业务侧抛出的可重试异常
     * @return 503 SERVICE_UNAVAILABLE + ErrorResponse
     */
    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<ErrorResponse> handleRetryable(RetryableException e) {
        log.warn("可重试异常 [{}]: {}", e.getErrorCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    /**
     * 处理不可重试异常.
     *
     * <p>记 error 级别日志(业务规则违反,需立即排查),
     * 返回 400 + ErrorResponse.
     *
     * @param e 业务侧抛出的不可重试异常
     * @return 400 BAD_REQUEST + ErrorResponse
     */
    @ExceptionHandler(NonRetryableException.class)
    public ResponseEntity<ErrorResponse> handleNonRetryable(NonRetryableException e) {
        log.error("不可重试异常 [{}]: {}", e.getErrorCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    /**
     * 处理降级异常 — REST 入口的安全网,返回 503 + 通用降级消息.
     *
     * <p><b>注意:</b> DegradationException 的<b>正常处理路径</b>是异步任务/Service 层显式 catch
     * 后切换备用方案(如 LLM 超时切规则筛选、Redis 不可用切内存缓存).
     * 异常流到 RestControllerAdvice 本身表示业务侧未接住,此 handler 仅作兜底.
     *
     * <p><b>不重抛的原因:</b> Spring MVC 的 {@code ExceptionHandlerExceptionResolver}
     * 不会将重抛异常路由到同 advice 内其他 handler(如 {@link #handleUnexpected}),
     * 重抛会以 {@code ServletException} 形式泄漏到 servlet 容器,导致:
     * <ul>
     *   <li>HTTP 响应体为空(无 ErrorResponse 结构)</li>
     *   <li>异常消息通过 servlet 错误页暴露给客户端(违反 AR8 信息泄漏防护)</li>
     * </ul>
     *
     * <p>返回 503 而非 500: 降级通常是瞬态(LLM 超时、Redis 短暂不可用),
     * 客户端可在稍后重试拿到非降级响应.
     *
     * @param e 业务侧抛出的降级异常
     * @return 503 SERVICE_UNAVAILABLE + ErrorResponse(message 固定 "服务降级,请稍后重试")
     */
    @ExceptionHandler(DegradationException.class)
    public ResponseEntity<ErrorResponse> handleDegradation(DegradationException e) {
        log.warn("降级异常 [{}]: {}", e.getErrorCode(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(e.getErrorCode(), "服务降级,请稍后重试"));
    }

    /**
     * 兜底处理所有未预期的异常.
     *
     * <p>记 error 级别日志(含完整 stacktrace,内部排查用),
     * 返回 500 + 通用消息 <b>"系统错误"</b>.
     *
     * <p><b>信息泄露防护:</b> 响应体 {@code message} 不使用 {@code e.getMessage()},
     * 避免 SQL 错误 / NPE stacktrace 通过响应体暴露给客户端(合规 AR8 / 架构 L1498-1503).
     *
     * @param e 未被上面 handler 捕获的任意异常
     * @return 500 INTERNAL_SERVER_ERROR + ErrorResponse(message 固定 "系统错误")
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR, "系统错误"));
    }
}
