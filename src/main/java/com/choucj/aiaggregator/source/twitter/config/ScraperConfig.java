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
 * x-author-self-scraper 本地服务客户端配置.
 */
@Configuration
@EnableConfigurationProperties(ScraperProperties.class)
public class ScraperConfig {

    @Bean
    @Qualifier("xAuthorScraperRestClient")
    public RestClient xAuthorScraperRestClient(ScraperProperties properties) {
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
