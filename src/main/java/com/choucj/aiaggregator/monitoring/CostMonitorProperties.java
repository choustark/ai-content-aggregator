package com.choucj.aiaggregator.monitoring;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Story 5.5: 成本监控运行配置，控制月度预算、阈值和每日汇总调度.
 *
 * <p>预算为 0 时只观测不阻断；阈值仍会被校验，避免未来开启预算时带着非法配置启动。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cost.monitor")
public class CostMonitorProperties {

    /** 月度预算，单位 cents；0 表示只观测不阻断。 */
    @Min(value = 0, message = "cost.monitor.monthly-budget-cents must be >= 0")
    private long monthlyBudgetCents = 0L;

    /** warn 告警阈值百分比。 */
    @Min(value = 1, message = "cost.monitor.warning-threshold-percent must be >= 1")
    @Max(value = 100, message = "cost.monitor.warning-threshold-percent must be <= 100")
    private int warningThresholdPercent = 80;

    /** 自动处理停止阈值百分比。 */
    @Min(value = 1, message = "cost.monitor.stop-threshold-percent must be >= 1")
    @Max(value = 100, message = "cost.monitor.stop-threshold-percent must be <= 100")
    private int stopThresholdPercent = 100;

    /** 每日成本汇总 cron；默认每天 00:05 执行。 */
    private String dailyCron = "0 5 0 * * ?";

    /** 月度汇总与停机标记保留天数。 */
    @Min(value = 1, message = "cost.monitor.summary-ttl-days must be >= 1")
    private long summaryTtlDays = 400L;

    /**
     * 校验 warn 阈值不能超过 stop 阈值.
     *
     * @return true 表示阈值顺序合法
     */
    @AssertTrue(message = "cost.monitor.warning-threshold-percent must be <= stop-threshold-percent")
    public boolean isThresholdOrderValid() {
        return warningThresholdPercent <= stopThresholdPercent;
    }
}
