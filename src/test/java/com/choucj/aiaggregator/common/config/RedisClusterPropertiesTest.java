package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisClusterProperties} 绑定测试.
 *
 * <p>这是 Story 1.1 遗留类, Story 1.2 复审后补测, 锁定 Node 字段类型 (Integer port)
 * 与多节点绑定行为, 防止未来重构破坏 RedisClusterConfig 的 {@code Assert.notEmpty} 契约
 * (见 review-1.1 N5).
 */
class RedisClusterPropertiesTest {

    @EnableConfigurationProperties(RedisClusterProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldBindMultipleNodesWhenConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "redis.cluster.nodes[0].host", "host1",
                                "redis.cluster.nodes[0].port", "7001",
                                "redis.cluster.nodes[1].host", "host2",
                                "redis.cluster.nodes[1].port", "7002"))))
                .run(ctx -> {
                    RedisClusterProperties props = ctx.getBean(RedisClusterProperties.class);
                    assertThat(props.getNodes()).hasSize(2);
                    assertThat(props.getNodes().get(0).getHost()).isEqualTo("host1");
                    assertThat(props.getNodes().get(0).getPort()).isEqualTo(7001);
                    assertThat(props.getNodes().get(1).getHost()).isEqualTo("host2");
                    assertThat(props.getNodes().get(1).getPort()).isEqualTo(7002);
                });
    }

    @Test
    void shouldLeaveNodesNullWhenNotConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    RedisClusterProperties props = ctx.getBean(RedisClusterProperties.class);
                    // 绑定阶段不抛异常, 由 RedisClusterConfig 的 Assert.notEmpty 在 Bean 创建时快速失败
                    assertThat(props.getNodes()).isNull();
                });
    }
}
