package com.choucj.aiaggregator.common.repository;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 1.5b AC-8 端到端集成测试 — 验证 {@code RedisTemplate<String, Object>}
 * + {@code GenericJackson2JsonRedisSerializer} + {@link RedisRepository} 在真实
 * Redis Cluster 下的 JSON 序列化往返与异常映射.
 *
 * <p><b>测试场景(AC-8):</b>
 * <ol>
 *   <li><b>对象写入读回一致:</b> {@code setObject(SampleDO)} + {@code getObject(SampleDO.class)}
 *       字段逐一断言,验证 JSON 序列化/反序列化往返无损</li>
 *   <li><b>{@code getClass()} 类型断言:</b> 反序列化结果是具体 {@code SampleDO} 而非
 *       {@code LinkedHashMap}({@code @class} 元信息生效的核心证据)</li>
 *   <li><b>复杂嵌套对象:</b> DO 含 {@code List<String>} / {@code Map<String, Integer>} 字段,
 *       验证 Jackson 对集合属性的序列化能力</li>
 *   <li><b>序列化失败路径:</b> 写入 {@code new Object()}(无属性空 Bean) →
 *       Jackson 抛 {@code InvalidDefinitionException} → 被包装为
 *       {@code SerializationException} → 映射为 {@link NonRetryableException}</li>
 * </ol>
 *
 * <p><b>跳过策略(复用 1.5a 模式):</b> {@link TestInstance}(PER_CLASS) 让 {@link BeforeAll}
 * 成为实例方法,可访问 {@link Autowired} 注入的 {@link StringRedisTemplate} 执行
 * {@code PING};失败则 {@link Assumptions#assumeTrue(boolean, String)} 跳过全部测试,
 * 不阻塞 CI(本地无 Cluster 时).
 *
 * <p><b>profile=test:</b> 加载 {@code application-test.yml},集群节点指向
 * {@code redis-cluster-1/2/3:7001/7002/7003}(Docker container name).
 *
 * <p><b>键隔离:</b> 所有测试键以 {@code test:1.5b:json:} 为前缀,{@link AfterEach} 统一清理.
 *
 * <p><b>SampleDO 设计:</b> 嵌套静态类(无需独立文件).Jackson 反序列化 nested static class
 * 受 {@code @class} 元信息指引,与外部顶级类无差异.字段全包 {@code List/Map/原生/String}
 * 覆盖典型 DO 形态.
 *
 * <p>引用源: Story 1.5b 创建(AC-8);单测 {@link RedisExceptionMappingTest} 覆盖异常映射细节,
 * 本集成测试聚焦真实 Redis 下的端到端正确性.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("external")
class RedisJsonIntegrationTest {

    private static final String KEY_PREFIX = "test:1.5b:json:";

    @Autowired
    private RedisRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 连通性预检 — 集群离线时跳过整套测试(与 1.5a {@code RedisClusterConnectivityTest} 一致).
     */
    @BeforeAll
    void requireRedisClusterOnline() {
        boolean reachable;
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            reachable = "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "本地 Redis Cluster 不可用,跳过 Story 1.5b JSON 集成测试(AC-8: 不阻塞 CI)");
    }

    @AfterEach
    void cleanupKeys() {
        redisTemplate.delete(java.util.List.of(
                KEY_PREFIX + "roundtrip",
                KEY_PREFIX + "type-assert",
                KEY_PREFIX + "nested",
                KEY_PREFIX + "fail-empty-bean"
        ));
    }

    @Test
    void shouldSetObjectAndGetObjectReturnSameFields() {
        String key = KEY_PREFIX + "roundtrip";
        SampleDO sample = SampleDO.of("alice", 30, List.of("tag-a", "tag-b"),
                Map.of("k1", 1, "k2", 2));

        repository.setObject(key, sample, Duration.ofMinutes(5));

        SampleDO readBack = repository.getObject(key, SampleDO.class);
        assertThat(readBack)
                .as("setObject 后 getObject 应返回非 null 对象")
                .isNotNull();
        assertThat(readBack.getName()).isEqualTo("alice");
        assertThat(readBack.getAge()).isEqualTo(30);
        assertThat(readBack.getTags()).containsExactly("tag-a", "tag-b");
        assertThat(readBack.getScores()).containsEntry("k1", 1).containsEntry("k2", 2);
    }

    @Test
    void shouldGetObjectReturnConcreteTypeNotLinkedHashMap() {
        String key = KEY_PREFIX + "type-assert";
        repository.setObject(key, SampleDO.of("bob", 25, List.of(), Map.of()),
                Duration.ofMinutes(5));

        SampleDO readBack = repository.getObject(key, SampleDO.class);

        assertThat(readBack)
                .as("GenericJackson2JsonRedisSerializer 反序列化应返回具体 SampleDO,而非 LinkedHashMap — @class 元信息生效证据")
                .isExactlyInstanceOf(SampleDO.class);
        assertThat(readBack.getClass())
                .isEqualTo(SampleDO.class);
    }

    @Test
    void shouldRoundtripNestedCollectionsWithoutLoss() {
        String key = KEY_PREFIX + "nested";
        SampleDO sample = SampleDO.of("carol", 28,
                List.of("x", "y", "z"),
                Map.of("hits", 100, "misses", 5));

        repository.setObject(key, sample, Duration.ofMinutes(5));

        SampleDO readBack = repository.getObject(key, SampleDO.class);
        assertThat(readBack.getTags()).hasSize(3).containsExactly("x", "y", "z");
        assertThat(readBack.getScores())
                .hasSize(2)
                .containsEntry("hits", 100)
                .containsEntry("misses", 5);
    }

    @Test
    void shouldMapEmptyBeanSerializationFailureToNonRetryable() {
        String key = KEY_PREFIX + "fail-empty-bean";

        assertThatThrownBy(() -> repository.setObject(key, new Object(), Duration.ofMinutes(1)))
                .as("写入 java.lang.Object(无属性空 Bean) 应被 Jackson 拒绝,映射为 NonRetryableException")
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("setObject 失败")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    /**
     * 测试用 POJO — 模拟真实 DO 形态(字段 + List + Map).
     *
     * <p>必须有无参构造器(Jackson 反序列化要求) + getter/setter(Lombok 提供).
     * 用静态工厂 {@link #of} 简化测试构造,构造器保留 protected 让 Jackson 可见.
     */
    @lombok.Getter
    @lombok.Setter
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SampleDO {
        private String name;
        private int age;
        private List<String> tags;
        private Map<String, Integer> scores;

        public static SampleDO of(String name, int age, List<String> tags, Map<String, Integer> scores) {
            return new SampleDO(name, age, tags, scores);
        }
    }
}
