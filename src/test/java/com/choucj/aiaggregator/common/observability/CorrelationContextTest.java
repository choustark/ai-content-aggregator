package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证关联上下文的生命周期，因为共享线程上的残留 MDC 会把不同任务错误串联。
 *
 * <p>引用源：Story 10.2（创建）。
 */
class CorrelationContextTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void should_create_and_clear_context_when_begin_and_end_are_called() {
        String correlationId = CorrelationContext.begin("task-101");

        assertThat(correlationId).isNotBlank().isEqualTo(CorrelationContext.require());
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo("task-101");

        CorrelationContext.end();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThatThrownBy(CorrelationContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    void should_not_leak_outer_context_when_nested_context_ends() {
        String outer = CorrelationContext.begin("outer-task");
        String inner = CorrelationContext.begin("inner-task");

        assertThat(inner).isNotEqualTo(outer).isEqualTo(CorrelationContext.require());
        assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo("inner-task");

        CorrelationContext.end();

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_omit_task_id_when_task_id_is_unavailable() {
        CorrelationContext.begin(null);

        assertThat(CorrelationContext.require()).isNotBlank();
        assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isNull();
    }
}
