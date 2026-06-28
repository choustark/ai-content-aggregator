package com.choucj.aiaggregator.source.twitter.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RSSHub 客户端配置.
 *
 * <p>注册专用 {@link RestClient} Bean(命名 {@code rsshubRestClient}), 与默认 RestClient 隔离,
 * 单独绑定 {@link RSSHubProperties#getTimeoutSeconds()} 超时, 避免污染全局 HTTP 客户端配置.
 *
 * <p>架构 delta (Story 2.1): 用 Spring 6.1 {@link RestClient}(同步) 替代 architecture.md 原计划的 WebClient(响应式).
 * 同步调用符合本 story 单线程顺序处理的 MVP 模型, 多实例切换用 for 循环直观可控.
 */
@Configuration
@EnableConfigurationProperties(RSSHubProperties.class)
public class RSSHubConfig {

    /**
     * RSSHub 专用 RestClient, 通过 {@link Qualifier} 注入到 {@code RSSHubClient}.
     *
     * @param properties RSSHub 配置(提供超时秒数)
     * @return 配置好超时的 RestClient
     */
    @Bean
    @Qualifier("rsshubRestClient")
    public RestClient rsshubRestClient(RSSHubProperties properties) {
        return RestClient.builder()
                .requestFactory(buildRequestFactory(properties.getTimeoutSeconds()))
                .build();
    }

    private ClientHttpRequestFactory buildRequestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }
}
