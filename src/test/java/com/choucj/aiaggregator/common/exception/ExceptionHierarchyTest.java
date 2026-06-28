package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 1.4 异常层级单测 — 验证继承关系 + 构造器签名 + errorCode 绑定.
 *
 * <p>覆盖 3 个具体子类:
 * <ul>
 *   <li>{@link RetryableException} — 2 个构造器, errorCode 强制 RETRYABLE_ERROR</li>
 *   <li>{@link NonRetryableException} — 4 个构造器, 允许自定义 errorCode</li>
 *   <li>{@link DegradationException} — 2 个构造器, errorCode 强制 DEGRADATION_NEEDED</li>
 * </ul>
 *
 * <p>防回归重点:
 * <ul>
 *   <li>errorCode 默认值不被业务侧覆盖（Retryable/Degradation 强制固定）</li>
 *   <li>cause 传递正确（避免 RuntimeException 包装时丢堆栈）</li>
 *   <li>{@link AggregatorException#getErrorCode()} 返回值与构造器入参一致</li>
 * </ul>
 */
class ExceptionHierarchyTest {

    @Test
    void shouldRetryableExceptionDefaultErrorCodeBeRetryableError() {
        RetryableException ex = new RetryableException("网络超时");

        assertThat(ex.getErrorCode())
                .as("RetryableException 的 errorCode 必须强制为 RETRYABLE_ERROR, 不允许业务侧覆盖")
                .isEqualTo(ErrorCode.RETRYABLE_ERROR);
        assertThat(ex.getMessage()).isEqualTo("网络超时");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldRetryableExceptionPreserveCauseWhenProvided() {
        Throwable cause = new java.io.IOException("connection reset");
        RetryableException ex = new RetryableException("网络超时", cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RETRYABLE_ERROR);
        assertThat(ex.getMessage()).isEqualTo("网络超时");
        assertThat(ex.getCause())
                .as("cause 必须正确传递, 避免排查时丢堆栈")
                .isSameAs(cause);
    }

    @Test
    void shouldNonRetryableExceptionDefaultToNonRetryableErrorCode() {
        NonRetryableException ex = new NonRetryableException("参数非法");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NON_RETRYABLE_ERROR);
        assertThat(ex.getMessage()).isEqualTo("参数非法");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldNonRetryableExceptionAcceptDefaultCodeWithCause() {
        Throwable cause = new IllegalArgumentException("malformed URL");
        NonRetryableException ex = new NonRetryableException("URL 非法", cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NON_RETRYABLE_ERROR);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldNonRetryableExceptionAcceptCustomErrorCode() {
        NonRetryableException ex =
                new NonRetryableException(ErrorCode.INTERNAL_ERROR, "自定义错误码场景");

        assertThat(ex.getErrorCode())
                .as("NonRetryableException 允许业务侧自定义 errorCode, 用于细粒度分类")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ex.getMessage()).isEqualTo("自定义错误码场景");
    }

    @Test
    void shouldNonRetryableExceptionAcceptCustomCodeWithCause() {
        Throwable cause = new RuntimeException("config missing");
        NonRetryableException ex =
                new NonRetryableException(ErrorCode.INTERNAL_ERROR, "配置错误", cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldDegradationExceptionDefaultErrorCodeBeDegradationNeeded() {
        DegradationException ex = new DegradationException("DeepSeek 超时, 切换 GLM");

        assertThat(ex.getErrorCode())
                .as("DegradationException 的 errorCode 必须强制为 DEGRADATION_NEEDED")
                .isEqualTo(ErrorCode.DEGRADATION_NEEDED);
        assertThat(ex.getMessage()).isEqualTo("DeepSeek 超时, 切换 GLM");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldDegradationExceptionPreserveCauseWhenProvided() {
        Throwable cause = new java.util.concurrent.TimeoutException("LLM 调用 30s 超时");
        DegradationException ex = new DegradationException("DeepSeek 超时, 切换 GLM", cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEGRADATION_NEEDED);
        assertThat(ex.getCause())
                .as("cause 应保留主方案失败的原始异常, 便于排查降级触发点")
                .isSameAs(cause);
    }

    @Test
    void shouldAllThreeSubclassesExtendAggregatorException() {
        assertThat(AggregatorException.class)
                .as("所有具体异常必须继承 AggregatorException, GlobalExceptionHandler 才能统一拦截")
                .isAssignableFrom(RetryableException.class)
                .isAssignableFrom(NonRetryableException.class)
                .isAssignableFrom(DegradationException.class);
    }

    @Test
    void shouldAllSubclassesBeRuntimeExceptionForUncheckedSemantics() {
        assertThat(RuntimeException.class)
                .as("AggregatorException 继承 RuntimeException, 业务侧无需强制 try/catch")
                .isAssignableFrom(AggregatorException.class);
    }
}
