package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 降级异常 — 主方案失败,触发备用方案.
 *
 * <p><b>典型场景:</b>
 * <ul>
 *   <li>LLM 调用超时(主方案)→ 切规则筛选(备用方案)</li>
 *   <li>Redis 向量检索不可用 → 切内存缓存或跳过 RAG</li>
 *   <li>多模型投票失败 → 单模型输出</li>
 *   <li>FxTwitter 不可用 → 切 twscrape 备用通道</li>
 * </ul>
 *
 * <p><b>处理策略</b>(与 {@link RetryableException} / {@link NonRetryableException} 不同):
 * <ul>
 *   <li><b>正常路径</b>: 异步任务/Service 层显式 catch 后切换备用方案
 *       (如 Story 2.4 LLM 主模型超时切备用、Story 2.5 Redis 不可用切内存缓存)</li>
 *   <li><b>REST 入口兜底</b>: 若异常流到 {@link GlobalExceptionHandler#handleDegradation},
 *       返回 503 + 通用消息 "服务降级,请稍后重试"(防信息泄漏).
 *       此 handler <b>不重抛</b> — Spring MVC 不会将重抛异常路由到同 advice 其他 handler,
 *       重抛会以 {@code ServletException} 形式泄漏到容器</li>
 *   <li>任务队列(Story 1.6): 捕获并触发降级流程,而非重新入队</li>
 * </ul>
 *
 * <p><b>errorCode 固定为</b> {@link ErrorCode#DEGRADATION_NEEDED},
 * 不允许业务侧覆盖(语义明确,无需扩展).
 *
 * <p>引用源:Story 2.4(LLM 主模型超时切备用) / Story 2.5(Redis 不可用切内存) /
 * Story 5.x(多模型投票失败切单模型).
 */
public class DegradationException extends AggregatorException {

    @Override
    public ErrorCode getErrorCode() {
        return super.getErrorCode();
    }

    /**
     * 构造降级异常(无 cause).
     *
     * @param message 异常消息,描述触发降级的原因(如 "DeepSeek 超时,切换 GLM")
     */
    public DegradationException(String message) {
        super(ErrorCode.DEGRADATION_NEEDED, message);
    }

    /**
     * 构造降级异常(含 cause).
     *
     * @param message 异常消息
     * @param cause   底层异常(通常为主方案失败的原始异常,如 TimeoutException)
     */
    public DegradationException(String message, Throwable cause) {
        super(ErrorCode.DEGRADATION_NEEDED, message, cause);
    }
}
