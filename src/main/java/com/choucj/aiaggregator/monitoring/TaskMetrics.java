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
 *       {@code state=pending|processing|retry|dead-letter}（Story 10.5 扩展 retry/dead-letter 两态）。</li>
 *   <li>{@code aiaggregator.task.processed}（Prometheus:
 *       {@code aiaggregator_task_processed_total}），Counter/任务数，标签
 *       {@code source=twitter|github|unknown} 与
 *       {@code outcome=success|retryable_failure|non_retryable_failure|unexpected_failure|skipped}。
 *       唯一计数点是 {@code ContentScheduler} 的队列执行边界；该指标表示队列调用结果，
 *       不等价于 processor 内每篇内容的业务成功率。</li>
 *   <li>{@code aiaggregator.task.queue.collection.last.success}（Prometheus:
 *       {@code aiaggregator_task_queue_collection_last_success_seconds}），Gauge/Unix 秒，唯一标签
 *       {@code state=pending|processing|retry|dead-letter}（10.5 起新增 retry/dead-letter 两个
 *       延迟重试与死信队列的 last-good 快照）；成功采集对应队列值时刷新，失败时保留最后成功时间，
 *       从而识别已冻结的 last-good 队列快照。</li>
 *   <li>{@code aiaggregator.task.retry}（Prometheus:
 *       {@code aiaggregator_task_retry_total}），Counter/任务数，唯一标签
 *       {@code outcome=scheduled|dispatched|exhausted|dead_lettered|replayed}（Story 10.5）。
 *       记录点：{@code ContentScheduler} 可重试失败推进边界、
 *       {@code RetryDispatchScheduler} 到期重投边界、{@code TaskReplayController} 补跑边界。</li>
 *   <li>{@code aiaggregator.task.idempotent}（Prometheus:
 *       {@code aiaggregator_task_idempotent_total}），Counter/跳过次数，唯一标签
 *       {@code kind=archive|draft}（Story 10.6）。记录点：归档侧快照幂等命中跳过
 *       （{@code MarkdownArchiver}）与草稿侧 {@code DRAFT_CREATED} 幂等命中跳过
 *       （{@code ArticlePublicationWorkflow}）。taskId/articleId/correlationId 只进日志，
 *       不进标签（低基数纪律）。</li>
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
    private final AtomicLong retrySnapshot = new AtomicLong(UNAVAILABLE);
    private final AtomicLong deadLetterSnapshot = new AtomicLong(UNAVAILABLE);
    private final AtomicReference<Double> pendingLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> processingLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> retryLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> deadLetterLastCollectionEpochSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicLong lastCollectionLogMillis = new AtomicLong(UNAVAILABLE);
    private final Map<Source, Map<Outcome, Counter>> processedCounters = new EnumMap<>(Source.class);
    private final Map<Operation, AtomicReference<Double>> lastSuccessEpochSeconds = new EnumMap<>(Operation.class);
    private final Map<BatchArticleOutcome, Counter> batchArticleCounters = new EnumMap<>(BatchArticleOutcome.class);
    private final Map<RetryMetricOutcome, Counter> retryCounters = new EnumMap<>(RetryMetricOutcome.class);
    private final Map<IdempotentKind, Counter> idempotentCounters = new EnumMap<>(IdempotentKind.class);

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
        registerRetryCounters(registry);
        registerIdempotentCounters(registry);
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

    /**
     * 记录一次延迟重试/死信/补跑状态机推进(Story 10.5)。
     *
     * <p>调用方仅允许传入 {@link RetryMetricOutcome} 封闭枚举——taskId 与异常文本不得入库。
     */
    public void recordRetry(RetryMetricOutcome outcome) {
        recordRetry(outcome, 1L);
    }

    /** 同 {@link #recordRetry(RetryMetricOutcome)}, 供批量推进(如到期重投一批)按数量累计。 */
    public void recordRetry(RetryMetricOutcome outcome, long amount) {
        retryCounters.get(outcome).increment(Math.max(amount, 0L));
    }

    /**
     * 记录一次幂等命中跳过(Story 10.6) — 归档或草稿重复投递命中持久化业务标识
     * (归档快照 / {@code DRAFT_CREATED} + mediaId)而未产生副作用。
     *
     * <p>调用方仅允许传入 {@link IdempotentKind} 封闭枚举——articleId/taskId 只进审计日志,
     * 不得进入标签(低基数纪律); 调用方须自行 try/catch 隔离(观测是旁路, 不得影响发布结果)。
     */
    public void recordIdempotent(IdempotentKind kind) {
        idempotentCounters.get(kind).increment();
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
        // Story 10.5: retry ZSET / dead-letter 集合纳入统一队列观测
        Gauge.builder("aiaggregator.task.queue.size", this,
                        ignored -> safeQueueValue(taskQueue::retryCount, retrySnapshot,
                                retryLastCollectionEpochSeconds))
                .description("Current task queue size by state")
                .tag("state", "retry")
                .register(registry);
        Gauge.builder("aiaggregator.task.queue.size", this,
                        ignored -> safeQueueValue(taskQueue::deadLetterCount, deadLetterSnapshot,
                                deadLetterLastCollectionEpochSeconds))
                .description("Current task queue size by state")
                .tag("state", "dead-letter")
                .register(registry);
        registerQueueCollectionGauge(registry, "pending", pendingLastCollectionEpochSeconds);
        registerQueueCollectionGauge(registry, "processing", processingLastCollectionEpochSeconds);
        registerQueueCollectionGauge(registry, "retry", retryLastCollectionEpochSeconds);
        registerQueueCollectionGauge(registry, "dead-letter", deadLetterLastCollectionEpochSeconds);
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

    private void registerRetryCounters(MeterRegistry registry) {
        for (RetryMetricOutcome outcome : RetryMetricOutcome.values()) {
            retryCounters.put(outcome, Counter.builder("aiaggregator.task.retry")
                    .description("Delayed-retry/dead-letter/replay state-machine transitions")
                    .tag("outcome", outcome.tagValue())
                    .register(registry));
        }
    }

    private void registerIdempotentCounters(MeterRegistry registry) {
        for (IdempotentKind kind : IdempotentKind.values()) {
            idempotentCounters.put(kind, Counter.builder("aiaggregator.task.idempotent")
                    .description("Idempotent skips backed by persisted business identity (Story 10.6)")
                    .tag("kind", kind.tagValue())
                    .register(registry));
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

    /**
     * 延迟重试状态机推进分类(Story 10.5) — 封闭枚举, 防止 taskId/异常文本等高基数标签入库.
     *
     * <ul>
     *   <li>{@code scheduled} — 可重试失败已按退避写入 retry ZSET</li>
     *   <li>{@code dispatched} — 到期重试已原子转回 pending 队列</li>
     *   <li>{@code exhausted} — 重试尝试耗尽, 同脚本落死信</li>
     *   <li>{@code dead_lettered} — 不可重试失败或异常态兜底移入死信</li>
     *   <li>{@code replayed} — 死信任务经人工补跑创建新 pending 任务</li>
     * </ul>
     */
    public enum RetryMetricOutcome {
        SCHEDULED("scheduled"),
        DISPATCHED("dispatched"),
        EXHAUSTED("exhausted"),
        DEAD_LETTERED("dead_lettered"),
        REPLAYED("replayed");

        private final String tagValue;

        RetryMetricOutcome(String tagValue) {
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

    /**
     * 幂等命中跳过分类(Story 10.6) — 封闭枚举, 防止 articleId/taskId 等高基数标签入库。
     *
     * <ul>
     *   <li>{@code archive} — 归档重复投递命中归档快照(跨日/同日)而未追加 Markdown</li>
     *   <li>{@code draft} — 草稿重复投递命中 {@code DRAFT_CREATED + wechatDraftMediaId}
     *       而未调用微信 {@code addDraft}</li>
     * </ul>
     */
    public enum IdempotentKind {
        ARCHIVE("archive"),
        DRAFT("draft");

        private final String tagValue;

        IdempotentKind(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
