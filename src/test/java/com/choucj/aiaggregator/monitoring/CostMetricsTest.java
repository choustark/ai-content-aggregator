package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CostMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void should_project_existing_snapshot_when_metrics_are_scraped() {
        CostMonitor monitor = mock(CostMonitor.class);
        CostMonitorProperties properties = new CostMonitorProperties();
        properties.setWarningThresholdPercent(80);
        properties.setStopThresholdPercent(100);
        when(monitor.readMonthSnapshot(YearMonth.of(2026, 9))).thenReturn(
                new CostSnapshot("2026-09", 1, 0, 80, 100, 80, "WARNING", false, Map.of()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CostMetrics(monitor, properties, registry, CLOCK);

        assertThat(registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value()).isEqualTo(80);
        assertThat(registry.get("aiaggregator.cost.monthly.threshold.percent")
                .tag("level", "warning").gauge().value()).isEqualTo(80);
        assertThat(registry.get("aiaggregator.cost.monthly.threshold.percent")
                .tag("level", "stop").gauge().value()).isEqualTo(100);
    }

    @Test
    void should_use_nan_then_last_snapshot_when_read_fails() {
        CostMonitor monitor = mock(CostMonitor.class);
        CostMonitorProperties properties = new CostMonitorProperties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(monitor.readMonthSnapshot(YearMonth.of(2026, 9)))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(new CostSnapshot("2026-09", 0, 0, 10, 100, 10, "OK", false, Map.of()))
                .thenThrow(new IllegalStateException("redis down"));

        new CostMetrics(monitor, properties, registry, CLOCK);

        assertThat(registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value()).isNaN();
        assertThat(registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value()).isEqualTo(10);
        assertThat(registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value()).isEqualTo(10);
    }

    @Test
    void should_use_clock_zone_when_month_boundary_differs_from_utc() {
        Clock shanghaiClock = Clock.fixed(
                Instant.parse("2026-08-31T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        CostMonitor monitor = mock(CostMonitor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CostMetrics(monitor, new CostMonitorProperties(), registry, shanghaiClock);
        registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value();

        org.mockito.Mockito.verify(monitor).readMonthSnapshot(YearMonth.of(2026, 9));
    }

    @Test
    void should_rate_limit_warning_when_snapshot_read_repeatedly_fails(CapturedOutput output) {
        CostMonitor monitor = mock(CostMonitor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(monitor.readMonthSnapshot(YearMonth.of(2026, 9)))
                .thenThrow(new IllegalStateException("redis down"));

        new CostMetrics(monitor, new CostMonitorProperties(), registry, CLOCK);
        registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value();
        registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value();
        registry.get("aiaggregator.cost.monthly.usage.percent").gauge().value();

        assertThat(output.getOut()).containsOnlyOnce("读取成本快照指标失败");
    }
}
