package com.choucj.aiaggregator.common.config;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.providers.ClusterConnectionProvider;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Cluster 客户端配置.
 *
 * <p>{@code langchain4j-community-redis-spring-boot-starter} 的自动配置仅支持单节点 Redis,
 * 集群模式需要手动提供 {@link UnifiedJedis} Bean.
 *
 * <p>使用 {@code @Primary} 是因为自动配置在 classpath 上时也会注册一个单节点
 * {@code UnifiedJedis} Bean;当业务代码注入 {@link UnifiedJedis} 时,Spring 需要明确的优先候选,
 * 否则会因冲突报 {@code NoUniqueBeanDefinitionException}.Jedis 6.0.0 中
 * {@code UnifiedJedis(Set, JedisClientConfig)} 已 {@code @Deprecated},
 * 正确做法是通过 {@link ClusterConnectionProvider} 构造,并显式指定
 * {@code maxAttempts}(MOVED/ASK 重试次数)与 {@code maxTotalRetriesDuration}(总超时).
 */
@Configuration
@EnableConfigurationProperties(RedisClusterProperties.class)
@ConditionalOnProperty(prefix = "langchain4j.community.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisClusterConfig {

    @Bean
    @Primary
    public UnifiedJedis unifiedJedis(RedisClusterProperties redisClusterProperties,
                                     RedisEmbeddingStoreProperties properties) {
        Assert.notEmpty(redisClusterProperties.getNodes(),
                "redis.cluster.nodes must be configured (e.g. in application.yaml) when langchain4j.community.redis.enabled=true");

        Set<HostAndPort> nodes = redisClusterProperties.getNodes().stream()
                .map(node -> new HostAndPort(node.getHost(), node.getPort()))
                .collect(Collectors.toSet());

        DefaultJedisClientConfig jedisClientConfig = DefaultJedisClientConfig.builder()
                .user(properties.getUser())
                .password(properties.getPassword())
                .build();

        ClusterConnectionProvider connectionProvider = new ClusterConnectionProvider(nodes, jedisClientConfig);

        return new UnifiedJedis(connectionProvider, 5, Duration.ofSeconds(10L));
    }
}
