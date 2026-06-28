package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WxMpProperties} 绑定 + 校验测试.
 */
class WxMpPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of(
                            "wx.mp.app-id", "wx-test-id",
                            "wx.mp.secret", "wx-test-secret",
                            "wx.mp.use-stable-token", "true"))));

    @EnableConfigurationProperties(WxMpProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldBindAllFieldsWhenConfigured() {
        runner.run(ctx -> {
            WxMpProperties props = ctx.getBean(WxMpProperties.class);
            assertThat(props.getAppId()).isEqualTo("wx-test-id");
            assertThat(props.getSecret()).isEqualTo("wx-test-secret");
            assertThat(props.isUseStableToken()).isTrue();
        });
    }

    @Test
    void shouldFailToStartWhenAppIdMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of("wx.mp.secret", "wx-test-secret"))))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void shouldFailToStartWhenSecretBlank() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "wx.mp.app-id", "wx-test-id",
                                "wx.mp.secret", ""))))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }
}
