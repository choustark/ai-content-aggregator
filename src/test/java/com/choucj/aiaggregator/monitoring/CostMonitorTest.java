package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.common.observability.LogEventCapture;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.5 {@link CostMonitor} 月度汇总与预算阈值测试.
 */
@ExtendWith(MockitoExtension.class)
class CostMonitorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void should_correlate_warning_and_clear_context_when_daily_summary_reads_invalid_value() {
        List<String> ids = new ArrayList<>();
        when(redisRepository.get(anyString())).thenAnswer(invocation -> {
            ids.add(CorrelationContext.require());
            return "invalid-number";
        });
        try (var logs = new LogEventCapture(CostMonitor.class)) {
            costMonitor.summarizeCurrentMonth();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            assertThat(ids).isNotEmpty();
            String firstId = ids.getFirst();
            assertThat(ids).allMatch(firstId::equals);
            assertThat(logs.events()).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", firstId));
            ids.clear();
            costMonitor.summarizeCurrentMonth();
            assertThat(ids.getFirst()).isNotEqualTo(firstId);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }

    @Test
    void should_clear_context_when_daily_summary_throws() {
        CostMonitor monitor = spy(costMonitor);
        doThrow(new IllegalStateException("failed")).when(monitor).summarizeMonth(any(YearMonth.class));
        assertThatThrownBy(monitor::summarizeCurrentMonth).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 7, 1);

    @Mock
    private RedisRepository redisRepository;

    private CostMonitorProperties monitorProperties;
    private CostPricingProperties pricingProperties;
    private CostMonitor costMonitor;

    @BeforeEach
    void setUp() {
        monitorProperties = new CostMonitorProperties();
        monitorProperties.setMonthlyBudgetCents(100);
        pricingProperties = new CostPricingProperties();
        CostPricingProperties.ModelPricing deepseek = new CostPricingProperties.ModelPricing();
        deepseek.setInputCentsPerMillion(10);
        deepseek.setOutputCentsPerMillion(20);
        pricingProperties.getPricing().put("deepseek", deepseek);
        costMonitor = new CostMonitor(redisRepository, monitorProperties, pricingProperties);
    }

    @Test
    void shouldSummarizeEmptyMonthAsOk() {
        CostSnapshot snapshot = costMonitor.summarizeMonth(MONTH);

        assertThat(snapshot.totalTokens()).isZero();
        assertThat(snapshot.estimatedCostCents()).isZero();
        assertThat(snapshot.thresholdStatus()).isEqualTo("OK");
        verify(redisRepository).setObject(eq(RedisKeys.costMonthly(MONTH)), eq(snapshot), any(Duration.class));
    }

    @Test
    void shouldSummarizeDailyAndPerModelMetricsIdempotently() {
        when(redisRepository.get(RedisKeys.costDaily(FIRST_DAY))).thenReturn("30");
        when(redisRepository.get(RedisKeys.costDailyRagExtra(FIRST_DAY))).thenReturn("5");
        when(redisRepository.get(RedisKeys.costDailyModelInput(FIRST_DAY, "deepseek"))).thenReturn("10");
        when(redisRepository.get(RedisKeys.costDailyModelOutput(FIRST_DAY, "deepseek"))).thenReturn("20");
        when(redisRepository.get(RedisKeys.costDailyModelEstimatedMicroCents(FIRST_DAY, "deepseek")))
                .thenReturn("200000000");

        CostSnapshot snapshot = costMonitor.summarizeMonth(MONTH);

        assertThat(snapshot.totalTokens()).isEqualTo(30);
        assertThat(snapshot.ragExtraTokens()).isEqualTo(5);
        assertThat(snapshot.estimatedCostCents()).isEqualTo(200);
        assertThat(snapshot.perModel()).containsKey("deepseek");
        assertThat(snapshot.perModel().get("deepseek").inputTokens()).isEqualTo(10);
        assertThat(snapshot.perModel().get("deepseek").outputTokens()).isEqualTo(20);
        assertThat(snapshot.perModel().get("deepseek").estimatedCostCents()).isEqualTo(200);
        verify(redisRepository).setObject(eq(RedisKeys.costMonthly(MONTH)), eq(snapshot), any(Duration.class));
    }

