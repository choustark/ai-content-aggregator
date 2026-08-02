package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.TwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeTwitterDiscoveryClientTest {

    @Test
    void shouldUseFirstProviderThatReturnsTweets() {
        TwitterProperties properties = properties("scraper", "apify", "rsshub");
        StubProvider scraper = new StubProvider("scraper", List.of(tweet("1")));
        StubProvider apify = new StubProvider("apify", List.of(tweet("2")));

        List<Tweet> tweets = new CompositeTwitterDiscoveryClient(properties, List.of(apify, scraper))
                .discoverTweets("openai");

        assertThat(tweets).extracting(Tweet::getId).containsExactly("1");
        assertThat(scraper.calls).containsExactly("openai");
        assertThat(apify.calls).isEmpty();
    }

    @Test
    void shouldFallbackWhenProviderReturnsEmpty() {
        TwitterProperties properties = properties("scraper", "rsshub");
        StubProvider scraper = new StubProvider("scraper", List.of());
        StubProvider rsshub = new StubProvider("rsshub", List.of(tweet("rss")));

        List<Tweet> tweets = new CompositeTwitterDiscoveryClient(properties, List.of(scraper, rsshub))
                .discoverTweets("sama");

        assertThat(tweets).extracting(Tweet::getId).containsExactly("rss");
        assertThat(scraper.calls).containsExactly("sama");
        assertThat(rsshub.calls).containsExactly("sama");
    }

    @Test
    void shouldFallbackWhenProviderThrowsRetryableOrNonRetryableException() {
        TwitterProperties properties = properties("scraper", "apify", "rsshub");
        StubProvider scraper = new StubProvider("scraper",
                new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, "login expired"));
        StubProvider apify = new StubProvider("apify",
                new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "quota limited"));
        StubProvider rsshub = new StubProvider("rsshub", List.of(tweet("fallback")));

        List<Tweet> tweets = new CompositeTwitterDiscoveryClient(properties, List.of(scraper, apify, rsshub))
                .discoverTweets("karpathy");

        assertThat(tweets).extracting(Tweet::getId).containsExactly("fallback");
        assertThat(scraper.calls).containsExactly("karpathy");
        assertThat(apify.calls).containsExactly("karpathy");
        assertThat(rsshub.calls).containsExactly("karpathy");
    }

    @Test
    void shouldUseLegacySingleProviderWhenProviderChainIsEmpty() {
        TwitterProperties properties = new TwitterProperties();
        properties.setDiscoveryProvider("apify");
        StubProvider apify = new StubProvider("apify", List.of(tweet("legacy")));

        List<Tweet> tweets = new CompositeTwitterDiscoveryClient(properties, List.of(apify))
                .discoverTweets("openai");

        assertThat(tweets).extracting(Tweet::getId).containsExactly("legacy");
    }

    @Test
    void shouldThrowWhenNoConfiguredProviderIsRegistered() {
        TwitterProperties properties = properties("missing");

        CompositeTwitterDiscoveryClient client = new CompositeTwitterDiscoveryClient(properties, List.of());

        assertThatThrownBy(() -> client.discoverTweets("openai"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("missing");
    }

    private static TwitterProperties properties(String... providers) {
        TwitterProperties properties = new TwitterProperties();
        properties.setDiscoveryProviders(List.of(providers));
        return properties;
    }

    private static Tweet tweet(String id) {
        return Tweet.builder().id(id).content("content-" + id).build();
    }

    private static final class StubProvider implements NamedTwitterDiscoveryProvider {
        private final String providerName;
        private final List<Tweet> tweets;
        private final RuntimeException failure;
        private final List<String> calls = new ArrayList<>();

        private StubProvider(String providerName, List<Tweet> tweets) {
            this.providerName = providerName;
            this.tweets = tweets;
            this.failure = null;
        }

        private StubProvider(String providerName, RuntimeException failure) {
            this.providerName = providerName;
            this.tweets = List.of();
            this.failure = failure;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public List<Tweet> discoverTweets(String username) {
            calls.add(username);
            if (failure != null) {
                throw failure;
            }
            return tweets;
        }
    }
}
