package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeatureFlagsProperties} 绑定测试.
 * 无 mandatory 字段, 不测校验失败路径.
 */
class FeatureFlagsPropertiesTest {

    @EnableConfigurationProperties(FeatureFlagsProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenNoConfig() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    FeatureFlagsProperties props = ctx.getBean(FeatureFlagsProperties.class);
                    assertThat(props.getTwitter().isEnabled()).isFalse();
                    assertThat(props.getTwitter().isUseTwscrape()).isFalse();
                    assertThat(props.getGithub().isEnabled()).isFalse();
                    assertThat(props.getRag().isEnabled()).isFalse();
                    assertThat(props.getMultiModel().isEnabled()).isFalse();
                    assertThat(props.getWechat().isAutoPublish()).isFalse();
                });
    }

    @Test
    void shouldBindNestedFlagsWhenProvided() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "feature-flags.twitter.enabled", "true",
                                "feature-flags.twitter.use-twscrape", "true",
                                "feature-flags.github.enabled", "true",
                                "feature-flags.wechat.auto-publish", "true"))))
                .run(ctx -> {
                    FeatureFlagsProperties props = ctx.getBean(FeatureFlagsProperties.class);
                    assertThat(props.getTwitter().isEnabled()).isTrue();
                    assertThat(props.getTwitter().isUseTwscrape()).isTrue();
                    assertThat(props.getGithub().isEnabled()).isTrue();
                    assertThat(props.getWechat().isAutoPublish()).isTrue();
                    // 未配置的开关仍是默认值 false
                    assertThat(props.getRag().isEnabled()).isFalse();
                });
    }
}
