package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.source.twitter.config.TwscrapeProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Story 2.2b {@link TwscrapeClient} 集成测试 — 调用真实 twscrape CLI.
 *
 * <p>仅当环境变量 {@code TWSCRAPE_INTEGRATION_TEST=true} 且 {@code which twscrape} 能找到可执行文件时运行,
 * 否则 {@link BeforeAll} {@code assumeTrue} 跳过. 默认在 CI / 未装 twscrape 的开发机上跳过.
 */
@org.junit.jupiter.api.Tag("external")
class TwscrapeIntegrationTest {

    /** 已知存在的历史公开推文 URL — twscrape tweet 子命令直接吃 URL. */
    private static final String SAMPLE_TWEET_URL =
            System.getenv().getOrDefault("TWSCRAPE_SAMPLE_TWEET_URL",
                    "https://twitter.com/jack/status/20");

    @BeforeAll
    static void requireTwscrapeInstalled() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("TWSCRAPE_INTEGRATION_TEST"));
        Assumptions.assumeTrue(enabled && isTwscrapeOnPath(),
                "跳过: 未设置 TWSCRAPE_INTEGRATION_TEST=true 或 PATH 中找不到 twscrape");
    }

    @Test
    void shouldFetchRealTweetDetail() {
        TwscrapeProperties properties = new TwscrapeProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(120);
        properties.setExecutable("twscrape");

        TwscrapeClient client = new TwscrapeClient(properties, new ObjectMapper());

        Tweet tweet = client.fetchTweetDetail("20", SAMPLE_TWEET_URL);

        org.assertj.core.api.Assertions.assertThat(tweet.getId()).isEqualTo("20");
        org.assertj.core.api.Assertions.assertThat(tweet.getContent()).isNotNull();
        System.out.printf("twscrape 补全成功: id=%s, replies=%d, retweets=%d, likes=%d%n",
                tweet.getId(), tweet.getReplyCount(), tweet.getRetweetCount(), tweet.getLikeCount());
    }

    private static boolean isTwscrapeOnPath() {
        try {
            Process p = new ProcessBuilder("which", "twscrape")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return p.exitValue() == 0 && line != null && !line.isBlank();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
