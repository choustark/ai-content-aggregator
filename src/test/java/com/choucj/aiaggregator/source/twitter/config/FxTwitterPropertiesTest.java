package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.2a {@link FxTwitterProperties} 配置绑定测试.
 */
class FxTwitterPropertiesTest {

    @Test
    void shouldUseDefaultsWhenUnset() {
        FxTwitterProperties p = new FxTwitterProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getTimeoutSeconds()).isEqualTo(15);
        assertThat(p.getInstance()).isEqualTo("https://api.fxtwitter.com");
    }

    @Test
    void shouldBindConfiguredValues() {
        FxTwitterProperties p = new FxTwitterProperties();
        p.setEnabled(false);
        p.setTimeoutSeconds(30);
        p.setInstance("https://custom.example");

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getTimeoutSeconds()).isEqualTo(30);
        assertThat(p.getInstance()).isEqualTo("https://custom.example");
    }
}
