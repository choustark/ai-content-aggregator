package com.choucj.aiaggregator.monitoring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Story 5.5: 模型单价配置，避免把易变供应商价格硬编码进代码.
 *
 * <p>金额单位使用 cents per million tokens。业务侧换算为 micro-cents 累计，
 * 避免 double 舍入误差进入 Redis 主数据。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cost")
public class CostPricingProperties {

    /** 按模型名配置的输入/输出单价。 */
    @Valid
    private Map<String, ModelPricing> pricing = new LinkedHashMap<>();

    /**
     * 返回按模型名索引的单价表.
     *
     * @return 单价表
     */
    public Map<String, ModelPricing> getModels() {
        return pricing;
    }

    /**
     * 读取指定模型单价；缺失时抛异常，避免静默按 0 计费.
     *
     * @param model 模型名
     * @return 模型单价配置
     */
    public ModelPricing requireModel(String model) {
        ModelPricing modelPricing = pricing.get(model);
        if (modelPricing == null) {
            throw new IllegalArgumentException("缺少模型成本单价配置: model=" + model);
        }
        return modelPricing;
    }

    /**
     * 单个模型的输入/输出 token 单价.
     */
    @Data
    public static class ModelPricing {
        /** 输入 token 单价，单位 cents / 1M tokens。 */
        @Min(value = 1, message = "input-cents-per-million must be >= 1")
        private long inputCentsPerMillion;

        /** 输出 token 单价，单位 cents / 1M tokens。 */
        @Min(value = 1, message = "output-cents-per-million must be >= 1")
        private long outputCentsPerMillion;
    }
}
