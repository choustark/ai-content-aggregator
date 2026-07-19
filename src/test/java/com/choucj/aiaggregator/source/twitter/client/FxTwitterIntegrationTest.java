package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Story 2.2a {@link FxTwitterClient} 集成测试 — 调用真实 FxTwitter 公共实例.
 *
 * <p>仅当环境变量 {@code FXTWITTER_INTEGRATION_TEST=true} 且 {@code https://api.fxtwitter.com} 可达时运行,
 * 否则 {@link BeforeAll} {@code assumeTrue} 跳过.
 */
@org.junit.jupiter.api.Tag("external")
class FxTwitterIntegrationTest {

    private static final String INSTANCE = System.getenv()
            .getOrDefault("FXTWITTER_INSTANCE", "https://api.fxtwitter.com");

    /** 已知存在的历史推文 ID(任一稳定的公开推文). */
    private static final String SAMPLE_TWEET_ID =
            System.getenv().getOrDefault("FXTWITTER_SAMPLE_TWEET_ID", "20");

    @BeforeAll
    static void requireNetwork() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("FXTWITTER_INTEGRATION_TEST"));
        Assumptions.assumeTrue(enabled && isInstanceReachable(),
                "跳过: 未设置 FXTWITTER_INTEGRATION_TEST=true 或 " + INSTANCE + " 不可达");
    }

    @Test
    void shouldFetchRealTweetDetail() {
        FxTwitterProperties properties = new FxTwitterProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(15);
        properties.setInstance(INSTANCE);

        RestClient restClient = RestClient.builder().build();
        FxTwitterClient client = new FxTwitterClient(properties, restClient, new ObjectMapper());

        Tweet tweet = client.fetchTweetDetail(SAMPLE_TWEET_ID);

        org.assertj.core.api.Assertions.assertThat(tweet.getId()).isEqualTo(SAMPLE_TWEET_ID);
        org.assertj.core.api.Assertions.assertThat(tweet.getContent()).isNotNull();
        System.out.printf("补全成功: id=%s, replies=%d, retweets=%d, likes=%d%n",
                tweet.getId(), tweet.getReplyCount(), tweet.getRetweetCount(), tweet.getLikeCount());
    }

    private static boolean isInstanceReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(INSTANCE).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
