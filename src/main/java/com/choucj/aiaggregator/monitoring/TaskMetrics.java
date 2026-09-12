package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.task.queue.TaskQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 集中维护任务编排层的低基数 Micrometer 契约。
 *
 * <p>Meter 契约如下：
 * <ul>
 *   <li>{@code aiaggregator.task.queue.size}（Prometheus:
 *       {@code aiaggregator_task_queue_size}），Gauge/任务数，唯一标签
 *       {@code state=pending|processing}。</li>
 *   <li>{@code aiaggregator.task.processed}（Prometheus:
 *       {@code aiaggregator_task_processed_total}），Counter/任务数，标签
 *       {@code source=twitter|github|unknown} 与
 *       {@code outcome=success|retryable_failure|non_retryable_failure|unexpected_failure|skipped}。
 *       唯一计数点是 {@code ContentScheduler} 的队列执行边界；该指标表示队列调用结果，
 *       不等价于 processor 内每篇内容的业务成功率。</li>
 *   <li>{@code aiaggregator.task.queue.collection.last.success}（Prometheus:
 *       {@code aiaggregator_task_queue_collection_last_success_seconds}），Gauge/Unix 秒，唯一标签
 *       {@code state=pending|processing}；成功采集对应队列值时刷新，失败时保留最后成功时间，
 *       从而识别已冻结的 last-good 队列快照。</li>
 *   <li>{@code aiaggregator.schedule.last.success}（Prometheus:
 *       {@code aiaggregator_schedule_last_success_seconds}），Gauge/Unix 秒，唯一标签
 *       {@code operation=content_fetch|batch_publish}；进程启动后尚未成功执行时为 NaN，
 *       不使用 epoch 0 伪造历史时间。</li>
 *   <li>{@code aiaggregator.publish.batch.articles}（Prometheus:
 *       {@code aiaggregator_publish_batch_articles_total}），Counter/文章数，唯一标签
 *       {@code outcome=success|failure}；与 batch_publish 窗口执行状态分离。</li>
 * </ul>
 *
 * <p>API 只接受封闭枚举，不接受 taskId、URL、correlationId 或异常文本，避免高基数和敏感信息泄漏。
 * 队列 Gauge 经 {@link TaskQueue} 高层只读查询采集；采集失败返回最后成功快照，首次失败返回 NaN，
 * 不改变队列操作结果，也不会令 Prometheus scrape 失败。
 */
@Component
@Slf4j
public class TaskMetrics {

    private static final long UNAVAILABLE = Long.MIN_VALUE;
    private static final long LOG_INTERVAL_MILLIS = 60_000L;

    private final TaskQueue taskQueue;
    private final Clock clock;
    private final AtomicLong pendingSnapshot = new AtomicLong(UNAVAILABLE);
    private final AtomicLong processingSnapshot = new AtomicLong(UNAVAILABLE);
    private final AtomicReference<Double> pendingLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> processingLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicLong lastCollectionLogMillis = new AtomicLong(UNAVAILABLE);
    private final Map<Source, Map<Outcome, Counter>> processedCounters = new EnumMap<>(Source.class);
    private final Map<Operation, AtomicReference<Double>> lastSuccessEpochSeconds = new EnumMap<>(Operation.class);
    private final Map<BatchArticleOutcome, Counter> batchArticleCounters = new EnumMap<>(BatchArticleOutcome.class);

    @Autowired
    public TaskMetrics(TaskQueue taskQueue, MeterRegistry registry) {
        this(taskQueue, registry, Clock.systemUTC());
    }

    TaskMetrics(TaskQueue taskQueue, MeterRegistry registry, Clock clock) {
        this.taskQueue = taskQueue;
        this.clock = clock;
        registerQueueGauges(registry);
        registerProcessedCounters(registry);
        registerScheduleGauges(registry);
        registerBatchArticleCounters(registry);
    }

    /** 记录一个队列任务在调度器边界的终态；调用方不得在 processor 或 queue 内重复记录。 */
    public void recordProcessed(Source source, Outcome outcome) {
        processedCounters.get(source).get(outcome).increment();
    }

    /** 仅在计划操作完整返回后更新最近成功的 Unix 秒时间。 */
    public void recordScheduleSuccess(Operation operation) {
        lastSuccessEpochSeconds.get(operation).set((double) clock.instant().getEpochSecond());
    }

