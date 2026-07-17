package com.choucj.aiaggregator.monitoring;

import java.util.Map;

/**
 * Story 5.5: 成本监控月度快照，用于 Redis 汇总和 Actuator 只读展示.
 *
 * @param month 月份，格式 {@code yyyy-MM}
 * @param totalTokens 当月总 token
 * @param ragExtraTokens RAG prompt 增量 token
 * @param estimatedCostCents 估算成本，单位 cents
 * @param budgetCents 月度预算，单位 cents
 * @param usagePercent 预算使用百分比
 * @param thresholdStatus 阈值状态
 * @param halted 是否已停止自动处理
 * @param perModel 按模型汇总
 */
public record CostSnapshot(String month,
                           long totalTokens,
                           long ragExtraTokens,
                           long estimatedCostCents,
                           long budgetCents,
                           int usagePercent,
                           String thresholdStatus,
                           boolean halted,
                           Map<String, ModelCost> perModel) {

    public CostSnapshot {
        perModel = Map.copyOf(perModel);
    }

    /**
     * 单模型 token 与成本汇总.
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @param estimatedCostCents 估算成本，单位 cents
     */
    public record ModelCost(long inputTokens, long outputTokens, long estimatedCostCents) {
    }
}
