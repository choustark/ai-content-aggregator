package com.choucj.aiaggregator.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 捕获编码前的日志事件与即时 MDC 快照，使断言不依赖控制台格式和清理时机。 */
public final class LogEventCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    /** 绑定指定类的日志，以便仅检查该调用边界的行为。 */
    public LogEventCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    /** 返回已捕获事件，用于级别、正文和关联字段断言。 */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** 移除测试 appender，避免影响同 JVM 的后续测试。 */
    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
