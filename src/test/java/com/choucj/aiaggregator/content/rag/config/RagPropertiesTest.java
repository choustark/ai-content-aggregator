package com.choucj.aiaggregator.content.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 5.2 AC3 — RAG 检索配置默认值与 fail-fast 校验.
 */
class RagPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(RagProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RagProperties.class);
            RagProperties properties = ctx.getBean(RagProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getSimilarityThreshold()).isEqualTo(0.75);
            assertThat(properties.getMaxReferences()).isEqualTo(3);
        });
    }

    @Test
    void shouldBindConfiguredValues() {
        runner.withPropertyValues(
                        "features.rag.enabled=true",
                        "features.rag.similarity-threshold=0.82",
                        "features.rag.max-references=5")
                .run(ctx -> {
                    RagProperties properties = ctx.getBean(RagProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getSimilarityThreshold()).isEqualTo(0.82);
                    assertThat(properties.getMaxReferences()).isEqualTo(5);
                });
    }

    @Test
    void shouldFailFastWhenSimilarityThresholdIsBelowRange() {
        runner.withPropertyValues("features.rag.similarity-threshold=-0.01")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("similarityThreshold");
                });
    }

    @Test
    void shouldFailFastWhenSimilarityThresholdIsAboveRange() {
        runner.withPropertyValues("features.rag.similarity-threshold=1.01")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("similarityThreshold");
                });
    }

    @Test
    void shouldFailFastWhenSimilarityThresholdIsNaN() {
        runner.withPropertyValues("features.rag.similarity-threshold=NaN")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("similarityThreshold must be finite");
                });
    }

    @Test
    void shouldFailFastWhenMaxReferencesIsInvalid() {
        runner.withPropertyValues("features.rag.max-references=0")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("maxReferences");
                });
    }

    @Test
    void shouldFailFastWhenMaxReferencesExceedsUpperBound() {
        runner.withPropertyValues("features.rag.max-references=11")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("maxReferences");
                });
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
