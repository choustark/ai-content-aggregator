package com.choucj.aiaggregator.common.repository;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 1.5a AC-5 连通性集成测试 — 端到端验证 Redis Cluster + Repository 链路.
 *
 * <p><b>测试场景(AC-5):</b>
 * <ol>
 *   <li>{@code set + get} 一致性 —— 写入值可被正确读回</li>
 *   <li>{@code delete} 后 {@code get} 返回 null —— 删除语义正确</li>
 *   <li>{@code exists} 反映键存在性 —— 写前 false,写后 true</li>
 *   <li>{@code expire} 后 TTL > 0 —— 过期时间被 Redis 服务端正确记录</li>
 * </ol>
 *
 * <p><b>跳过策略(AC-5 强制要求):</b>
 * 本地无 Redis Cluster 时,CI 不应被阻塞.使用 {@link TestInstance}(PER_CLASS)
 * 让 {@link BeforeAll} 成为实例方法,从而访问 {@link Autowired} 注入的
 * {@link StringRedisTemplate},执行 {@code PING} 探活;失败则
 * {@link Assumptions#assumeTrue(boolean, String)} 跳过全部 4 个测试.
 *
 * <p><b>profile=test:</b> 加载 {@code application-test.yml},集群节点指向
 * {@code redis-cluster-1/2/3:7001/7002/7003}(Docker container name).
 * 本地开发机需手动起 3 节点 Cluster 或调整 host 为 localhost.
 *
 * <p><b>键隔离:</b> 所有测试键以 {@code test:1.5a:connectivity:} 为前缀,
 * {@link AfterEach} 统一清理,避免污染其他测试或后续 CI 运行.
 *
 * <p>引用源: Story 1.5a 创建;Story 1.5b 会扩展为含异常映射的端到端测试.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.Tag("external")
class RedisClusterConnectivityTest {

    private static final String KEY_PREFIX = "test:1.5a:connectivity:";

    @Autowired
    private RedisRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 连通性预检 —— 集群离线时跳过整套测试.
     *
     * <p>用 {@link StringRedisTemplate} 的裸连接执行 {@code PING},
     * 任意异常即视为本地 Cluster 不可用(端口未监听/拓扑未就绪/密码不匹配等).
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
                "本地 Redis Cluster 不可用,跳过 Story 1.5a 连通性集成测试(AC-5: 不阻塞 CI)");
    }

    @AfterEach
    void cleanupKeys() {
        redisTemplate.delete(java.util.List.of(
                KEY_PREFIX + "setget",
                KEY_PREFIX + "delete",
                KEY_PREFIX + "exists",
                KEY_PREFIX + "expire"
        ));
    }

    @Test
    void shouldSetAndThenGetReturnSameValue() {
        String key = KEY_PREFIX + "setget";

        repository.set(key, "hello-cluster");

        assertThat(repository.get(key))
                .as("set 后 get 应返回刚写入的值")
                .isEqualTo("hello-cluster");
    }

    @Test
    void shouldGetReturnNullAfterDelete() {
        String key = KEY_PREFIX + "delete";
        repository.set(key, "to-be-deleted");

        repository.delete(key);

        assertThat(repository.get(key))
                .as("delete 后 get 应返回 null")
                .isNull();
    }

    @Test
    void shouldExistsReflectKeyPresence() {
        String key = KEY_PREFIX + "exists";

        assertThat(repository.exists(key))
                .as("键未写入时 exists 应为 false")
                .isFalse();

        repository.set(key, "now-exists");

        assertThat(repository.exists(key))
                .as("键写入后 exists 应为 true")
                .isTrue();
    }

    @Test
    void shouldExpireSetPositiveTtl() {
        String key = KEY_PREFIX + "expire";
        repository.set(key, "with-ttl");

        repository.expire(key, Duration.ofMinutes(5));

        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds)
                .as("expire 后服务端记录的 TTL 应为正数(秒)")
                .isNotNull()
                .isPositive();
    }
}
