package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * x-author-self-scraper 本地服务配置.
 *
 * <p>该服务是本地运行的 Apify SDK/Crawlee Actor 包装 API, HTTP 契约为
 * {@code /v1/jobs -> /v1/jobs/{id} -> /v1/jobs/{id}/results}, 不兼容 Apify 云端
 * {@code /v2/acts/{actorId}/run-sync-get-dataset-items} 端点, 因此独立建模为
 * {@code scraper.*} 命名空间.
 */
@ConfigurationProperties(prefix = "scraper")
@Validated
@Data
public class ScraperProperties {

    /** 本地 scraper API base URL. */
    private String baseUrl = "http://127.0.0.1:3100";

    /** HTTP 连接/读取超时秒数. */
    @Min(value = 1, message = "scraper.timeout-seconds must be positive")
    private int timeoutSeconds = 180;

    /** Job 轮询间隔毫秒. */
    @Min(value = 100, message = "scraper.poll-interval-ms must be at least 100")
    private int pollIntervalMs = 1000;

    /** 单个 Job 最大等待秒数. */
    @Min(value = 1, message = "scraper.max-wait-seconds must be positive")
    private int maxWaitSeconds = 180;

    /** 每个账号最多抓取普通 post 数. */
    @Min(value = 1, message = "scraper.max-items-per-author must be positive")
    private int maxItemsPerAuthor = 50;

    /** 每个账号最多发现 Article 数. */
    @Min(value = 1, message = "scraper.max-articles-per-author must be positive")
    private int maxArticlesPerAuthor = 10;

    /** 是否抓取普通 posts. */
    private boolean includePosts = true;

    /** 是否打开作者 Articles tab 并采集 Article. */
    private boolean includeArticles = false;

    /** 是否抓取 replies timeline. */
    private boolean includeReplies = false;

    /** 最大滚动次数, 控制本地服务请求预算. */
    @Min(value = 1, message = "scraper.max-scrolls must be positive")
    private int maxScrolls = 30;

    /** 滚动间隔毫秒. */
    @Min(value = 1000, message = "scraper.scroll-delay-ms must be at least 1000")
    private int scrollDelayMs = 1800;
}
