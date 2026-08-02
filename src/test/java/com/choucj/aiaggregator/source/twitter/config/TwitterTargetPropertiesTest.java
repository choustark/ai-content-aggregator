package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 6.4: {@link TwitterTargetProperties} 默认值校验.
 */
class TwitterTargetPropertiesTest {

    @Test
    void shouldDefaultToDisabledAndEmptyUrls() {
        TwitterTargetProperties properties = new TwitterTargetProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getUrls()).isEmpty();
    }

    @Test
    void shouldAllowMutationForEnabledAndUrls() {
        TwitterTargetProperties properties = new TwitterTargetProperties();
        properties.setEnabled(true);
        properties.getUrls().add("https://x.com/OpenAI/status/123");

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getUrls()).containsExactly("https://x.com/OpenAI/status/123");
    }
}
