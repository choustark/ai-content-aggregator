package com.choucj.aiaggregator.common.config;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator features 端点，暴露关键功能开关的只读状态用于运维核对.
 *
 * <p>Story 5.1 要求可通过 {@code /actuator/features} 查询 {@code rag.enabled}。
 */
@Component
@Endpoint(id = "features")
public class FeatureEndpoint {

    private final FeatureFlagsProperties featureFlagsProperties;
    private final Environment environment;

    /**
     * 构造 features 端点.
     *
     * @param featureFlagsProperties feature-flags 配置
     * @param environment            Spring 环境
     */
    public FeatureEndpoint(FeatureFlagsProperties featureFlagsProperties, Environment environment) {
        this.featureFlagsProperties = featureFlagsProperties;
        this.environment = environment;
    }

    /**
     * 读取功能开关快照.
     *
     * @return 功能开关 Map
     */
    @ReadOperation
    public Map<String, Object> features() {
        return Map.of(
                "twitter", Map.of(
                        "enabled", featureFlagsProperties.getTwitter().isEnabled(),
                        "useTwscrape", featureFlagsProperties.getTwitter().isUseTwscrape()),
                "github", Map.of(
                        "enabled", environment.getProperty("features.github.enabled", Boolean.class,
                                featureFlagsProperties.getGithub().isEnabled()),
                        "featureFlagEnabled", featureFlagsProperties.getGithub().isEnabled()),
                "rag", Map.of(
                        "enabled", environment.getProperty("features.rag.enabled", Boolean.class, false),
                        "featureFlagEnabled", featureFlagsProperties.getRag().isEnabled(),
                        "batchAsyncEnabled", featureFlagsProperties.getRag().isBatchAsyncEnabled()),
                "multiModel", Map.of(
                        "enabled", featureFlagsProperties.getMultiModel().isEnabled()),
                "wechat", Map.of(
                        "autoPublish", featureFlagsProperties.getWechat().isAutoPublish())
        );
    }
}
