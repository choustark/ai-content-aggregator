package com.choucj.aiaggregator.content.embedding;

import com.choucj.aiaggregator.common.config.EmbeddingConfig;
import com.choucj.aiaggregator.common.config.LlmProperties;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import redis.clients.jedis.UnifiedJedis;

import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 5.1 AC5 — {@code features.rag.enabled} 控制 Embedding 相关 Bean 注册.
 */
class EmbeddingBeanConditionalTest {

    @EnableConfigurationProperties(LlmProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldNotRegisterEmbeddingBeansWhenRagDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", baseProperties(false))))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(EmbeddingService.class);
                    assertThat(ctx).doesNotHaveBean(EmbeddingModel.class);
                });
    }

    @Test
    void shouldRegisterEmbeddingBeansWhenRagEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", baseProperties(true))))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                    assertThat(ctx).hasBean("embeddingModel");
                });
    }

    @Test
    void shouldExposeSingleRedisEmbeddingStoreWhenRedisVectorEnabled() {
        UnifiedJedis jedis = Mockito.mock(UnifiedJedis.class);
        Mockito.when(jedis.ftList()).thenReturn(Set.of("aiaggregator-embeddings"));
        Mockito.when(jedis.ftInfo("aiaggregator-embeddings")).thenReturn(validIndexInfo());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisEmbeddingStoreAutoConfiguration.class))
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withBean("embeddingUnifiedJedis", UnifiedJedis.class, () -> jedis)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", baseProperties(true, true))))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedisEmbeddingStore.class);
                    assertThat(ctx).hasSingleBean(EmbeddingStore.class);
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                });
    }

    @Test
    void shouldEnableRedisVectorStoreFromRagSwitchPlaceholder() {
        UnifiedJedis jedis = Mockito.mock(UnifiedJedis.class);
        Mockito.when(jedis.ftList()).thenReturn(Set.of("aiaggregator-embeddings"));
        Mockito.when(jedis.ftInfo("aiaggregator-embeddings")).thenReturn(validIndexInfo());

        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withBean("embeddingUnifiedJedis", UnifiedJedis.class, () -> jedis)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.ofEntries(
                                Map.entry("features.rag.enabled", "true"),
                                Map.entry("feature-flags.rag.batch-async-enabled", "true"),
                                Map.entry("llm.deepseek.api-key", "ds-key"),
                                Map.entry("llm.glm.api-key", "glm-key"),
                                Map.entry("langchain4j.open-ai.embedding-model.model-name", "embedding-3"),
                                Map.entry("langchain4j.open-ai.embedding-model.dimensions", "1024"),
                                Map.entry("langchain4j.open-ai.embedding-model.timeout", "60s"),
                                Map.entry("langchain4j.community.redis.enabled", "${features.rag.enabled:false}"),
                                Map.entry("langchain4j.community.redis.dimension", "1024")))))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedisEmbeddingStore.class);
                    assertThat(ctx).hasSingleBean(EmbeddingStore.class);
                    assertThat(ctx).hasSingleBean(EmbeddingService.class);
                });
    }

    @Test
    void shouldFailFastWhenModelAndRedisDimensionsDiffer() {
        UnifiedJedis jedis = Mockito.mock(UnifiedJedis.class);
        Mockito.when(jedis.ftList()).thenReturn(Set.of("aiaggregator-embeddings"));
        Mockito.when(jedis.ftInfo("aiaggregator-embeddings")).thenReturn(validIndexInfo());

        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withBean("embeddingUnifiedJedis", UnifiedJedis.class, () -> jedis)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.ofEntries(
                                Map.entry("features.rag.enabled", "true"),
                                Map.entry("feature-flags.rag.batch-async-enabled", "true"),
                                Map.entry("llm.deepseek.api-key", "ds-key"),
                                Map.entry("llm.glm.api-key", "glm-key"),
                                Map.entry("langchain4j.open-ai.embedding-model.model-name", "embedding-3"),
                                Map.entry("langchain4j.open-ai.embedding-model.dimensions", "1024"),
                                Map.entry("langchain4j.open-ai.embedding-model.timeout", "60s"),
                                Map.entry("langchain4j.community.redis.enabled", "true"),
                                Map.entry("langchain4j.community.redis.dimension", "2048")))))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasRootCauseMessage(
                                "Embedding model dimension must match Redis schema dimension: model=1024, redis=2048"));
    }

    @Test
    void shouldFailFastWhenRedisIndexSchemaIsWrong() {
        UnifiedJedis jedis = Mockito.mock(UnifiedJedis.class);
        Mockito.when(jedis.ftList()).thenReturn(Set.of("aiaggregator-embeddings"));
        Mockito.when(jedis.ftInfo("aiaggregator-embeddings")).thenReturn(Map.of(
                "index_definition", Map.of("prefixes", List.of("wrong:prefix:")),
                "attributes", List.of(Map.of(
                        "identifier", "wrong_vector",
                        "type", "VECTOR",
                        "data_type", "FLOAT64",
                        "dim", 2048,
                        "distance_metric", "L2"))
        ));

        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withBean("embeddingUnifiedJedis", UnifiedJedis.class, () -> jedis)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", baseProperties(true, true))))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("RediSearch index schema mismatch for aiaggregator-embeddings: "
                                + "missing FLOAT32 vector type"));
    }

    @Test
    void shouldFailFastWhenRagNumericConfigIsInvalid() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingConfig.class, TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.ofEntries(
                                Map.entry("features.rag.enabled", "true"),
                                Map.entry("features.rag.async-threads", "0"),
                                Map.entry("feature-flags.rag.batch-async-enabled", "true"),
                                Map.entry("llm.deepseek.api-key", "ds-key"),
                                Map.entry("llm.glm.api-key", "glm-key"),
                                Map.entry("langchain4j.open-ai.embedding-model.model-name", "embedding-3"),
                                Map.entry("langchain4j.open-ai.embedding-model.dimensions", "1024"),
                                Map.entry("langchain4j.open-ai.embedding-model.timeout", "60s"),
                                Map.entry("langchain4j.community.redis.enabled", "false")))))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("features.rag.async-threads must be > 0"));
    }

    private static Map<String, Object> baseProperties(boolean ragEnabled) {
        return baseProperties(ragEnabled, false);
    }

    private static Map<String, Object> baseProperties(boolean ragEnabled, boolean redisEnabled) {
        return Map.ofEntries(
                Map.entry("features.rag.enabled", Boolean.toString(ragEnabled)),
                Map.entry("feature-flags.rag.batch-async-enabled", "true"),
                Map.entry("llm.deepseek.api-key", "ds-key"),
                Map.entry("llm.glm.api-key", "glm-key"),
                Map.entry("langchain4j.open-ai.embedding-model.model-name", "embedding-3"),
                Map.entry("langchain4j.open-ai.embedding-model.dimensions", "1024"),
                Map.entry("langchain4j.open-ai.embedding-model.timeout", "60s"),
                Map.entry("langchain4j.community.redis.enabled", Boolean.toString(redisEnabled)),
                Map.entry("langchain4j.community.redis.dimension", "1024")
        );
    }

    private static Map<String, Object> validIndexInfo() {
        return Map.of(
                "index_definition", Map.of("prefixes", List.of("rag:embedding:")),
                "attributes", List.of(Map.of(
                        "identifier", "vector",
                        "type", "VECTOR",
                        "algorithm", "FLAT",
                        "data_type", "FLOAT32",
                        "dim", 1024,
                        "distance_metric", "COSINE"))
        );
    }
}
