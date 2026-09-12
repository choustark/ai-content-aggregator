package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 Story 5.5 已保存的成本快照只读投影为 Prometheus Gauge。
 *
 * <p>本组件不重算成本、不写 Redis、不执行预算 gate。使用量读取失败时返回最后成功快照，
 * 首次失败返回 NaN，并按 60 秒窗口限频告警日志；80%/100% 阈值直接取自既有
 * {@link CostMonitorProperties}。月份按系统默认时区计算，与 {@link CostMonitor} 保持一致。
 */
@Component
@Slf4j
public class CostMetrics {

    private static final long UNAVAILABLE = Long.MIN_VALUE;
    private static final long LOG_INTERVAL_MILLIS = 60_000L;

    private final CostMonitor costMonitor;
    private final Clock clock;
    private final AtomicLong usagePercentSnapshot = new AtomicLong(UNAVAILABLE);
    private final AtomicLong lastCollectionLogMillis = new AtomicLong(UNAVAILABLE);

    @Autowired
    public CostMetrics(CostMonitor costMonitor, CostMonitorProperties properties, MeterRegistry registry) {
        this(costMonitor, properties, registry, Clock.systemDefaultZone());
    }

    CostMetrics(CostMonitor costMonitor, CostMonitorProperties properties, MeterRegistry registry, Clock clock) {
        this.costMonitor = costMonitor;
        this.clock = clock;
        Gauge.builder("aiaggregator.cost.monthly.usage.percent", this, ignored -> currentUsagePercent())
                .description("Current monthly cost budget usage percent from the persisted CostMonitor snapshot")
                .register(registry);
        Gauge.builder("aiaggregator.cost.monthly.threshold.percent", properties,
                        CostMonitorProperties::getWarningThresholdPercent)
                .description("Configured monthly cost threshold percent")
                .tag("level", "warning")
                .register(registry);
        Gauge.builder("aiaggregator.cost.monthly.threshold.percent", properties,
                        CostMonitorProperties::getStopThresholdPercent)
                .description("Configured monthly cost threshold percent")
                .tag("level", "stop")
                .register(registry);
    }

    private double currentUsagePercent() {
        try {
            CostSnapshot snapshot = costMonitor.readMonthSnapshot(YearMonth.now(clock));
            if (snapshot == null) {
                return snapshotOrNan();
            }
            usagePercentSnapshot.set(Math.max(snapshot.usagePercent(), 0));
            return usagePercentSnapshot.get();
        } catch (RuntimeException exception) {
            logCollectionFailure(exception);
            return snapshotOrNan();
        }
    }

    private void logCollectionFailure(RuntimeException exception) {
        long now = clock.millis();
        long previous = lastCollectionLogMillis.get();
        if ((previous == UNAVAILABLE || now - previous >= LOG_INTERVAL_MILLIS)
                && lastCollectionLogMillis.compareAndSet(previous, now)) {
            log.warn("读取成本快照指标失败，返回最后成功快照: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private double snapshotOrNan() {
        long snapshot = usagePercentSnapshot.get();
        return snapshot == UNAVAILABLE ? Double.NaN : snapshot;
    }
}
