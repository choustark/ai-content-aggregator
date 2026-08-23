package com.choucj.aiaggregator.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Redis Cluster 配置属性.
 *
 * <p>从 application.yaml 读取 {@code redis.cluster.nodes} 配置,
 * 由 {@link RedisClusterLettuceConfiguration} 通过 {@code @EnableConfigurationProperties} 注册,
 * 不再使用 {@code @Component} 以避免 Bean 重复注册.
 */
@ConfigurationProperties(prefix = "redis.cluster")
@Data
public class RedisClusterProperties {

    private List<Node> nodes;

    /**
     * Redis ACL 用户名. 未启用 ACL 时留空.
     */
    private String username;

    /**
     * Redis Cluster 业务存储密码. 与 embedding standalone Redis 密码独立配置.
     */
    private String password;

    /**
     * Lettuce 客户端连接参数.
     */
    private Client client = new Client();

    /**
     * Redis Cluster 单个节点配置.
     */
    @Data
    public static class Node {

        private String host;

        /**
         * 端口号,使用 {@code Integer} 以便在 YAML 漏配时被 Spring 宽松绑定检测出来,
         * 而非静默退化为原始 {@code int} 的默认值 0.
         */
        private Integer port;
    }

    @Data
    public static class Client {

        /**
         * 建连超时.
         */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /**
         * 单条 Redis 命令超时.
         */
        private Duration commandTimeout = Duration.ofSeconds(5);

        /**
         * 是否启用 TCP keepalive, 降低空闲连接被网络层静默断开的概率.
         */
        private boolean keepAlive = true;

        /**
         * 集群拓扑周期刷新间隔.
         */
        private Duration topologyRefreshPeriod = Duration.ofMinutes(5);
    }
}
