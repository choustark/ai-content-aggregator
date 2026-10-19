package com.choucj.aiaggregator.source.twitter.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.util.StringUtils;

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
        return buildRestClient(RestClient.builder(), properties);
    }

    RestClient buildRestClient(RestClient.Builder builder, ScraperProperties properties) {
        builder.requestFactory(buildRequestFactory(properties.getTimeoutSeconds()));
        configureAuthentication(builder, properties);
        return builder.build();
    }

    void configureAuthentication(RestClient.Builder builder, ScraperProperties properties) {
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getApiKey().trim());
        }
    }

    private ClientHttpRequestFactory buildRequestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }
}
