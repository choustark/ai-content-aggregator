package com.choucj.aiaggregator.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 5.5 成本监控配置绑定与校验测试.
 */
class CostMonitorPropertiesTest {

    @EnableConfigurationProperties({CostMonitorProperties.class, CostPricingProperties.class})
    static class TestConfig {
    }

    @Test
    void shouldApplyCostMonitorDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    CostMonitorProperties monitor = ctx.getBean(CostMonitorProperties.class);
                    assertThat(monitor.getMonthlyBudgetCents()).isZero();
                    assertThat(monitor.getWarningThresholdPercent()).isEqualTo(80);
                    assertThat(monitor.getStopThresholdPercent()).isEqualTo(100);
                    assertThat(monitor.getDailyCron()).isEqualTo("0 5 0 * * ?");
                    assertThat(monitor.getSummaryTtlDays()).isEqualTo(400);
                });
    }

    @Test
    void shouldBindModelPricing() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "cost.monitor.monthly-budget-cents=5000",
                        "cost.monitor.warning-threshold-percent=75",
                        "cost.monitor.stop-threshold-percent=95",
                        "cost.pricing.deepseek.input-cents-per-million=14",
                        "cost.pricing.deepseek.output-cents-per-million=28",
                        "cost.pricing.glm.input-cents-per-million=20",
                        "cost.pricing.glm.output-cents-per-million=80")
                .run(ctx -> {
                    CostMonitorProperties monitor = ctx.getBean(CostMonitorProperties.class);
                    CostPricingProperties pricing = ctx.getBean(CostPricingProperties.class);
                    assertThat(monitor.getMonthlyBudgetCents()).isEqualTo(5000);
                    assertThat(monitor.getWarningThresholdPercent()).isEqualTo(75);
                    assertThat(monitor.getStopThresholdPercent()).isEqualTo(95);
                    assertThat(pricing.getModels())
                            .containsKeys("deepseek", "glm");
                    assertThat(pricing.requireModel("deepseek").getInputCentsPerMillion()).isEqualTo(14);
                    assertThat(pricing.requireModel("glm").getOutputCentsPerMillion()).isEqualTo(80);
                });
    }

    @Test
    void shouldFailWhenWarningThresholdExceedsStopThreshold() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "cost.monitor.warning-threshold-percent=101",
                        "cost.monitor.stop-threshold-percent=100")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shouldFailWhenPricingIsIncomplete() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "cost.pricing.deepseek.input-cents-per-million=0",
                        "cost.pricing.deepseek.output-cents-per-million=28")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
