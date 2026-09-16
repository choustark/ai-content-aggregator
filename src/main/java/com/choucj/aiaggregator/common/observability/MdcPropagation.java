package com.choucj.aiaggregator.common.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 装饰异步任务以传播提交时刻的 MDC 快照，并在执行结束后清理工作线程防止串号。
 *
 * <p>引用源：Story 10.2（创建）。
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * 捕获当前 MDC 并包装 {@link Runnable}，使平台线程池与虚拟线程使用同一传播纪律。
     *
     * @param task 待执行任务
     * @return 带 MDC 快照恢复和 finally 清理的任务
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            replay(context);
            try {
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 捕获当前 MDC 并包装 {@link Callable}，使返回值和异常保持原语义且工作线程最终无残留。
     *
     * @param task 待执行任务
     * @param <T> 返回值类型
     * @return 带 MDC 快照恢复和 finally 清理的任务
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            replay(context);
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }

    private static void replay(Map<String, String> context) {
        MDC.clear();
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }
    }
}