    @Test
    void shouldWarnWhenMonthlyCostReachesWarningThreshold() {
        when(redisRepository.get(RedisKeys.costDailyModelEstimatedMicroCents(FIRST_DAY, "deepseek")))
                .thenReturn("80000000");

        CostSnapshot snapshot = costMonitor.summarizeMonth(MONTH);

        assertThat(snapshot.estimatedCostCents()).isEqualTo(80);
        assertThat(snapshot.usagePercent()).isEqualTo(80);
        assertThat(snapshot.thresholdStatus()).isEqualTo("WARNING");
        verify(redisRepository, never()).set(eq(RedisKeys.costBudgetHalted(MONTH)), any(), any(Duration.class));
    }

    @Test
    void shouldSetHaltedMarkerWhenMonthlyCostReachesStopThreshold() {
        when(redisRepository.get(RedisKeys.costDailyModelEstimatedMicroCents(FIRST_DAY, "deepseek")))
                .thenReturn("100000000");

        CostSnapshot snapshot = costMonitor.summarizeMonth(MONTH);

        assertThat(snapshot.thresholdStatus()).isEqualTo("HALTED");
        assertThat(snapshot.halted()).isTrue();
        verify(redisRepository).set(eq(RedisKeys.costBudgetHalted(MONTH)), eq("true"), any(Duration.class));
    }

    @Test
    void shouldObserveOnlyWhenBudgetIsZero() {
        monitorProperties.setMonthlyBudgetCents(0);
        when(redisRepository.get(RedisKeys.costDailyModelEstimatedMicroCents(FIRST_DAY, "deepseek")))
                .thenReturn("100000000");

        CostSnapshot snapshot = costMonitor.summarizeMonth(MONTH);

        assertThat(snapshot.thresholdStatus()).isEqualTo("OBSERVE_ONLY");
        assertThat(snapshot.halted()).isFalse();
        verify(redisRepository, never()).set(eq(RedisKeys.costBudgetHalted(MONTH)), any(), any(Duration.class));
    }

    @Test
    void shouldIgnoreDirtyRedisValueAndStillWriteSummary() {
        when(redisRepository.get(RedisKeys.costDaily(FIRST_DAY))).thenReturn("not-a-number");

        assertThatCode(() -> costMonitor.summarizeMonth(MONTH))
                .doesNotThrowAnyException();

        ArgumentCaptor<CostSnapshot> captor = ArgumentCaptor.forClass(CostSnapshot.class);
        verify(redisRepository).setObject(eq(RedisKeys.costMonthly(MONTH)), captor.capture(), any(Duration.class));
        assertThat(captor.getValue().thresholdStatus()).isEqualTo("OK");
    }

    @Test
    void shouldRespectManualResumeKeyWhenCheckingHaltGate() {
        when(redisRepository.exists(RedisKeys.costBudgetResume(MONTH))).thenReturn(true);

        assertThat(costMonitor.isProcessingHalted(MONTH)).isFalse();
    }

    @Test
    void shouldRefreshMonthlySummaryBeforeCheckingHaltGate() {
        when(redisRepository.get(RedisKeys.costDailyModelEstimatedMicroCents(FIRST_DAY, "deepseek")))
                .thenReturn("100000000");

        assertThat(costMonitor.refreshAndCheckProcessingHalted(MONTH)).isTrue();

        verify(redisRepository).setObject(eq(RedisKeys.costMonthly(MONTH)), any(CostSnapshot.class), any(Duration.class));
        verify(redisRepository).set(eq(RedisKeys.costBudgetHalted(MONTH)), eq("true"), any(Duration.class));
    }

    @Test
    void shouldFailFastWhenBudgetGateEnabledWithoutAnyPricing() {
        monitorProperties.setMonthlyBudgetCents(100);
        CostPricingProperties emptyPricing = new CostPricingProperties();

        assertThatThrownBy(() -> new CostMonitor(redisRepository, monitorProperties, emptyPricing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost.pricing");
    }
}
