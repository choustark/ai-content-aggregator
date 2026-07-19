package com.choucj.aiaggregator.source.twitter.config;

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
 * Apify Twitter/X 客户端配置.
 */
@Configuration
@ConditionalOnProperty(prefix = "twitter", name = "discovery-provider", havingValue = "apify")
@EnableConfigurationProperties(ApifyTwitterProperties.class)
public class ApifyTwitterConfig {

    @Bean
    @Qualifier("apifyTwitterRestClient")
    public RestClient apifyTwitterRestClient(ApifyTwitterProperties properties) {
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
