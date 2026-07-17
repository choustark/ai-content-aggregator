package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Story 5.5: 成本监控器，汇总每日 token/cost 并执行月度预算阈值判断.
 *
 * <p>本组件只做观测和自动处理 gate 的判定，不关闭 Spring Context，也不删除任务队列。
 * Redis 异常按观测侧软失败处理，避免成本监控影响内容主流程。
 */
@Slf4j
@Component
public class CostMonitor {

    private static final long MICRO_CENTS_PER_CENT = 1_000_000L;

    private final RedisRepository redisRepository;
    private final CostMonitorProperties monitorProperties;
    private final CostPricingProperties pricingProperties;

    public CostMonitor(RedisRepository redisRepository,
                       CostMonitorProperties monitorProperties,
                       CostPricingProperties pricingProperties) {
        this.redisRepository = redisRepository;
        this.monitorProperties = monitorProperties;
        this.pricingProperties = pricingProperties;
        if (monitorProperties.getMonthlyBudgetCents() > 0 && pricingProperties.getModels().isEmpty()) {
            throw new IllegalArgumentException("cost.pricing must contain at least one model when cost monitor budget gate is enabled");
        }
    }

    /**
     * 每日汇总当前月份成本.
     */
    @Scheduled(cron = "${cost.monitor.daily-cron:0 5 0 * * ?}")
    public void summarizeCurrentMonth() {
        summarizeMonth(YearMonth.now());
    }

    /**
     * 汇总指定月份成本，重复执行会重算快照并覆盖同一个月度键，避免重复累加.
     *
     * @param month 月份
     * @return 月度成本快照
     */
    public CostSnapshot summarizeMonth(YearMonth month) {
        long totalTokens = 0L;
        long ragExtraTokens = 0L;
        long estimatedMicroCents = 0L;
        Map<String, MutableModelCost> mutablePerModel = new LinkedHashMap<>();
        for (String model : pricingProperties.getModels().keySet()) {
            mutablePerModel.put(model, new MutableModelCost());
        }

        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            totalTokens += readLong(RedisKeys.costDaily(date));
            ragExtraTokens += readLong(RedisKeys.costDailyRagExtra(date));
            for (String model : pricingProperties.getModels().keySet()) {
                MutableModelCost modelCost = mutablePerModel.get(model);
                modelCost.inputTokens += readLong(RedisKeys.costDailyModelInput(date, model));
                modelCost.outputTokens += readLong(RedisKeys.costDailyModelOutput(date, model));
                long modelMicroCents = readLong(RedisKeys.costDailyModelEstimatedMicroCents(date, model));
                modelCost.estimatedMicroCents += modelMicroCents;
                estimatedMicroCents += modelMicroCents;
            }
        }

        long estimatedCostCents = toCentsCeil(estimatedMicroCents);
        int usagePercent = usagePercent(estimatedCostCents);
        String thresholdStatus = thresholdStatus(usagePercent);
        boolean halted = "HALTED".equals(thresholdStatus);
        Map<String, CostSnapshot.ModelCost> perModel = immutablePerModel(mutablePerModel);
        CostSnapshot snapshot = new CostSnapshot(month.toString(), totalTokens, ragExtraTokens,
                estimatedCostCents, monitorProperties.getMonthlyBudgetCents(), usagePercent,
                thresholdStatus, halted, perModel);

