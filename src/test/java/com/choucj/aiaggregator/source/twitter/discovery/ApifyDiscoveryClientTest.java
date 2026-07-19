package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.source.twitter.config.ApifyTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApifyDiscoveryClientTest {

    private ApifyTwitterProperties properties;
    private ApifyDiscoveryClient client;

    @BeforeEach
    void setUp() {
        properties = new ApifyTwitterProperties();
        properties.setToken("test-token");
        client = new ApifyDiscoveryClient(properties, mock(RestClient.class), new ObjectMapper());
    }

    @Test
    void shouldParseCommonApifyTweetFields() {
        String body = """
                [
                  {
                    "id": "12345",
                    "url": "https://twitter.com/OpenAI/status/12345",
                    "username": "OpenAI",
                    "text": "hello from apify",
                    "createdAt": "2026-07-18T10:00:00Z",
                    "replyCount": 3,
                    "retweetCount": 4,
                    "likeCount": 5,
                    "images": ["https://img.example/a.jpg"]
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "OpenAI");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getId()).isEqualTo("12345");
        assertThat(tweet.getAuthor()).isEqualTo("@OpenAI");
        assertThat(tweet.getContent()).isEqualTo("hello from apify");
        assertThat(tweet.getSummary()).isEqualTo("hello from apify");
        assertThat(tweet.getReplyCount()).isEqualTo(3);
        assertThat(tweet.getRetweetCount()).isEqualTo(4);
        assertThat(tweet.getLikeCount()).isEqualTo(5);
        assertThat(tweet.getImageUrls()).containsExactly("https://img.example/a.jpg");
    }

    @Test
    void shouldExtractIdFromUrlWhenIdMissing() {
        String body = """
                [
                  {
                    "tweetUrl": "https://twitter.com/OpenAI/status/67890?s=20",
                    "userName": "@OpenAI",
                    "fullText": "url only"
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "OpenAI");

        assertThat(tweets).hasSize(1);
        assertThat(tweets.get(0).getId()).isEqualTo("67890");
        assertThat(tweets.get(0).getAuthor()).isEqualTo("@OpenAI");
    }

    @Test
    void shouldFailFastWhenTokenMissing() {
        properties.setToken("");

        assertThatThrownBy(() -> client.discoverTweets("OpenAI"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("apify.twitter.token");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldMapRestClientResponseExtractionFailureToRetryable() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        client = new ApifyDiscoveryClient(properties, restClient, new ObjectMapper());

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString())).thenReturn(bodySpec);
        when(bodySpec.body(org.mockito.ArgumentMatchers.any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RestClientException("extract failed"));

        assertThatThrownBy(() -> client.discoverTweets("OpenAI"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("Apify 响应读取失败");
    }

    @Test
    void shouldKeepActorIdTildeUnescapedInRunUrl() {
        properties.setActorId("apidojo/tweet-scraper");

        String url = client.buildRunUrl();

        assertThat(url).contains("/v2/acts/apidojo~tweet-scraper/run-sync-get-dataset-items");
        assertThat(url).doesNotContain("apidojo%7Etweet-scraper");
    }

    @Test
    void shouldBuildSearchTermInputByDefault() {
        properties.setMaxTweetsPerAccount(50);
        properties.setUseSearchTerms(true);

        Map<String, Object> input = client.buildInput("@OpenAI");

        assertThat(input).containsEntry("searchTerms", List.of("from:OpenAI"));
        assertThat(input).containsEntry("maxItems", 50);
        assertThat(input).containsEntry("sort", "Latest");
        assertThat(input).doesNotContainKey("twitterHandles");
    }

    @Test
    void shouldBuildHandleInputWhenSearchTermsDisabled() {
        properties.setUseSearchTerms(false);

        Map<String, Object> input = client.buildInput("OpenAI");

        assertThat(input).containsEntry("usernames", List.of("OpenAI"));
        assertThat(input).doesNotContainKey("searchTerms");
    }

    @Test
    void shouldParseParseforgeActorFields() {
        String body = """
                [
                  {
                    "id": "1940000000000000000",
                    "twitterUrl": "https://x.com/OpenAI/status/1940000000000000000",
                    "fullText": "parseforge output",
                    "createdAt": "2026-07-18T10:00:00.000Z",
                    "replyCount": 1,
                    "retweetCount": 2,
                    "likeCount": 3,
                    "author": {
                      "userName": "OpenAI"
                    },
                    "media": [
                      {
                        "type": "photo",
                        "url": "https://pbs.twimg.com/media/example.jpg"
                      }
                    ]
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "fallback");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getAuthor()).isEqualTo("@OpenAI");
        assertThat(tweet.getContent()).isEqualTo("parseforge output");
        assertThat(tweet.getImageUrls()).containsExactly("https://pbs.twimg.com/media/example.jpg");
    }
}
