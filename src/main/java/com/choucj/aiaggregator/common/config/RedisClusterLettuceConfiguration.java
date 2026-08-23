package com.choucj.aiaggregator.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Lettuce-based Redis Cluster 连接配置 — 业务键值访问入口.
 *
 * <p><b>双客户端共存策略(架构 Delta,Story 1.5a 引入):</b>
 * <table border="1">
 *   <tr><th>客户端</th><th>配置类</th><th>用途</th><th>Bean 类型</th></tr>
 *   <tr><td>Jedis</td><td>{@link RagEmbeddingInfrastructureConfig}</td>
 *       <td>standalone Redis-Stack 向量检索</td><td>{@code embeddingUnifiedJedis}</td></tr>
 *   <tr><td>Lettuce</td><td>本类(Story 1.5a)</td>
 *       <td>业务键值(缓存/任务队列/计数/幂等)</td>
 *       <td>{@link RedisConnectionFactory} + {@link StringRedisTemplate}</td></tr>
 * </table>
 *
 * <p><b>两套 Redis 物理隔离:</b> 业务侧通过 {@link StringRedisTemplate} 抽象访问 Redis Cluster,
 * RAG embedding 通过 {@link RagEmbeddingInfrastructureConfig} 中的 {@code embeddingUnifiedJedis}
 * 访问 standalone Redis-Stack. 两者 host/port/password 均独立配置.
 *
 * <p><b>条件装配:</b>
 * {@code @ConditionalOnProperty(prefix="spring.data.redis.cluster", name="enabled",
 * matchIfMissing=true)} — 默认开启,与 Story 1.1 Jedis 路径条件一致;
 * 关闭时业务侧注入 {@link StringRedisTemplate} 会失败(prod/test 强制开启).
 *
 * <p>引用源: Story 1.5a 创建;被 Story 1.5b(JSON 序列化)、1.6(任务队列)、2.x(数据缓存)消费.
 *
 * <p><b>Story 1.5b 扩展:</b>
 * <ul>
 *   <li>新增 {@code RedisTemplate<String, Object>} Bean —— 服务复杂对象 JSON 序列化
 *       (TaskOrderDO / RssArticleDO / LlmResponseDO),使用 {@link GenericJackson2JsonRedisSerializer}</li>
 *   <li>加 {@link Slf4j} —— 在 {@code redisConnectionFactory} 初始化末尾打 {@code log.info}
 *       输出节点列表,与 Story 1.1 {@link RedisClusterConfig} 对称(闭环 Story 1.5a CR N1)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RedisClusterProperties.class)