    /** 记录批量发布结果中的文章级成功/失败数；窗口是否执行由 schedule 指标独立表达。 */
    public void recordBatchArticles(long success, long failure) {
        batchArticleCounters.get(BatchArticleOutcome.SUCCESS).increment(Math.max(success, 0L));
        batchArticleCounters.get(BatchArticleOutcome.FAILURE).increment(Math.max(failure, 0L));
    }

    private void registerQueueGauges(MeterRegistry registry) {
        Gauge.builder("aiaggregator.task.queue.size", this,
                        ignored -> safeQueueValue(taskQueue::pendingCount, pendingSnapshot,
                                pendingLastCollectionEpochSeconds))
                .description("Current task queue size by state")
                .tag("state", "pending")
                .register(registry);
        Gauge.builder("aiaggregator.task.queue.size", this,
                        ignored -> safeQueueValue(taskQueue::processingCount, processingSnapshot,
                                processingLastCollectionEpochSeconds))
                .description("Current task queue size by state")
                .tag("state", "processing")
                .register(registry);
        registerQueueCollectionGauge(registry, "pending", pendingLastCollectionEpochSeconds);
        registerQueueCollectionGauge(registry, "processing", processingLastCollectionEpochSeconds);
    }

    private void registerQueueCollectionGauge(MeterRegistry registry, String state,
                                               AtomicReference<Double> epochSeconds) {
        Gauge.builder("aiaggregator.task.queue.collection.last.success", epochSeconds, AtomicReference::get)
                .description("Unix timestamp of the last successful task queue collection")
                .baseUnit("seconds")
                .tag("state", state)
                .register(registry);
    }

    private void registerProcessedCounters(MeterRegistry registry) {
        for (Source source : Source.values()) {
            Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, Counter.builder("aiaggregator.task.processed")
                        .description("Queue execution outcomes; not per-content business success")
                        .tags("source", source.tagValue(), "outcome", outcome.tagValue())
                        .register(registry));
            }
            processedCounters.put(source, outcomes);
        }
    }

    private void registerScheduleGauges(MeterRegistry registry) {
        for (Operation operation : Operation.values()) {
            AtomicReference<Double> epochSeconds = new AtomicReference<>(Double.NaN);
            lastSuccessEpochSeconds.put(operation, epochSeconds);
            Gauge.builder("aiaggregator.schedule.last.success", epochSeconds, AtomicReference::get)
                    .description("Unix timestamp of the last successful scheduled operation")
                    .baseUnit("seconds")
                    .tag("operation", operation.tagValue())
                    .register(registry);
        }
    }

    private void registerBatchArticleCounters(MeterRegistry registry) {
        for (BatchArticleOutcome outcome : BatchArticleOutcome.values()) {
            batchArticleCounters.put(outcome, Counter.builder("aiaggregator.publish.batch.articles")
                    .description("Batch-published article outcomes")
                    .tag("outcome", outcome.tagValue())
                    .register(registry));
        }
    }

    private double safeQueueValue(LongSupplier collector, AtomicLong snapshot,
                                  AtomicReference<Double> lastCollectionEpochSeconds) {
        try {
            long current = Math.max(collector.getAsLong(), 0L);
            snapshot.set(current);
            lastCollectionEpochSeconds.set((double) clock.instant().getEpochSecond());
            return current;
        } catch (RuntimeException exception) {
            logCollectionFailure(exception);
            long previous = snapshot.get();
            return previous == UNAVAILABLE ? Double.NaN : previous;
        }
    }

    private void logCollectionFailure(RuntimeException exception) {
        long now = clock.millis();
        long previous = lastCollectionLogMillis.get();
        if ((previous == UNAVAILABLE || now - previous >= LOG_INTERVAL_MILLIS)
                && lastCollectionLogMillis.compareAndSet(previous, now)) {
            log.warn("采集任务队列 Gauge 失败，返回最后成功快照: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    public enum Source {
        TWITTER("twitter"), GITHUB("github"), UNKNOWN("unknown");

        private final String tagValue;

        Source(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public enum Outcome {
        SUCCESS("success"),
        RETRYABLE_FAILURE("retryable_failure"),
        NON_RETRYABLE_FAILURE("non_retryable_failure"),
        UNEXPECTED_FAILURE("unexpected_failure"),
        SKIPPED("skipped");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public enum Operation {
        CONTENT_FETCH("content_fetch"), BATCH_PUBLISH("batch_publish");

        private final String tagValue;

        Operation(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    private enum BatchArticleOutcome {
        SUCCESS("success"), FAILURE("failure");

        private final String tagValue;

        BatchArticleOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        String tagValue() {
            return tagValue;
        }
    }
}
