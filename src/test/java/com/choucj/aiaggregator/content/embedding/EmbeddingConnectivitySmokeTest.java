package com.choucj.aiaggregator.content.embedding;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 5.1 TE2 — GLM embedding-3 + redis-vector 真实连通性冒烟测试.
 *
 * <p>默认跳过。需要本地 {@code redis-vector} 可达且 {@code api-keys.yml} 或进程环境可解析
 * {@code llm.glm.api-key} 后运行。
 */
@org.junit.jupiter.api.Tag("external")
class EmbeddingConnectivitySmokeTest {

    private static final String INDEX = "aiaggregator-embeddings-smoke";
    private static final String PREFIX = "rag:embedding:";
    private static final int DIMENSION = 1024;

    @Test
    void shouldEmbedStoreAndSearchWhenExternalDependenciesAvailable() {
        String apiKey = resolveGlmEmbeddingApiKey();
        assumeTrue(isUsableApiKey(apiKey), "跳过: 需要 llm.glm.api-key 或 LLM_GLM_API_KEY");
        assumeTrue(Boolean.getBoolean("redis.vector.available"), "跳过: 需要 -Dredis.vector.available=true");
        int redisPort = Integer.getInteger("redis.vector.port", 7100);

        try (UnifiedJedis jedis = new UnifiedJedis(new HostAndPort("localhost", redisPort))) {
            assertThat(jedis.ping()).isEqualTo("PONG");
            assertThat(jedis.ftList()).isNotNull();

            EmbeddingModel model = OpenAiEmbeddingModel.builder()
                    .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                    .apiKey(apiKey)
                    .modelName("embedding-3")
                    .dimensions(DIMENSION)
                    .timeout(Duration.ofSeconds(60))
                    .build();
            long start = System.nanoTime();
            float[] vector = model.embed("AI 内容聚合器 RAG 向量检索测试").content().vector();
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(vector).hasSize(DIMENSION);
            assertThat(elapsedMs).isLessThan(5_000);

            RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                    .unifiedJedis(jedis)
                    .indexName(INDEX)
                    .prefix(PREFIX)
                    .dimension(DIMENSION)
                    .build();
            String articleId = "smoke-" + UUID.randomUUID();
            try {
                store.addAll(List.of(articleId),
                        List.of(Embedding.from(vector)),
                        List.of(TextSegment.from("AI 内容聚合器 RAG 向量检索测试")));

                assertThat(jedis.exists(PREFIX + articleId)).isTrue();
                assertIndexSchema(jedis.ftInfo(INDEX));

                EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vector))
                        .maxResults(3)
                        .minScore(0.0)
                        .build());

                assertThat(result.matches()).isNotEmpty();
                assertThat(result.matches().getFirst().embeddingId()).isEqualTo(articleId);
                assertThat(result.matches().getFirst().embedded().text()).isEqualTo("AI 内容聚合器 RAG 向量检索测试");
            } finally {
                store.removeAll(List.of(articleId));
            }
        }
    }

    private static void assertIndexSchema(Object ftInfo) {
        String normalized = String.valueOf(ftInfo).toLowerCase(java.util.Locale.ROOT);
        assertThat(normalized).contains("vector");
        assertThat(normalized).contains("float32");
        assertThat(normalized).contains("1024");
        assertThat(normalized).contains("cosine");
    }

    private static String resolveGlmEmbeddingApiKey() {
        ConfigurableEnvironment env = new StandardEnvironment();
        loadYamlIfPresent(env, "api-keys.yml");

        String glmApiKey = env.getProperty("llm.glm.api-key");
        if (isUsableApiKey(glmApiKey)) {
            return glmApiKey;
        }

        return System.getenv("LLM_GLM_API_KEY");
    }

    private static void loadYamlIfPresent(ConfigurableEnvironment env, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return;
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            List<PropertySource<?>> propertySources = loader.load(path, resource);
            propertySources.forEach(source -> env.getPropertySources().addFirst(source));
        } catch (IOException e) {
            throw new UncheckedIOException("无法加载 " + path, e);
        }
    }

    private static boolean isUsableApiKey(String apiKey) {
        return apiKey != null
                && !apiKey.isBlank()
                && !"dev-placeholder".equals(apiKey)
                && !apiKey.startsWith("your-");
    }
}
