package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.1 AC-2 {@link RSSHubProperties} 配置绑定测试.
 *
 * <p>仅验证默认值与 setter/getter — 完整 @ConfigurationProperties + @Validated 校验由 Spring Boot
 * 在 ApplicationContext 初始化时执行(本 story 不重复 BootstrapContext 测试, 避免依赖 application.yml).
 */
class RSSHubPropertiesTest {

    @Test
    void shouldUseDefaultsWhenUnset() {
        RSSHubProperties p = new RSSHubProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getTimeoutSeconds()).isEqualTo(30);
        assertThat(p.getInstances()).isEmpty();
    }

    @Test
    void shouldBindConfiguredValues() {
        RSSHubProperties p = new RSSHubProperties();
        p.setEnabled(false);
        p.setTimeoutSeconds(60);
        p.setInstances(List.of("https://rsshub.app", "http://localhost:1200"));

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getTimeoutSeconds()).isEqualTo(60);
        assertThat(p.getInstances()).containsExactly("https://rsshub.app", "http://localhost:1200");
    }
}
