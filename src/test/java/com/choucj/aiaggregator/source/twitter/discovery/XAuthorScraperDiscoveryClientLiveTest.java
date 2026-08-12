package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.config.ScraperProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实端到端 live test: 让 {@link XAuthorScraperDiscoveryClient} 真打本地 x-author-self-scraper
 * 服务 ({@code http://127.0.0.1:3100}), 走完整 {@code /v1/jobs -> /v1/jobs/{id} -> /v1/jobs/{id}/results}
 * 链路, 验证 actor 可用性以及 Java client 与本地 actor 的契约对齐.
 *
 * <p>与 {@link XAuthorScraperDiscoveryClientTest} 的区别: 后者用 {@code MockRestServiceServer} 全 mock,
 * 只验证请求构造/响应解析逻辑; 本测试真正发起 HTTP, 是 {@code E6-LOCAL-APIFY-ACTOR-CHECK} 想要的契约实证.
 *
 * <p>门控策略 (CI 安全):
 * <ul>
 *   <li>{@code @Tag("external")} — surefire 默认 {@code excludedGroups=external}, 普通 {@code mvn test} 不跑.</li>
 *   <li>{@code @BeforeAll} 探 {@code /health}, actor 不在线时 {@code assumeTrue} 跳过, 不算失败.</li>
 * </ul>
 *
 * <p>本地运行:
 * <pre>{@code
 * ./mvnw test -Pexternal-tests -Dtest=XAuthorScraperDiscoveryClientLiveTest
 * # 可选: 覆盖目标用户名 (默认 zhongying14, readiness 已验证有缓存结果)
 * ./mvnw test -Pexternal-tests -Dtest=XAuthorScraperDiscoveryClientLiveTest \
 *   -Dtest.scraper.live.username=elikikii
 * }</pre>
 *
 * <p>注意: 会触发一次低量 live X 抓取 (单作者, maxItemsPerAuthor=20). actor 在线但 X session 失败时,
 * client 会抛 NonRetryableException (代码 RATE_LIMITED/LOGIN_EXPIRED 等) — 这属于 readiness 发现, 不是测试 bug.
 */
@Tag("external")
class XAuthorScraperDiscoveryClientLiveTest {

    private static final String BASE_URL = "http://127.0.0.1:3100";
    private static final String DEFAULT_USERNAME = "zhongying14";

    private static String targetUsername;
    private static RestClient restClient;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void beforeAll() {
        // actor 不在线则跳过整组测试 (而非失败)
        assumeTrue(isActorHealthy(),
                "本地 x-author-self-scraper 未运行于 " + BASE_URL + "/health — 启动后重跑, 或忽略此 live test");

        targetUsername = System.getProperty("test.scraper.live.username", DEFAULT_USERNAME);
        restClient = RestClient.builder().build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldDiscoverRealTweetsViaLocalActor() {
        ScraperProperties properties = newScraperProperties();
        XAuthorScraperDiscoveryClient client =
                new XAuthorScraperDiscoveryClient(properties, restClient, objectMapper);

        List<Tweet> tweets = client.discoverTweets(targetUsername);

        assertThat(tweets)
                .as("username=%s 应至少返回一条非空推文 (若失败检查 actor session/代理)", targetUsername)
                .isNotEmpty();

        assertThat(tweets).allSatisfy(tweet -> {
            assertThat(tweet.getId()).isNotBlank();
            assertThat(tweet.getUrl()).contains("/status/");
            assertThat(tweet.getAuthor()).isEqualTo("@" + targetUsername);
            assertThat(tweet.getContent()).isNotBlank();
        });
    }

    @Test
    void shouldPreserveMediaOrQuoteFromLocalActor() {
        // readiness 全量扫描复现: zhongying14 时间线至少包含 media (photo/video) 或 quotedTweetUrl.
        ScraperProperties properties = newScraperProperties();
        XAuthorScraperDiscoveryClient client =
                new XAuthorScraperDiscoveryClient(properties, restClient, objectMapper);

        List<Tweet> tweets = client.discoverTweets(targetUsername);

        assertThat(tweets).isNotEmpty();
        boolean hasMedia = tweets.stream().anyMatch(t -> !t.getMedia().isEmpty());
        boolean hasQuote = tweets.stream().anyMatch(t -> StringUtils.hasText(t.getQuotedTweetUrl()));
        assertThat(hasMedia || hasQuote)
                .as("readiness 复现: 时间线应至少包含 media 或 quotedTweetUrl (got media=%s, quote=%s)",
                        hasMedia, hasQuote)
                .isTrue();
    }

    private static ScraperProperties newScraperProperties() {
        ScraperProperties properties = new ScraperProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setIncludePosts(true);
        properties.setIncludeArticles(false);
        properties.setIncludeReplies(false);
        properties.setMaxItemsPerAuthor(20);   // readiness 推荐值, 避免 tight-budget AUTHOR_FAILED
        properties.setMaxArticlesPerAuthor(10);
        properties.setMaxScrolls(30);
        properties.setScrollDelayMs(1800);
        properties.setPollIntervalMs(1000);
        properties.setMaxWaitSeconds(180);
        return properties;
    }

    private static boolean isActorHealthy() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + "/health").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            try (var is = conn.getInputStream()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return body.contains("\"status\":\"ok\"");
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
