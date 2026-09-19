package com.choucj.aiaggregator.common.observability;

import com.choucj.aiaggregator.monitoring.DependencyMetrics;
import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Outcome;
import io.lettuce.core.RedisCommandTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 在真实 Redis、HTTP 与 SDK 边界统一记录耗时、结果和脱敏慢操作事件。
 *
 * <p>观测逻辑始终 fail-open：业务返回值、异常实例和既有重试/降级语义不会被改变。
 */
@Slf4j
@org.springframework.stereotype.Component
public class SlowOperationRecorder {

    private final DependencyMetrics metrics;
    private final SlowOperationProperties properties;
    private final LongSupplier nanoTime;

    /** 使用 JVM 单调时钟创建生产 recorder。 */
    @Autowired
    public SlowOperationRecorder(DependencyMetrics metrics, SlowOperationProperties properties) {
        this(metrics, properties, System::nanoTime);
    }

    SlowOperationRecorder(DependencyMetrics metrics, SlowOperationProperties properties,
                          LongSupplier nanoTime) {
        this.metrics = metrics;
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /** 记录一次无预期阻塞等待的实际外部调用。 */
    public <T> T observe(Kind kind, Dependency dependency, Operation operation, Supplier<T> action) {
        return observe(kind, dependency, operation, Duration.ZERO, action);
    }

    /**
     * 记录一次实际外部调用，并在慢日志阈值中容纳调用方设计内的预期等待时间。
     */
    public <T> T observe(Kind kind, Dependency dependency, Operation operation,
                         Duration expectedWait, Supplier<T> action) {
        long started = safeNanoTime();
        FailureClassification classification = FailureClassification.success();
        try {
            return action.get();
        } catch (RuntimeException | Error exception) {
            classification = classify(exception);
            throw exception;
        } finally {
            observeSafely(kind, dependency, operation, expectedWait, started,
                    classification.outcome(), classification.errorCode());
        }
    }

    /** 记录无返回值的实际外部调用。 */
    public void observe(Kind kind, Dependency dependency, Operation operation, Runnable action) {
        observe(kind, dependency, operation, () -> {
            action.run();
            return null;
        });
    }

    private long safeNanoTime() {
        try {
            return nanoTime.getAsLong();
        } catch (RuntimeException exception) {
            log.debug("慢操作单调时钟不可用，跳过本次观测: errorType={}",
                    exception.getClass().getSimpleName());
            return Long.MIN_VALUE;
        }
    }

    private void observeSafely(Kind kind, Dependency dependency, Operation operation,
                               Duration expectedWait, long started, Outcome outcome,
                               String errorCode) {
        if (started == Long.MIN_VALUE) {
            return;
        }
        try {
            long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - started);
            Duration elapsed = Duration.ofNanos(elapsedNanos);
            try {
                metrics.record(kind, dependency, operation, outcome, elapsed);
            } catch (RuntimeException exception) {
                log.debug("依赖指标记录失败，业务结果保持不变: errorType={}",
                        exception.getClass().getSimpleName());
            }
            Duration threshold = threshold(kind).plus(nonNegative(expectedWait));
            if (elapsed.compareTo(threshold) >= 0) {
                log.atWarn()
                        .addKeyValue("event.action", "slow_operation")
                        .addKeyValue("kind", kind.tagValue())
                        .addKeyValue("dependency", dependency.tagValue())
                        .addKeyValue("operation", operation.tagValue())
                        .addKeyValue("outcome", outcome.tagValue())
                        .addKeyValue("elapsedMs", elapsed.toMillis())
                        .addKeyValue("errorCode", errorCode)
                        .log("检测到外部依赖慢操作");
            }
        } catch (RuntimeException exception) {
            log.debug("慢操作观测失败，业务结果保持不变: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private Duration threshold(Kind kind) {
        return switch (kind) {
            case REDIS -> properties.getRedisThreshold();
            case HTTP -> properties.getHttpThreshold();
            case SDK -> properties.getSdkThreshold();
        };
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }

    private FailureClassification classify(Throwable exception) {
        if (exception instanceof RestClientResponseException responseException) {
            Outcome outcome = responseException.getStatusCode().is4xxClientError()
                    ? Outcome.CLIENT_ERROR : Outcome.SERVER_ERROR;
            return new FailureClassification(outcome, "http_" + responseException.getStatusCode().value());
        }
        if (exception instanceof ResourceAccessException) {
            // 连接拒绝/DNS 失败等 I/O 故障不是超时: 只有 cause 链上真实存在超时异常才记 TIMEOUT,
            // 否则 timeout 与 failure 两个 outcome 的告警语义会被连接层故障淹没.
            return new FailureClassification(Outcome.FAILURE, exception.getClass().getSimpleName());
        }
        if (hasTimeoutCause(exception)) {
            return new FailureClassification(Outcome.TIMEOUT, "timeout");
        }
        if (exception instanceof AggregatorException aggregatorException) {
            return new FailureClassification(Outcome.FAILURE, aggregatorException.getErrorCode().name());
        }
        return new FailureClassification(Outcome.FAILURE, exception.getClass().getSimpleName());
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException
                    || current instanceof QueryTimeoutException
                    || current instanceof RedisCommandTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record FailureClassification(Outcome outcome, String errorCode) {
        private static FailureClassification success() {
            return new FailureClassification(Outcome.SUCCESS, "none");
        }
    }
}
