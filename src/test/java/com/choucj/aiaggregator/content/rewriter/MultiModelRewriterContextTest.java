package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.content.rewriter.config.RewriterConfig;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 5.4 — ContentRewriter 互斥装配测试.
 */
class MultiModelRewriterContextTest {

    @Test
    void shouldRegisterOnlySingleModelRewriterWhenMultiModelFlagMissing() {
        contextRunner()
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ContentRewriter.class);
                    assertThat(ctx).hasSingleBean(SingleModelRewriter.class);
                    assertThat(ctx).doesNotHaveBean(MultiModelRewriter.class);
                });
    }

    @Test
    void shouldRegisterOnlySingleModelRewriterWhenMultiModelDisabled() {
        contextRunner()
                .withPropertyValues("feature-flags.multi-model.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ContentRewriter.class);
                    assertThat(ctx).hasSingleBean(SingleModelRewriter.class);
                    assertThat(ctx).doesNotHaveBean(MultiModelRewriter.class);
                });
    }

    @Test
    void shouldRegisterOnlyMultiModelRewriterWhenMultiModelEnabled() {
        contextRunner()
                .withPropertyValues("feature-flags.multi-model.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ContentRewriter.class);
                    assertThat(ctx).hasSingleBean(MultiModelRewriter.class);
                    assertThat(ctx).doesNotHaveBean(SingleModelRewriter.class);
                });
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RewriterBeansConfig.class);
    }

    @Configuration
    @Import({RewriterConfig.class, SingleModelRewriter.class, MultiModelRewriter.class, RuleBasedModelScorer.class})
    static class RewriterBeansConfig {

        @Bean
        LlmClient llmClient() {
            return org.mockito.Mockito.mock(LlmClient.class);
        }

        @Bean
        TokenUsageTracker tokenUsageTracker() {
            return org.mockito.Mockito.mock(TokenUsageTracker.class);
        }
    }
}
