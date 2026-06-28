package com.choucj.aiaggregator.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Redis Cluster 配置属性.
 *
 * <p>从 application.yaml 读取 {@code redis.cluster.nodes} 配置,
 * 由 {@link RedisClusterConfig} 通过 {@code @EnableConfigurationProperties} 注册,
 * 不再使用 {@code @Component} 以避免 Bean 重复注册.
 */
@ConfigurationProperties(prefix = "redis.cluster")
@Data
public class RedisClusterProperties {

    private List<Node> nodes;

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
}
