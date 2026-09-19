package com.choucj.aiaggregator.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.choucj.aiaggregator.monitoring.DependencyMetrics;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency.GITHUB;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind.HTTP;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation.FETCH;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation.GET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SlowOperationRecorderTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void should_record_without_warning_when_elapsed_is_below_threshold() {
        Fixture fixture = fixture();
        try (LogEventCapture capture = new LogEventCapture(SlowOperationRecorder.class)) {
            String result = fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
                fixture.clock().add(Duration.ofMillis(1999));
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            assertThat(timerCount(fixture.registry(), "success")).isEqualTo(1);
            assertThat(capture.events()).noneMatch(event -> event.getLevel() == Level.WARN);
        }
    }

    @Test
    void should_warn_at_and_above_threshold_with_mdc_and_whitelisted_fields() {
        for (long millis : List.of(2000L, 2001L)) {
            Fixture fixture = fixture();
            MDC.put("correlationId", "corr-canary");
            try (LogEventCapture capture = new LogEventCapture(SlowOperationRecorder.class)) {
                fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
                    fixture.clock().add(Duration.ofMillis(millis));
                    return "ok";
                });

                ILoggingEvent event = capture.events().stream()
                        .filter(item -> item.getLevel() == Level.WARN)
                        .findFirst().orElseThrow();
                assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "corr-canary");
                assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                        .containsExactly("event.action", "kind", "dependency", "operation",
                                "outcome", "elapsedMs", "errorCode");
                assertThat(event.getFormattedMessage()).doesNotContain("corr-canary");
            }
        }
    }

    @Test
    void should_record_failure_and_rethrow_same_exception_when_business_call_fails() {
        Fixture fixture = fixture();
        RuntimeException expected = new RuntimeException("secret-body-canary");
        try (LogEventCapture capture = new LogEventCapture(SlowOperationRecorder.class)) {
            assertThatThrownBy(() -> fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
                fixture.clock().add(Duration.ofSeconds(3));
                throw expected;
            })).isSameAs(expected);

            assertThat(timerCount(fixture.registry(), "failure")).isEqualTo(1);
            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("secret-body-canary");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @Test
    void should_preserve_business_result_when_observation_fails() {
        DependencyMetrics metrics = mock(DependencyMetrics.class);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(metrics).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        SlowOperationProperties properties = new SlowOperationProperties();
        MutableNanoClock clock = new MutableNanoClock();
        SlowOperationRecorder recorder = new SlowOperationRecorder(metrics, properties, clock::getAsLong);

        assertThat(recorder.observe(HTTP, GITHUB, FETCH, () -> "business-result"))
                .isEqualTo("business-result");
    }

    @Test
    void should_apply_redis_threshold_after_expected_wait() {
        Fixture fixture = fixture();
        fixture.properties().setRedisThreshold(Duration.ofMillis(100));
        try (LogEventCapture capture = new LogEventCapture(SlowOperationRecorder.class)) {
            fixture.recorder().observe(Kind.REDIS, Dependency.REDIS,
                    GET, Duration.ofMillis(200), () -> {
                fixture.clock().add(Duration.ofMillis(299));
                return "ok";
            });
            assertThat(capture.events()).noneMatch(event -> event.getLevel() == Level.WARN);

            fixture.recorder().observe(Kind.REDIS, Dependency.REDIS,
                    GET, Duration.ofMillis(200), () -> {
                fixture.clock().add(Duration.ofMillis(300));
                return "ok";
            });
            assertThat(capture.events()).anyMatch(event -> event.getLevel() == Level.WARN);
        }
    }

    @Test
    void should_classify_http_4xx_and_timeout_failures() {
        Fixture fixture = fixture();
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "not found", null, null, null);
        assertThatThrownBy(() -> fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
            throw notFound;
        })).isSameAs(notFound);
        RuntimeException timeoutWrapper = new RuntimeException(new SocketTimeoutException("timeout"));
        assertThatThrownBy(() -> fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
            throw timeoutWrapper;
        })).isSameAs(timeoutWrapper);

        assertThat(timerCount(fixture.registry(), "client_error")).isEqualTo(1);
        assertThat(timerCount(fixture.registry(), "timeout")).isEqualTo(1);
    }

    @Test
    void should_classify_io_failure_as_failure_and_redis_command_timeout_as_timeout() {
        Fixture fixture = fixture();
        // 连接拒绝等无超时 cause 的 I/O 故障不是 timeout (P-R2 裁定口径).
        ResourceAccessException connectionRefused =
                new ResourceAccessException("Connection refused");
        assertThatThrownBy(() -> fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
            throw connectionRefused;
        })).isSameAs(connectionRefused);

        // Lettuce 命令超时经 Spring 包装后必须仍归类 timeout (P-R6).
        QueryTimeoutException redisTimeout = new QueryTimeoutException(
                "Redis command timed out", new RedisCommandTimeoutException("command timeout"));
        assertThatThrownBy(() -> fixture.recorder().observe(Kind.REDIS, Dependency.REDIS, GET, () -> {
            throw redisTimeout;
        })).isSameAs(redisTimeout);

        assertThat(timerCount(fixture.registry(), "failure")).isEqualTo(1);
        assertThat(redisTimerCount(fixture.registry(), "timeout")).isEqualTo(1);
    }

    @Test
    void should_record_error_then_rethrow_same_error() {
        Fixture fixture = fixture();
        AssertionError expected = new AssertionError("canary");

        assertThatThrownBy(() -> fixture.recorder().observe(HTTP, GITHUB, FETCH, () -> {
            throw expected;
        })).isSameAs(expected);

        assertThat(timerCount(fixture.registry(), "failure")).isEqualTo(1);
    }

    private Fixture fixture() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DependencyMetrics metrics = new DependencyMetrics(registry);
        SlowOperationProperties properties = new SlowOperationProperties();
        MutableNanoClock clock = new MutableNanoClock();
        return new Fixture(registry, properties, new SlowOperationRecorder(metrics, properties, clock::getAsLong), clock);
    }

    private double timerCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get("aiaggregator.dependency.operation.duration")
                .tags("kind", "http", "dependency", "github", "operation", "fetch", "outcome", outcome)
                .timer().count();
    }

    private double redisTimerCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get("aiaggregator.dependency.operation.duration")
                .tags("kind", "redis", "dependency", "redis", "operation", "get", "outcome", outcome)
                .timer().count();
    }

    private record Fixture(SimpleMeterRegistry registry, SlowOperationProperties properties,
                           SlowOperationRecorder recorder, MutableNanoClock clock) {
    }

    private static final class MutableNanoClock {
        private final AtomicLong nanos = new AtomicLong();

        long getAsLong() {
            return nanos.get();
        }

        void add(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
