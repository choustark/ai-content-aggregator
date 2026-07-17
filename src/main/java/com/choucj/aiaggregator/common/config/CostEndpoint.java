package com.choucj.aiaggregator.common.config;

import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.monitoring.CostSnapshot;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Map;

/**
 * Actuator costs 端点，暴露当前月成本快照和阈值状态用于运维核对.
 *
 * <p>端点仅读取已保存快照，不触发 LLM 调用、内容重写或成本重算写入。
 */
@Component
@Endpoint(id = "costs")
public class CostEndpoint {

    private final CostMonitor costMonitor;

    /**
     * 构造成本端点.
     *
     * @param costMonitor 成本监控器
     */
    public CostEndpoint(CostMonitor costMonitor) {
        this.costMonitor = costMonitor;
    }

    /**
     * 读取当前月成本快照.
     *
     * @return endpoint 响应
     */
    @ReadOperation
    public Map<String, Object> costs() {
        YearMonth month = YearMonth.now();
        try {
            CostSnapshot snapshot = costMonitor.readMonthSnapshot(month);
            return Map.of(
                    "degraded", false,
                    "month", month.toString(),
                    "snapshot", snapshot == null ? Map.of() : snapshot);
        } catch (RuntimeException e) {
            return Map.of(
                    "degraded", true,
                    "month", month.toString(),
                    "errorType", e.getClass().getSimpleName());
        }
    }
}
