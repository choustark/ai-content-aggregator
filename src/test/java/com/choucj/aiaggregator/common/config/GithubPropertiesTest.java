package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GithubProperties} 绑定 + 校验测试.
 */
class GithubPropertiesTest {

    @EnableConfigurationProperties(GithubProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldBindTokenWhenConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of("github.token", "ghp_xxx"))))
                .run(ctx -> {
                    GithubProperties props = ctx.getBean(GithubProperties.class);
                    assertThat(props.getToken()).isEqualTo("ghp_xxx");
                });
    }

    @Test
    void shouldFailToStartWhenTokenMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(BindValidationException.class);
                });
    }
}
