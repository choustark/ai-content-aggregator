package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 10.12: MediaMetrics 单元测试 (SimpleMeterRegistry 断言值域).
 *
 * <p>核心断言:
 * <ul>
 *   <li>Prometheus 指标名 {@code aiaggregator.media.phase.result} 存在且仅含封闭枚举标签</li>
 *   <li>仅预登记 18 条可达组合; 不可达组合 (如 download+retry_scheduled) 结构性不暴露</li>
 *   <li>record/recordSucceeded 正确累加; 不可达组合丢弃不抛异常、不虚增计数</li>
 *   <li>低基数纪律: 标签值域全部来自封闭枚举, 无 ID/URL/自由文本</li>
 * </ul>
 */
class MediaMetricsTest {

    private static final String METRIC_NAME = "aiaggregator.media.phase.result";

    private MeterRegistry registry;
    private MediaMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MediaMetrics(registry);
    }

    // ===== 值域: 指标存在 + 标签封闭 =====

    @Test
    void shouldRegisterEighteenReachableCombinations() {
        assertThat(registry.get(METRIC_NAME).meters()).hasSize(18);
    }

    @Test
    void shouldExposeExactlyFourClosedEnumTags() {
        registry.get(METRIC_NAME).meters().forEach(meter -> {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(Tag::getKey)
                    .collect(Collectors.toSet());
            assertThat(tagKeys).containsExactlyInAnyOrder("type", "phase", "outcome", "errorClass");
        });
    }

    @Test
    void shouldRestrictTagValuesToClosedEnumDomains() {
        Set<String> typeValues = Set.of("photo", "video");
        Set<String> phaseValues = Set.of("download", "wechat_prepare", "article_reference");
        Set<String> outcomeValues = Set.of("succeeded", "failed_terminal", "retry_scheduled", "blocked");
        Set<String> errorClassValues = Set.of("none", "environment_blocked", "rate_limited", "retryable", "permanent");

        registry.get(METRIC_NAME).meters().forEach(meter -> {
            meter.getId().getTags().forEach(tag -> {
                switch (tag.getKey()) {
                    case "type" -> assertThat(tag.getValue()).isIn(typeValues);
                    case "phase" -> assertThat(tag.getValue()).isIn(phaseValues);
                    case "outcome" -> assertThat(tag.getValue()).isIn(outcomeValues);
                    case "errorClass" -> assertThat(tag.getValue()).isIn(errorClassValues);
                    default -> throw new AssertionError("意外标签: " + tag.getKey());
                }
            });
        });
    }

    // ===== 不可达组合: 结构性不暴露 + 丢弃不抛 =====

    @Test
    void shouldNotExposeUnreachableCombination_downloadRetryScheduled() {
        // 下载阶段只有 succeeded/failed_terminal, retry_scheduled 结构性缺席
        assertThat(registry.find(METRIC_NAME)
                .tag("phase", "download")
                .tag("outcome", "retry_scheduled")
                .counter()).isNull();
    }

    @Test
    void shouldNotExposeUnreachableCombination_downloadBlocked() {
        assertThat(registry.find(METRIC_NAME)
                .tag("phase", "download")
                .tag("outcome", "blocked")
                .counter()).isNull();
    }

    @Test
    void shouldNotExposeUnreachableCombination_articleReferenceFailures() {
        assertThat(registry.find(METRIC_NAME)
                .tag("phase", "article_reference")
                .tag("outcome", "failed_terminal")
                .counter()).isNull();
    }

    @Test
    void shouldDropUnreachableCombinationWithoutThrowingAndWithoutCounting() {
        double before = counterValue("photo", "download", "succeeded", "none");

        assertThatCode(() -> metrics.record(MediaMetrics.MediaType.PHOTO,
                MediaMetrics.MediaPhase.DOWNLOAD,
                MediaMetrics.MediaOutcome.RETRY_SCHEDULED,
                MediaMetrics.MediaErrorClass.RETRYABLE)).doesNotThrowAnyException();

        // 不虚增任何可达计数, 也不新建 meter
        assertThat(counterValue("photo", "download", "succeeded", "none")).isEqualTo(before);
        assertThat(registry.get(METRIC_NAME).meters()).hasSize(18);
    }

    // ===== record/recordSucceeded 累加语义 =====

    @Test
    void shouldIncrementCounterOnRecord() {
        metrics.record(MediaMetrics.MediaType.VIDEO, MediaMetrics.MediaPhase.DOWNLOAD,
                MediaMetrics.MediaOutcome.FAILED_TERMINAL, MediaMetrics.MediaErrorClass.PERMANENT);
        metrics.record(MediaMetrics.MediaType.VIDEO, MediaMetrics.MediaPhase.DOWNLOAD,
                MediaMetrics.MediaOutcome.FAILED_TERMINAL, MediaMetrics.MediaErrorClass.PERMANENT);

        assertThat(counterValue("video", "download", "failed_terminal", "permanent")).isEqualTo(2.0);
    }

    @Test
    void shouldRecordSucceededWithNoneErrorClass() {
        metrics.recordSucceeded(MediaMetrics.MediaType.PHOTO, MediaMetrics.MediaPhase.WECHAT_PREPARE);

        assertThat(counterValue("photo", "wechat_prepare", "succeeded", "none")).isEqualTo(1.0);
    }

    @Test
    void shouldRecordBlockedForEnvironmentFailure() {
        metrics.record(MediaMetrics.MediaType.PHOTO, MediaMetrics.MediaPhase.WECHAT_PREPARE,
                MediaMetrics.MediaOutcome.BLOCKED, MediaMetrics.MediaErrorClass.ENVIRONMENT_BLOCKED);

        assertThat(counterValue("photo", "wechat_prepare", "blocked", "environment_blocked")).isEqualTo(1.0);
    }

    @Test
    void shouldRecordArticleReferenceSucceeded() {
        metrics.recordSucceeded(MediaMetrics.MediaType.VIDEO, MediaMetrics.MediaPhase.ARTICLE_REFERENCE);

        assertThat(counterValue("video", "article_reference", "succeeded", "none")).isEqualTo(1.0);
    }

    @Test
    void shouldKeepCountersIndependentPerTagCombination() {
        metrics.recordSucceeded(MediaMetrics.MediaType.PHOTO, MediaMetrics.MediaPhase.DOWNLOAD);
        metrics.record(MediaMetrics.MediaType.VIDEO, MediaMetrics.MediaPhase.DOWNLOAD,
                MediaMetrics.MediaOutcome.FAILED_TERMINAL, MediaMetrics.MediaErrorClass.RETRYABLE);

        assertThat(counterValue("photo", "download", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("video", "download", "failed_terminal", "retryable")).isEqualTo(1.0);
        assertThat(counterValue("video", "download", "succeeded", "none")).isZero();
    }

    // ===== 低基数纪律 canary =====

    @Test
    void shouldNeverExposeHighCardinalityValuesInTags() {
        // ID/URL/correlationId 形态的值绝不出现在任何标签中
        // (canary: 扫描整个 registry 的全部 meter — 仅扫本指标的 18 条封闭枚举 meter 是同义反复)
        registry.getMeters().forEach(meter ->
                meter.getId().getTags().forEach(tag ->
                        assertThat(tag.getValue())
                                .as("%s 标签 %s", meter.getId().getName(), tag.getKey())
                                .doesNotContain("tweet", "http", "media-", ":")));
    }

    private double counterValue(String type, String phase, String outcome, String errorClass) {
        return registry.get(METRIC_NAME)
                .tag("type", type)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .tag("errorClass", errorClass)
                .counter().count();
    }
}
