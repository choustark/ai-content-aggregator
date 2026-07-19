package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.config.ApifyTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("external")
@SpringBootTest(properties = {
        "twitter.discovery-provider=apify",
        "apify.twitter.actor-id=parseforge~x-com-scraper",
        "apify.twitter.handle-field=usernames",
        "apify.twitter.use-search-terms=false",
        "apify.twitter.max-tweets-per-account=5",
        "features.github.enabled=false",
        "feature-flags.github.enabled=false",
        "wechat.mp.enabled=false",
        "wechat.mp.publishing.batch-enabled=false",
        "schedule.enabled=false"
})
class ApifyDiscoveryIntegrationTest {

    @Autowired
    private ApifyTwitterProperties properties;

    @Autowired
    private ApifyDiscoveryClient client;

    @Test
    void shouldDiscoverTweetsFromParseforgeActor() {
        assumeTrue(StringUtils.hasText(properties.getToken()), "APIFY_API_TOKEN not configured");

        List<Tweet> tweets = client.discoverTweets("OpenAI");

        assertThat(tweets).isNotEmpty();
        assertThat(tweets).hasSizeLessThanOrEqualTo(5);
        assertThat(tweets)
                .allSatisfy(tweet -> {
                    assertThat(tweet.getId()).isNotBlank();
                    assertThat(tweet.getUrl()).contains("/status/");
                    assertThat(tweet.getAuthor()).isEqualTo("@OpenAI");
                    assertThat(tweet.getContent()).isNotBlank();
                });
    }
}
