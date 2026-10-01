package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskMetricsTest {

    private TaskQueue taskQueue;
    private SimpleMeterRegistry registry;
    private TaskMetrics metrics;

    @BeforeEach
    void setUp() {
        taskQueue = mock(TaskQueue.class);
        registry = new SimpleMeterRegistry();
        metrics = new TaskMetrics(taskQueue, registry,
                Clock.fixed(Instant.parse("2026-09-12T02:03:04Z"), ZoneOffset.UTC));
    }

    @Test
    void should_expose_zero_when_queue_is_empty() {
        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isZero();
        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "processing").gauge().value())
                .isZero();
    }

    @Test
    void should_expose_current_counts_without_incrementing_when_scraped_repeatedly() {
        when(taskQueue.pendingCount()).thenReturn(3L, 5L);
        when(taskQueue.processingCount()).thenReturn(2L, 4L);

        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isEqualTo(5);
        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "processing").gauge().value())
                .isEqualTo(2);
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getType() == Meter.Type.COUNTER)
                .mapToDouble(meter -> ((io.micrometer.core.instrument.Counter) meter).count()))
                .allMatch(value -> value == 0d);
    }

    @Test
    void should_return_last_good_value_when_queue_collection_fails() {
        when(taskQueue.pendingCount()).thenReturn(7L)
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isEqualTo(7);
    }

    @Test
    void should_freeze_last_collection_timestamp_when_queue_collection_fails() {
        Clock mutableClock = mock(Clock.class);
        when(mutableClock.instant()).thenReturn(
                Instant.parse("2026-09-12T02:03:04Z"),
                Instant.parse("2026-09-12T03:03:04Z"));
        SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
        new TaskMetrics(taskQueue, localRegistry, mutableClock);
        when(taskQueue.pendingCount()).thenReturn(7L)
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        assertThat(localRegistry.get("aiaggregator.task.queue.size")
                .tag("state", "pending").gauge().value()).isEqualTo(7);
        double firstCollection = localRegistry.get("aiaggregator.task.queue.collection.last.success")
                .tag("state", "pending").gauge().value();
        assertThat(firstCollection).isEqualTo(Instant.parse("2026-09-12T02:03:04Z").getEpochSecond());

        assertThat(localRegistry.get("aiaggregator.task.queue.size")
                .tag("state", "pending").gauge().value()).isEqualTo(7);
        assertThat(localRegistry.get("aiaggregator.task.queue.collection.last.success")
                .tag("state", "pending").gauge().value()).isEqualTo(firstCollection);
    }

    @Test
    void should_return_nan_when_first_queue_collection_fails() {
        when(taskQueue.processingCount())
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "redis down"));

        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "processing").gauge().value())
                .isNaN();
    }

    @Test
    void should_count_each_closed_outcome_once_when_recorded() {
        for (TaskMetrics.Outcome outcome : TaskMetrics.Outcome.values()) {
            metrics.recordProcessed(TaskMetrics.Source.UNKNOWN, outcome);
            assertThat(registry.get("aiaggregator.task.processed")
                    .tags("source", "unknown", "outcome", outcome.tagValue()).counter().count()).isEqualTo(1);
        }

        assertMeterIdsUseClosedLowCardinalityContract();
    }

    @Test
    void should_not_leak_unbounded_values_when_meter_ids_are_inspected() {
        String longId = "task-" + "9".repeat(256);
        String url = "https://private.example/items/secret-token";
        String correlationId = "correlation-sensitive-123";
        String errorBody = "upstream body contains account@example.com";
        List<String> canaries = List.of(longId, url, correlationId, errorBody);
        when(taskQueue.pendingCount()).thenThrow(new IllegalStateException(String.join(" | ", canaries)));

        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "pending").gauge().value())
                .isNaN();
        metrics.recordProcessed(TaskMetrics.Source.TWITTER, TaskMetrics.Outcome.UNEXPECTED_FAILURE);

        assertMeterIdsUseClosedLowCardinalityContract();
        String exportedIds = registry.getMeters().stream()
                .map(meter -> meter.getId().toString())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(exportedIds).doesNotContain(canaries.toArray(String[]::new));
    }

    @Test
    void should_record_unix_seconds_when_schedule_succeeds() {
        metrics.recordScheduleSuccess(TaskMetrics.Operation.CONTENT_FETCH);
        metrics.recordScheduleSuccess(TaskMetrics.Operation.BATCH_PUBLISH);

        double expected = Instant.parse("2026-09-12T02:03:04Z").getEpochSecond();
        assertThat(registry.get("aiaggregator.schedule.last.success")
                .tag("operation", "content_fetch").gauge().value()).isEqualTo(expected);
        assertThat(registry.get("aiaggregator.schedule.last.success")
                .tag("operation", "batch_publish").gauge().value()).isEqualTo(expected);
    }

    @Test
    void should_expose_nan_when_schedule_has_never_succeeded() {
        assertThat(registry.get("aiaggregator.schedule.last.success")
                .tag("operation", "content_fetch").gauge().value()).isNaN();
        assertThat(registry.get("aiaggregator.schedule.last.success")
                .tag("operation", "batch_publish").gauge().value()).isNaN();
    }

    @Test
    void should_count_batch_article_outcomes_when_result_is_recorded() {
        metrics.recordBatchArticles(3, 2);

        assertThat(registry.get("aiaggregator.publish.batch.articles")
                .tag("outcome", "success").counter().count()).isEqualTo(3);
        assertThat(registry.get("aiaggregator.publish.batch.articles")
                .tag("outcome", "failure").counter().count()).isEqualTo(2);
        assertMeterIdsUseClosedLowCardinalityContract();
    }

    // ============ Story 10.5: 重试/死信/补跑指标 ============

    @Test
    void should_count_each_retry_outcome_once_when_recorded() {
        for (TaskMetrics.RetryMetricOutcome outcome : TaskMetrics.RetryMetricOutcome.values()) {
            metrics.recordRetry(outcome);
            assertThat(registry.get("aiaggregator.task.retry")
                    .tag("outcome", outcome.tagValue()).counter().count()).isEqualTo(1);
        }

        assertMeterIdsUseClosedLowCardinalityContract();
    }

    @Test
    void should_accumulate_retry_outcome_by_amount_when_batch_recorded() {
        metrics.recordRetry(TaskMetrics.RetryMetricOutcome.DISPATCHED, 4);

        assertThat(registry.get("aiaggregator.task.retry")
                .tag("outcome", "dispatched").counter().count()).isEqualTo(4);
    }

    @Test
    void should_expose_retry_and_dead_letter_queue_gauges() {
        when(taskQueue.retryCount()).thenReturn(6L);
        when(taskQueue.deadLetterCount()).thenReturn(2L);

        assertThat(registry.get("aiaggregator.task.queue.size").tag("state", "retry").gauge().value())
                .isEqualTo(6);
        assertThat(registry.get("aiaggregator.task.queue.size")
                .tag("state", "dead-letter").gauge().value()).isEqualTo(2);
        assertMeterIdsUseClosedLowCardinalityContract();
    }

    // ============ Story 10.6: 幂等命中跳过指标 ============

    @Test
    void should_count_each_idempotent_kind_once_when_recorded() {
        for (TaskMetrics.IdempotentKind kind : TaskMetrics.IdempotentKind.values()) {
            metrics.recordIdempotent(kind);
            assertThat(registry.get("aiaggregator.task.idempotent")
                    .tag("kind", kind.tagValue()).counter().count()).isEqualTo(1);
        }

        assertMeterIdsUseClosedLowCardinalityContract();
    }

    @Test
    void should_accumulate_idempotent_skips_per_kind() {
        metrics.recordIdempotent(TaskMetrics.IdempotentKind.ARCHIVE);
        metrics.recordIdempotent(TaskMetrics.IdempotentKind.ARCHIVE);
        metrics.recordIdempotent(TaskMetrics.IdempotentKind.DRAFT);

        assertThat(registry.get("aiaggregator.task.idempotent")
                .tag("kind", "archive").counter().count()).isEqualTo(2);
        assertThat(registry.get("aiaggregator.task.idempotent")
                .tag("kind", "draft").counter().count()).isEqualTo(1);
    }

    @Test
    void should_not_leak_identity_values_into_idempotent_meter_ids() {
        // 幂等命中只允许 kind 封闭标签; articleId/taskId/correlationId 只进日志不入 meter
        metrics.recordIdempotent(TaskMetrics.IdempotentKind.ARCHIVE);

        String exportedIds = registry.getMeters().stream()
                .map(meter -> meter.getId().toString())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(exportedIds).doesNotContain("tw-123", "task-", "correlation");
        assertMeterIdsUseClosedLowCardinalityContract();
    }

    private void assertMeterIdsUseClosedLowCardinalityContract() {
        Set<String> allowedKeys = Set.of("state", "source", "outcome", "operation", "kind");
        Set<String> allowedValues = Set.of(
                "pending", "processing", "retry", "dead-letter",
                "twitter", "github", "unknown",
                "success", "failure", "retryable_failure", "non_retryable_failure", "unexpected_failure", "skipped",
                "content_fetch", "batch_publish",
                "scheduled", "dispatched", "exhausted", "dead_lettered", "replayed",
                "archive", "draft");
        registry.getMeters().forEach(meter -> {
            assertThat(meter.getId().getName()).startsWith("aiaggregator.");
            assertThat(meter.getId().getTags())
                    .allMatch(tag -> allowedKeys.contains(tag.getKey()) && allowedValues.contains(tag.getValue()));
        });
    }
}
