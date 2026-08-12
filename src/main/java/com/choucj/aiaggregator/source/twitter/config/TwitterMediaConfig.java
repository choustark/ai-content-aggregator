package com.choucj.aiaggregator.source.twitter.config;

import com.choucj.aiaggregator.source.twitter.media.MediaDownloadClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * X 媒体下载 RestClient Bean 注册 (Story 7.2).
 *
 * <p>在 {@link TwitterMediaConfig} 中注册, 复用已有的 {@link TwitterMediaProperties}.
 * Bean 名称 {@code mediaDownloadRestClient}, 通过 {@code @Qualifier} 注入 {@link MediaDownloadClient}.
 *
 * <p>超时配置从 {@code twitter.media.download-timeout-seconds} 读取,
 * 同时设置 connectTimeout 和 readTimeout (网络下载两段都适用).
 *
 * <p>集成型开关, 默认关闭: {@code twitter.media.enabled=false} 时本配置类 Bean 不注册,
 * 与 {@link TweetMediaArchiveWriter} 保持一致 (Story 7.1).
 *
 * <p>修复 Review Patch: 添加 @EnableConfigurationProperties 确保 TwitterMediaProperties 注册.
 */
@Configuration
@EnableConfigurationProperties(TwitterMediaProperties.class)
@ConditionalOnProperty(prefix = "twitter.media", name = "enabled", havingValue = "true")
public class TwitterMediaConfig {

    /**
     * X 媒体二进制下载专用 RestClient.
     *
     * <p>与 JSON API 客户端 (rsshubRestClient / fxtwitterRestClient) 隔离:
     * <ul>
     *   <li>JSON 客户端返回结构化数据, 用于反序列化为 Java 对象</li>
     *   <li>本客户端返回原始 byte[], 用于保存图片/媒体文件</li>
     * </ul>
     */
    @Bean("mediaDownloadRestClient")
    public RestClient mediaDownloadRestClient(TwitterMediaProperties properties) {
        int timeoutSeconds = properties.getDownloadTimeoutSeconds();
        return RestClient.builder()
                .requestFactory(buildRequestFactory(timeoutSeconds))
                .build();
    }

    private ClientHttpRequestFactory buildRequestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    /**
     * Story 7.2: 媒体下载客户端. 构造器注入 {@code @Qualifier("mediaDownloadRestClient")} RestClient}.
     */
    @Bean
    public MediaDownloadClient mediaDownloadClient(
            @org.springframework.beans.factory.annotation.Qualifier("mediaDownloadRestClient") RestClient mediaDownloadRestClient,
            TwitterMediaProperties properties) {
        long maxFileSizeBytes = (long) properties.getMaxFileSizeMb() * 1024 * 1024;
        return new MediaDownloadClient(mediaDownloadRestClient, maxFileSizeBytes);
    }
}
