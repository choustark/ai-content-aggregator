package com.choucj.aiaggregator.source.github.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * GitHub 客户端配置.
 *
 * <p>注册专用 {@link RestClient} Bean (命名 {@code githubRestClient}), 与 {@code rsshubRestClient}
 * / {@code fxtwitterRestClient} 及默认 RestClient 隔离, 单独绑定 {@link GitHubProperties#getTimeoutSeconds()} 超时.
 *
 * <p>沿用 Story 2.1 RSSHub / Story 2.2a FxTwitter 的配置模式, 保证 source 模块内 HTTP 客户端隔离一致.
 *
 * <p><b>Bean 注册总开关:</b> {@code features.github.enabled} / {@code feature-flags.github.enabled}
 * 任一显式为 false 时关闭本配置, 避免 GitHub disabled 场景仍注册 HTTP 客户端.
 */
@Configuration
@EnableConfigurationProperties(GitHubProperties.class)
@ConditionalOnProperty(
        name = {"features.github.enabled", "feature-flags.github.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class GitHubConfig {

    /**
     * GitHub 专用 RestClient, 通过 {@link Qualifier} 注入到 {@code GitHubClientImpl}.
     *
     * @param properties GitHub 配置 (提供超时秒数)
     * @return 配置好超时的 RestClient
     */
    @Bean
    @Qualifier("githubRestClient")
    public RestClient githubRestClient(GitHubProperties properties) {
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
