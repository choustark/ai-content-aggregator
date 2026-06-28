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
 * FxTwitter 客户端配置.
 *
 * <p>注册专用 {@link RestClient} Bean(命名 {@code fxtwitterRestClient}), 与 {@code rsshubRestClient}
 * 及默认 RestClient 隔离, 单独绑定 {@link FxTwitterProperties#getTimeoutSeconds()} 超时.
 *
 * <p>沿用 Story 2.1 RSSHub 的配置模式, 保证 source/twitter 模块内 HTTP 客户端隔离一致.
 *
 * <p>架构 delta (Story 2.2a): FxTwitter 单实例(无多实例备份), 见 {@link FxTwitterProperties} 决策说明.
 */
@Configuration
@EnableConfigurationProperties({FxTwitterProperties.class, TwitterProperties.class})
public class FxTwitterConfig {

    /**
     * FxTwitter 专用 RestClient, 通过 {@link Qualifier} 注入到 {@code FxTwitterClient}.
     *
     * @param properties FxTwitter 配置(提供超时秒数)
     * @return 配置好超时的 RestClient
     */
    @Bean
    @Qualifier("fxtwitterRestClient")
    public RestClient fxtwitterRestClient(FxTwitterProperties properties) {
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
