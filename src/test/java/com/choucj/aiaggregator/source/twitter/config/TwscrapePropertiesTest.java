package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.2b {@link TwscrapeProperties} 配置绑定测试.
 */
class TwscrapePropertiesTest {

    @Test
    void shouldUseDefaultsWhenUnset() {
        TwscrapeProperties p = new TwscrapeProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getTimeoutSeconds()).isEqualTo(120);
        assertThat(p.getExecutable()).isEqualTo("twscrape");
        assertThat(p.isSkipAccountCheck()).isFalse();
    }

    @Test
    void shouldBindConfiguredValues() {
        TwscrapeProperties p = new TwscrapeProperties();
        p.setEnabled(false);
        p.setTimeoutSeconds(300);
        p.setExecutable("/usr/local/bin/twscrape");
        p.setSkipAccountCheck(true);

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getTimeoutSeconds()).isEqualTo(300);
        assertThat(p.getExecutable()).isEqualTo("/usr/local/bin/twscrape");
        assertThat(p.isSkipAccountCheck()).isTrue();
    }
}
