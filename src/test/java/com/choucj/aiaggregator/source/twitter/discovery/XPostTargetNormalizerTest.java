package com.choucj.aiaggregator.source.twitter.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XPostTargetNormalizerTest {
    private final XPostTargetNormalizer normalizer = new XPostTargetNormalizer();

    @Test
    void shouldNormalizeAllowedPostTargets() {
        assertThat(normalizer.normalize("123")).isEqualTo(
                new XPostTargetNormalizer.Target("https://x.com/i/status/123", "123"));
        assertThat(normalizer.normalize("000123")).isEqualTo(
                new XPostTargetNormalizer.Target("https://x.com/i/status/123", "123"));
        assertThat(normalizer.normalize("https://twitter.com/OpenAI/status/456?s=20")).isEqualTo(
                new XPostTargetNormalizer.Target("https://x.com/OpenAI/status/456", "456"));
        assertThat(normalizer.normalize("http://www.x.com/i/status/789/")).isEqualTo(
                new XPostTargetNormalizer.Target("https://x.com/i/status/789", "789"));
    }

    @Test
    void shouldRejectEverythingOutsideExactPostContract() {
        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize(" ")).isNull();
        assertThat(normalizer.normalize("123456789012345678901")).isNull();
        assertThat(normalizer.normalize("https://twitter.com.evil/a/status/1")).isNull();
        assertThat(normalizer.normalize("https://x.com/a/status/abc")).isNull();
        assertThat(normalizer.normalize("https://x.com/a/status/1/extra")).isNull();
        assertThat(normalizer.normalize("https://x.com/i/article/1")).isNull();
        assertThat(normalizer.normalize("https://user@x.com/a/status/1")).isNull();
        assertThat(normalizer.normalize("ftp://x.com/a/status/1")).isNull();
    }
}
