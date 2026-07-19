package com.choucj.aiaggregator.content.embedding;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.providers.ClusterConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spike 5.1 §3 H3 — langchain4j-community-redis EmbeddingStore 在 Redis Cluster 下的连通性 + fan-out 验证.
 *
 * <p>验证两件事 (决定 F2 / F3 高危发现):
 * <ol>
 *   <li><b>F2 (fan-out)</b>: 向自然分散在 3 分片的 9 条 embedding 写入后, store.search 是否返回全量 9 条.
 *       RedisEmbeddingStore 内部用 {@link UnifiedJedis} (项目 RedisClusterConfig 注入 ClusterConnectionProvider),
 *       Jedis cluster 对 RediSearch FT.SEARCH 模块命令的运行时 routing 行为无法靠源码确定, 必须实跑.</li>
 *   <li><b>F3 (score 语义)</b>: 观察 match.score 的数值, 判定 langchain4j 是否把 RediSearch raw distance
 *       (1-cosine_sim) 转成 similarity 返回给应用层. 若转了, 则 epics Story 5.2 "阈值 0.75" 在应用层直接成立;
 *       若没转 (返回 distance), 则需换算.</li>
 * </ol>
 *
 * <p>用确定性 dim=4 向量, 不依赖任何 EmbeddingModel 选型 (GLM/BGE 待 Mr.Choucj 定), 可独立运行.
 * 需本地 Redis Cluster 可达, 默认 skip; 用 {@code -Dspike.h3=true} 启用.
 *
 * <p>查询向量 [1,0,0,0]: 与 doc{0,3,6} sim=1.000, doc{1,4,7} sim=0.707, doc{2,5,8} sim=0.577.
 *
 * <p><b>实测结论 (2026-07-12, spike F2):</b> 当前 Redis Cluster 配置下本测试在 {@code build()} 阶段
 * 抛 {@code JedisBroadcastException} — RediSearch 与 OSS Redis Cluster 不兼容. 根因: FT.CREATE 被按
 * 索引名 slot 路由 (非 slot 节点 MOVED), 而 Jedis 把它当 broadcast 向所有节点发. langchain4j
 * RedisEmbeddingStore 在本架构下无法建索引. 详见 spike-5.1 §2.5 F2. Story 5.1 前需改用 standalone
 * Redis-Stack 实例 (或触发 correct-course 评估 Epic 1 存储架构).
 */
@EnabledIfSystemProperty(named = "spike.h3", matches = "true")
@org.junit.jupiter.api.Tag("external")
class EmbeddingStoreClusterFanoutTest {

    private static final String INDEX = "spike-h3-fanout";
    private static final String PREFIX = "spike-h3-";
    private static final int DIM = 4;
    private static final Set<HostAndPort> NODES = IntStream.of(7001, 7002, 7003)
            .mapToObj(p -> new HostAndPort("localhost", p))
            .collect(Collectors.toSet());

    private UnifiedJedis client() {
        return new UnifiedJedis(
                new ClusterConnectionProvider(NODES, DefaultJedisClientConfig.builder().build()),
                5, Duration.ofSeconds(10L));
    }

    private static String rootCause(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName() + ": " + t.getMessage());
        // JedisBroadcastException 把每节点错误存在 getErrors() (不在 cause 链)
        if ("JedisBroadcastException".equals(t.getClass().getSimpleName())) {
            try {
                Object errors = t.getClass().getMethod("getErrors").invoke(t);
                sb.append(" | errors=").append(errors);
            } catch (Exception ignore) { /* 旧版本无 getErrors */ }
        }
        Throwable c = t.getCause();
        while (c != null) {
            sb.append(" -> ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }

    private static float[] vecFor(int group) {
        switch (group % 3) {
            case 0: return new float[]{1f, 0f, 0f, 0f};   // sim 1.000
            case 1: return new float[]{1f, 1f, 0f, 0f};   // sim 0.707
            default: return new float[]{1f, 1f, 1f, 0f};  // sim 0.577
        }
    }

    @Test
    void shouldReturnAllShardsWhenClusterFanOut() {
        UnifiedJedis jedis = client();
        try {
            // 清可能残留的同名索引 (broadcast DROP), 无害若不存在
            try {
                jedis.ftDropIndex(INDEX);
            } catch (Exception e) {
                System.out.println("ftDropIndex(清理) 忽略: " + rootCause(e));
            }

            RedisEmbeddingStore store;
            try {
                store = RedisEmbeddingStore.builder()
                        .unifiedJedis(jedis)
                        .indexName(INDEX)
                        .dimension(DIM)
                        .build();
            } catch (Exception e) {
                // 把 JedisBroadcastException 的每节点根因打全
                System.out.println("build/createIndex 失败: " + rootCause(e));
                for (Throwable t : e.getSuppressed()) System.out.println("  suppressed: " + rootCause(t));
                throw e;
            }

            List<String> ids = IntStream.range(0, 9).mapToObj(i -> "doc" + i).collect(Collectors.toList());

            try {
                // 清残留 (同名 id 的上次写入), 无害若不存在
                store.removeAll(ids);

                // 写入 9 条 (key=prefix+id, 自然分散到 3 分片, 每分片约 3 条)
                for (int i = 0; i < 9; i++) {
                    store.add(ids.get(i), Embedding.from(vecFor(i)));
                }

                // 检索 query=[1,0,0,0]
                EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f, 0f}))
                        .maxResults(9)
                        .minScore(0.0)
                        .build());
                List<EmbeddingMatch<TextSegment>> matches = result.matches();

                System.out.println("\n=== H3 fan-out 验证 ===");
                System.out.println("返回 " + matches.size() + " / 9 条");
                matches.forEach(m -> System.out.printf("  id=%-8s score=%.6f seg=%s%n",
                        m.embeddingId(), m.score(), m.embedded() != null ? m.embedded().text() : null));

                // F2 判定: 若 Jedis cluster 对 FT.SEARCH 不 fan-out, 只返回索引名 slot 节点上的 ~3 条
                int returned = matches.size();
                if (returned >= 9) {
                    System.out.println("\n>>> F2: fan-out 正常, cluster 全量检索可用");
                } else {
                    System.out.printf("%n>>> F2: 仅返回 %d 条, Jedis cluster 未对 FT.SEARCH fan-out, RAG 需自实现 fan-out 或改 standalone%n", returned);
                }

                // F3 判定: score 数值 (理论 sim 1.0/0.707/0.577; 若是 distance 则 0/0.293/0.423)
                System.out.println("\n>>> F3: 若 score 出现 ~1.0/0.707/0.577 = langchain4j 已转 SIMILARITY (应用层阈值直接对 0.75)");
                System.out.println("        若 score 出现 ~0/0.293/0.423 = 返回 raw DISTANCE (需换算)");

                assertEquals(9, returned, "cluster fan-out 必须返回全部 9 条");
            } finally {
                store.removeAll(ids); // 清理本次
            }
        } finally {
            jedis.close();
        }
    }
}