@ConditionalOnProperty(prefix = "spring.data.redis.cluster", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisClusterLettuceConfiguration {

    /**
     * Lettuce 连接工厂 — 集群模式.
     *
     * <p>读取 Story 1.1 已注册的 {@link RedisClusterProperties}(自定义 prefix
     * {@code redis.cluster}),构造 Spring Data Redis 的 {@link RedisClusterConfiguration},
     * 由 {@link LettuceConnectionFactory} 包装.
     *
     * <p><b>不使用 {@code @Primary}:</b> Spring Boot 自动配置在集群模式下不会注册
     * 默认 {@code RedisConnectionFactory}(因 {@code spring.data.redis.cluster.nodes}
     * 已存在,自动配置会让位给用户配置),无 Bean 竞争.
     *
     * @param clusterProperties         自定义集群节点列表(Story 1.1 Properties)
     * @return 已初始化的 {@link LettuceConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            RedisClusterProperties clusterProperties) {

        List<RedisClusterProperties.Node> nodes = clusterProperties.getNodes();
        Assert.notEmpty(nodes,
                "redis.cluster.nodes must be configured (e.g. in application-dev.yml) "
                        + "when spring.data.redis.cluster.enabled=true");

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();
        for (RedisClusterProperties.Node node : nodes) {
            clusterConfig.clusterNode(node.getHost(), node.getPort());
        }
        if (clusterProperties.getUsername() != null && !clusterProperties.getUsername().isBlank()) {
            clusterConfig.setUsername(clusterProperties.getUsername());
        }
        if (clusterProperties.getPassword() != null && !clusterProperties.getPassword().isBlank()) {
            clusterConfig.setPassword(RedisPassword.of(clusterProperties.getPassword()));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig, clientConfiguration(clusterProperties));
        factory.setValidateConnection(true);
        factory.afterPropertiesSet();

        String nodeList = nodes.stream()
                .map(n -> n.getHost() + ":" + n.getPort())
                .collect(Collectors.joining(","));
        log.info("Lettuce RedisConnectionFactory 初始化完成, 节点: {}", nodeList);

        return factory;
    }

    private LettuceClientConfiguration clientConfiguration(RedisClusterProperties clusterProperties) {
        RedisClusterProperties.Client client = clusterProperties.getClient();

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(client.getConnectTimeout())
                .keepAlive(client.isKeepAlive())
                .tcpNoDelay(true)
                .build();

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enableAllAdaptiveRefreshTriggers()
                .enablePeriodicRefresh(client.getTopologyRefreshPeriod())
                .dynamicRefreshSources(true)
                .closeStaleConnections(true)
                .build();

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(TimeoutOptions.enabled(client.getCommandTimeout()))
                .topologyRefreshOptions(topologyRefreshOptions)
                .autoReconnect(true)
                .validateClusterNodeMembership(false)
                .build();

        return LettuceClientConfiguration.builder()
                .commandTimeout(client.getCommandTimeout())
                .clientOptions(clientOptions)
                .build();
    }

    /**
     * Story 1.5b: 复杂对象 JSON 序列化路径.
     *
     * <p>与 {@link StringRedisTemplate} 共存 — 后者服务纯字符串(任务队列 taskId / 计数器),
     * 本 Bean 服务 DO 缓存(TaskOrderDO / RssArticleDO / LlmResponseDO / Tweet).
     *
     * <p><b>序列化器配置:</b>
     * <ul>
     *   <li>Key / HashKey: {@link StringRedisSerializer} —— 与 {@link StringRedisTemplate} 一致,
     *       保证两类 Template 的键可互读</li>
     *   <li>Value / HashValue: {@link GenericJackson2JsonRedisSerializer} ——
     *       在 JSON 中包含 {@code @class} 元信息标识类型,反序列化时按此加载类;
     *       一个 Bean 服务所有 DO 类型,避免为每种 DO 配置独立 Template(违反 DRY)</li>
     * </ul>
     *
     * <p><b>Java 8 时间类型支持:</b>
     * 显式配置 {@link ObjectMapper} 注册 {@link JavaTimeModule},
     * 支持 {@link java.time.LocalDateTime} / {@link java.time.LocalDate} 等类型序列化
     * (修复 {@code Tweet.publishedAt} 序列化失败问题).
     * 禁用 {@code WRITE_DATES_AS_TIMESTAMPS} 以 ISO-8601 格式输出日期.
     *
     * <p><b>反序列化风险:</b>
     * {@code @class} 默认允许反序列化任意类;但 Redis 数据源是内部写入(不来自外部用户),
     * 无 SSRF/反序列化攻击面. 若未来 Redis 暴露给外部,改用
     * {@code Jackson2JsonRedisSerializer<SpecificType>}(无 {@code @class}).
     *
     * <p><b>Bean 共存:</b>
     * {@link RedisTemplate} 与 {@link StringRedisTemplate}(后者继承前者但 Spring 装配按具体类型)
     * 无 {@code @Primary} 冲突,业务侧按类型注入即可.
     *
     * @param factory 本类提供的集群 ConnectionFactory
     * @return 配置好 JSON 序列化器的 {@code RedisTemplate<String, Object>}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 配置 ObjectMapper 支持 Java 8 时间类型
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 显式声明 StringRedisTemplate,绑定自定义 ConnectionFactory.
     *
     * <p>Spring Boot 自动配置在 {@link RedisConnectionFactory} Bean 已存在时会
     * 自动注册一个 StringRedisTemplate,但绑定的是它自己的 ConnectionFactory;
     * 显式声明确保用本类的集群 ConnectionFactory.
     *
     * @param factory 本类提供的集群 ConnectionFactory
     * @return 绑定集群 ConnectionFactory 的 StringRedisTemplate
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
