package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 MDC 提交时快照可跨线程传播且任务结束后被清除，以防平台线程池复用时串号。
 *
 * <p>引用源：Story 10.2（创建）。
 */
class MdcPropagationTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        executor.shutdownNow();
    }

    @Test
    void should_propagate_runnable_context_when_worker_is_reused() throws Exception {
        String correlationId = CorrelationContext.begin("task-202");
        Future<?> first = executor.submit(MdcPropagation.wrap(() -> {
            assertThat(CorrelationContext.require()).isEqualTo(correlationId);
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo("task-202");
        }));
        first.get();
        CorrelationContext.end();

        Future<String> second = executor.submit(() -> MDC.get(CorrelationContext.CORRELATION_ID_KEY));

        assertThat(second.get()).isNull();
    }

    @Test
    void should_propagate_callable_result_when_worker_task_fails() throws Exception {
        String correlationId = CorrelationContext.begin(null);
        Future<String> success = executor.submit(MdcPropagation.wrap(CorrelationContext::require));
        assertThat(success.get()).isEqualTo(correlationId);

        Future<?> failure = executor.submit(MdcPropagation.wrap(() -> {
            throw new IllegalStateException("boom");
        }));
        try {
            failure.get();
        } catch (Exception ignored) {
            // 这里只验证 finally 清理；业务异常仍由 Future 按原语义传播。
        }
        CorrelationContext.end();

        assertThat(executor.submit(MDC::getCopyOfContextMap).get()).isNullOrEmpty();
    }
}
