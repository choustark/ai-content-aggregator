package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.config.FeatureFlagsProperties;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.wechat.config.OriginalPostConfig;
import com.choucj.aiaggregator.source.twitter.config.TwitterTargetProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 8.3 / 8.6 D-G {@link ContentGenerationModeResolver} 模式解析测试.
 *
 * <p>8.6 变更: target urls 信号路径从全局配置级收敛为 per-tweet URL 匹配
 * (tweet.url trim + 去末尾斜杠归一化 equals); 信号命中场景进程级 warn 一次。
 */
@ExtendWith(OutputCaptureExtension.class)
class ContentGenerationModeResolverTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void should_default_to_rewrite_when_no_explicit_signal() {
        contextRunner.run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/u/status/1")))
                .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_remain_rewrite_when_original_post_disabled_even_if_target_configured() {
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=false",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_remain_rewrite_when_original_post_enabled_without_target_signal() {
        contextRunner
                .withPropertyValues("wechat.mp.original-post.enabled=true")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_resolve_preserve_original_when_feature_enabled_and_tweet_url_hits_target() {
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> assertThat(
                        resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL));
    }

    @Test
    void should_remain_rewrite_when_tweet_url_misses_target_urls() {
        // D-G: per-tweet 判定 — 批次内未命中 target urls 的推文走 REWRITE
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> assertThat(
                        resolver(ctx).resolve(tweet("https://x.com/OtherUser/status/456")))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_match_target_url_after_trim_and_trailing_slash_normalization() {
        // D-G: 配置 url 带末尾斜杠 + tweet url 带首尾空白 → 归一化后命中
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123/")
                .run(ctx -> assertThat(resolver(ctx).resolve(
                        tweet("  https://x.com/OpenAI/status/123  ")))
                        .isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL));
    }

    @Test
    void should_remain_rewrite_when_tweet_url_is_blank_or_missing() {
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet(null)))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_force_rewrite_when_enabled_false_and_default_mode_preserve_original() {
        // CR Round 1 Decision-1: enabled 是唯一总开关, 关闭时 default-mode 不得旁路
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=false",
                        "wechat.mp.original-post.default-mode=PRESERVE_ORIGINAL")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_honor_default_mode_when_enabled_true_and_no_target_signal() {
        // D-G: defaultMode 全局路径不变 (无 target urls 时全局生效)
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "wechat.mp.original-post.default-mode=PRESERVE_ORIGINAL")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL));
    }

    @Test
    void should_not_resolve_preserve_original_when_target_urls_only_blank_entries() {
        // CR Round 1: 空白 urls 元素不算有效信号, 与 ApifyDiscoveryClient 归一化语义对齐
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]= ",
                        "twitter.target.urls[1]=")
                .run(ctx -> assertThat(resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")))
                        .isEqualTo(ContentGenerationMode.REWRITE));
    }

    @Test
    void should_honor_explicit_override_before_configuration_signals() {
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=false",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> assertThat(resolver(ctx).resolve(
                        tweet("https://x.com/OpenAI/status/123"), ContentGenerationMode.PRESERVE_ORIGINAL))
                        .isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL));
    }

    /**
     * D-G: 信号命中场景进程级 warn 一次 (指定抓取入口未接线说明); 多次 resolve 只 warn 一次。
     */
    @Test
    void should_warn_once_when_target_signal_configured(CapturedOutput output) {
        contextRunner
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "twitter.target.enabled=true",
                        "twitter.target.urls[0]=https://x.com/OpenAI/status/123")
                .run(ctx -> {
                    ContentGenerationModeResolver r = resolver(ctx);
                    r.resolve(tweet("https://x.com/OpenAI/status/123"));
                    r.resolve(tweet("https://x.com/OtherUser/status/456"));
                    r.resolve(tweet("https://x.com/Third/status/789"));
                });

        String log = output.getOut();
        assertThat(log).contains("指定抓取入口未接线");
        assertThat(log.indexOf("指定抓取入口未接线"))
                .isEqualTo(log.lastIndexOf("指定抓取入口未接线"));
    }

    @Test
    void should_not_warn_when_target_signal_absent(CapturedOutput output) {
        contextRunner
                .withPropertyValues("wechat.mp.original-post.enabled=true")
                .run(ctx -> resolver(ctx).resolve(tweet("https://x.com/OpenAI/status/123")));

        assertThat(output.getOut()).doesNotContain("指定抓取入口未接线");
    }

    @Test
    void should_not_be_affected_by_multi_model_feature_flag_when_resolving_rewrite_mode() {
        assertRewriteModeUnderMultiModelFlag(false);
        assertRewriteModeUnderMultiModelFlag(true);
    }

    private void assertRewriteModeUnderMultiModelFlag(boolean enabled) {
        contextRunner
                .withPropertyValues("feature-flags.multi-model.enabled=" + enabled)
                .run(ctx -> {
                    FeatureFlagsProperties flags = ctx.getBean(FeatureFlagsProperties.class);
                    assertThat(flags.getMultiModel().isEnabled()).isEqualTo(enabled);
                    assertThat(resolver(ctx).resolve(tweet(null))).isEqualTo(ContentGenerationMode.REWRITE);
                });
    }

    private static ContentGenerationModeResolver resolver(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(ContentGenerationModeResolver.class);
    }

    private static Tweet tweet(String url) {
        return Tweet.builder().id("id-1").content("content").url(url).build();
    }

    @EnableConfigurationProperties({TwitterTargetProperties.class, FeatureFlagsProperties.class})
    @Import(OriginalPostConfig.class)
    static class TestConfig {

        @Bean
        ContentGenerationModeResolver contentGenerationModeResolver(
                com.choucj.aiaggregator.publish.wechat.config.OriginalPostProperties originalPostProperties,
                TwitterTargetProperties twitterTargetProperties) {
            return new ContentGenerationModeResolver(originalPostProperties, twitterTargetProperties);
        }
    }
}
