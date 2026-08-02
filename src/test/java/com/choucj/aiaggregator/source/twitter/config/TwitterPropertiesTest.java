package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.2a {@link TwitterProperties} 配置绑定测试.
 */
class TwitterPropertiesTest {

    @Test
    void shouldUseDefaultsWhenUnset() {
        TwitterProperties p = new TwitterProperties();
        assertThat(p.getAccounts()).isEmpty();
        assertThat(p.effectiveDiscoveryProviders()).containsExactly("rsshub");
    }

    @Test
    void shouldBindConfiguredValues() {
        TwitterProperties p = new TwitterProperties();
        p.setAccounts(List.of("karpathy", "sama"));

        assertThat(p.getAccounts()).containsExactly("karpathy", "sama");
    }

    @Test
    void shouldPreferDiscoveryProviderChainOverLegacySingleProvider() {
        TwitterProperties p = new TwitterProperties();
        p.setDiscoveryProvider("rsshub");
        p.setDiscoveryProviders(List.of(" Scraper ", "apify", "scraper"));

        assertThat(p.effectiveDiscoveryProviders()).containsExactly("scraper", "apify");
    }
}
