package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency.GITHUB;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind.HTTP;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation.FETCH;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Outcome.SERVER_ERROR;
import static com.choucj.aiaggregator.monitoring.DependencyMetrics.Outcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

class DependencyMetricsTest {

    @Test
    void should_record_timer_with_closed_low_cardinality_tags_when_operation_finishes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DependencyMetrics metrics = new DependencyMetrics(registry);

        metrics.record(HTTP, GITHUB, FETCH, SUCCESS, Duration.ofMillis(25));

        Timer timer = registry.get("aiaggregator.dependency.operation.duration")
                .tags("kind", "http", "dependency", "github", "operation", "fetch", "outcome", "success")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(25.0);
        Set<String> tagKeys = timer.getId().getTags().stream()
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertThat(tagKeys).containsExactlyInAnyOrder("kind", "dependency", "operation", "outcome");
    }

    @Test
    void should_increment_failure_gauge_and_reset_when_success_follows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DependencyMetrics metrics = new DependencyMetrics(registry);

        metrics.record(HTTP, GITHUB, FETCH, SERVER_ERROR, Duration.ofMillis(10));
        metrics.record(HTTP, GITHUB, FETCH, SERVER_ERROR, Duration.ofMillis(10));
        assertThat(registry.get("aiaggregator.external.dependency.consecutive.failures")
                .tag("dependency", "github").gauge().value()).isEqualTo(2.0);

        metrics.record(HTTP, GITHUB, FETCH, SUCCESS, Duration.ofMillis(10));
        assertThat(registry.get("aiaggregator.external.dependency.consecutive.failures")
                .tag("dependency", "github").gauge().value()).isZero();
    }

    @Test
    void should_expose_only_declared_tag_values_when_enums_are_inspected() {
        assertThat(Arrays.stream(DependencyMetrics.Kind.values())
                .map(DependencyMetrics.Kind::tagValue)).containsExactly("redis", "http", "sdk");
        assertThat(Arrays.stream(DependencyMetrics.Outcome.values())
                .map(DependencyMetrics.Outcome::tagValue))
                .containsExactly("success", "client_error", "server_error", "timeout", "failure");
    }
}
