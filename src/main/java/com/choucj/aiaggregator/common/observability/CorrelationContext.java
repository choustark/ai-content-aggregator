package com.choucj.aiaggregator.common.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 管理一次业务处理链的 MDC 生命周期，使关键日志可以被同一个关联标识检索。
 *
 * <p>{@code correlationId} 仅用于日志关联，不是分布式追踪的 {@code traceId}，也不承诺
 * span、采样或跨进程传播语义。
 *
 * <p>引用源：Story 10.2（创建）。
 */
public final class CorrelationContext {

    /** 日志关联标识的 MDC key。 */
    public static final String CORRELATION_ID_KEY = "correlationId";
    /** 队列任务标识的 MDC key。 */
    public static final String TASK_ID_KEY = "taskId";
    /** 文章标识的 MDC key。 */
    public static final String ARTICLE_ID_KEY = "articleId";

    private CorrelationContext() {
    }

    /**
     * 开始新的日志关联上下文并清除线程上的旧值，以防共享线程串联两个业务任务。
     *
     * @param taskId 当前队列任务标识；不可用时传 {@code null}
     * @return 新生成的不可预测关联标识
     */
    public static String begin(String taskId) {
        MDC.clear();
        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID_KEY, correlationId);
        putTaskId(taskId);
        return correlationId;
    }

    /**
     * 在任务被领取后补充队列任务标识，使后续日志无需逐条显式传递该字段。
     *
     * @param taskId 当前队列任务标识；为空时移除已有值
     */
    public static void putTaskId(String taskId) {
        putIfPresent(TASK_ID_KEY, taskId);
    }

    /**
     * 补充文章标识，使手动发布链路的日志可按业务对象检索。
     *
     * @param articleId 当前文章标识；为空时移除已有值
     */
    public static void putArticleId(String articleId) {
        putIfPresent(ARTICLE_ID_KEY, articleId);
    }

    /**
     * 读取当前关联标识并在上下文缺失时快速失败，以暴露漏接入的异步边界。
     *
     * @return 当前日志关联标识
     * @throws IllegalStateException 当前线程没有关联上下文时抛出
     */
    public static String require() {
        String correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalStateException("当前线程缺少 correlationId 日志关联上下文");
        }
        return correlationId;
    }

    /**
     * 结束关联上下文并清除线程的全部 MDC 数据，以防线程池复用时发生串号。
     */
    public static void end() {
        MDC.clear();
    }

    private static void putIfPresent(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