        saveSnapshot(month, snapshot);
        if ("WARNING".equals(thresholdStatus)) {
            log.warn("月度成本达到告警阈值: month={}, usagePercent={}, estimatedCostCents={}, budgetCents={}",
                    month, usagePercent, estimatedCostCents, monitorProperties.getMonthlyBudgetCents());
        } else if (halted) {
            log.error("月度成本达到停机阈值: month={}, usagePercent={}, estimatedCostCents={}, budgetCents={}",
                    month, usagePercent, estimatedCostCents, monitorProperties.getMonthlyBudgetCents());
            setHaltedMarker(month);
        }
        return snapshot;
    }

    /**
     * 判断当前月份是否应停止自动处理.
     *
     * @param month 月份
     * @return true 表示预算 gate 阻断自动处理
     */
    public boolean isProcessingHalted(YearMonth month) {
        if (monitorProperties.getMonthlyBudgetCents() <= 0) {
            return false;
        }
        try {
            if (redisRepository.exists(RedisKeys.costBudgetResume(month))) {
                return false;
            }
            return redisRepository.exists(RedisKeys.costBudgetHalted(month));
        } catch (RuntimeException e) {
            log.warn("读取成本停机标记失败, 放行自动处理: month={}, errorType={}",
                    month, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 刷新当月成本快照后再判断预算 gate，避免自动处理只依赖陈旧 halted 标记.
     *
     * @param month 月份
     * @return true 表示预算 gate 阻断自动处理
     */
    public boolean refreshAndCheckProcessingHalted(YearMonth month) {
        if (monitorProperties.getMonthlyBudgetCents() <= 0) {
            return false;
        }
        try {
            if (redisRepository.exists(RedisKeys.costBudgetResume(month))) {
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("读取成本恢复标记失败, 放行自动处理: month={}, errorType={}",
                    month, e.getClass().getSimpleName());
            return false;
        }
        CostSnapshot snapshot = summarizeMonth(month);
        if (snapshot.halted()) {
            return true;
        }
        return isProcessingHalted(month);
    }

    /**
     * 读取已保存的月度成本快照，不触发重算或写入，供只读运维端点使用.
     *
     * @param month 月份
     * @return 月度成本快照；尚未汇总时返回 null
     */
    public CostSnapshot readMonthSnapshot(YearMonth month) {
        return redisRepository.getObject(RedisKeys.costMonthly(month), CostSnapshot.class);
    }

    private long readLong(String key) {
        try {
            String value = redisRepository.get(key);
            if (value == null || value.isBlank()) {
                return 0L;
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("成本 Redis 数值格式非法, 按 0 处理: key={}", key);
            return 0L;
        } catch (RuntimeException e) {
            log.warn("读取成本 Redis 键失败, 按 0 处理: key={}, errorType={}",
                    key, e.getClass().getSimpleName());
            return 0L;
        }
    }

    private int usagePercent(long estimatedCostCents) {
        long budget = monitorProperties.getMonthlyBudgetCents();
        if (budget <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, estimatedCostCents * 100 / budget);
    }

    private String thresholdStatus(int usagePercent) {
        if (monitorProperties.getMonthlyBudgetCents() <= 0) {
            return "OBSERVE_ONLY";
        }
        if (usagePercent >= monitorProperties.getStopThresholdPercent()) {
            return "HALTED";
        }
        if (usagePercent >= monitorProperties.getWarningThresholdPercent()) {
            return "WARNING";
        }
        return "OK";
    }

    private void saveSnapshot(YearMonth month, CostSnapshot snapshot) {
        try {
            redisRepository.setObject(RedisKeys.costMonthly(month), snapshot, summaryTtl());
        } catch (RuntimeException e) {
            log.warn("保存月度成本快照失败: month={}, errorType={}", month, e.getClass().getSimpleName());
        }
    }

    private void setHaltedMarker(YearMonth month) {
        try {
            redisRepository.set(RedisKeys.costBudgetHalted(month), "true", summaryTtl());
        } catch (RuntimeException e) {
            log.warn("写入成本停机标记失败: month={}, errorType={}", month, e.getClass().getSimpleName());
        }
    }

    private Duration summaryTtl() {
        return Duration.ofDays(monitorProperties.getSummaryTtlDays());
    }

    private static long toCentsCeil(long microCents) {
        if (microCents <= 0) {
            return 0L;
        }
        return (microCents + MICRO_CENTS_PER_CENT - 1) / MICRO_CENTS_PER_CENT;
    }

    private static Map<String, CostSnapshot.ModelCost> immutablePerModel(Map<String, MutableModelCost> values) {
        Map<String, CostSnapshot.ModelCost> result = new LinkedHashMap<>();
        values.forEach((model, value) -> result.put(model, new CostSnapshot.ModelCost(
                value.inputTokens, value.outputTokens, toCentsCeil(value.estimatedMicroCents))));
        return result;
    }

    private static final class MutableModelCost {
        private long inputTokens;
        private long outputTokens;
        private long estimatedMicroCents;
    }
}
