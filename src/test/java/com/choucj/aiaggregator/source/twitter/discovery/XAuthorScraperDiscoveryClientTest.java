package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.source.twitter.config.ScraperProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XAuthorScraperDiscoveryClientTest {

    private static final String BASE_URL = "http://127.0.0.1:3100";

    private ScraperProperties properties;
    private MockRestServiceServer server;
    private XAuthorScraperDiscoveryClient client;

    @BeforeEach
    void setUp() {
        properties = new ScraperProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setPollIntervalMs(100);
        properties.setMaxWaitSeconds(1);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new XAuthorScraperDiscoveryClient(properties, builder.build(), new ObjectMapper());
    }

    @Test
    void shouldBuildScraperJobInputForUsername() {
        properties.setIncludeArticles(true);
        properties.setMaxItemsPerAuthor(3);

        Map<String, Object> input = client.buildInput("openai");

        assertThat(input).containsEntry("usernames", List.of("openai"));
        assertThat(input).containsEntry("includePosts", true);
        assertThat(input).containsEntry("includeArticles", true);
        assertThat(input).containsEntry("maxItemsPerAuthor", 3);
        assertThat(input).containsEntry("proxyConfiguration", Map.of("useApifyProxy", false));
    }

    @Test
    void shouldFetchTweetsThroughLocalJobApi() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-1","status":"queued","resultCount":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-1","status":"completed","resultCount":1}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-1/results?limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "items": [
                            {
                              "id": "2079508890610290965",
                              "url": "https://x.com/vbjby3/status/2079508890610290965",
                              "contentType": "article",
                              "requestedUsername": "vbjby3",
                              "createdAt": "2026-07-21T10:08:49.000Z",
                              "article": {
                                "title": "Article title",
                                "body": "Article body",
                                "images": [{"src": "https://img.example/a.jpg"}]
                              },
                              "metrics": {"replyCount": 1, "retweetCount": 2, "likeCount": 3},
                              "links": ["https://example.com"]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Tweet> tweets = client.discoverTweets("@vbjby3");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getId()).isEqualTo("2079508890610290965");
        assertThat(tweet.getAuthor()).isEqualTo("@vbjby3");
        assertThat(tweet.getContent()).isEqualTo("Article body");
        assertThat(tweet.getSummary()).isEqualTo("Article title");
        assertThat(tweet.getReplyCount()).isEqualTo(1);
        assertThat(tweet.getRetweetCount()).isEqualTo(2);
        assertThat(tweet.getLikeCount()).isEqualTo(3);
        assertThat(tweet.getImageUrls()).containsExactly("https://img.example/a.jpg");
        assertThat(tweet.getMedia()).hasSize(1);
        assertThat(tweet.getLinks()).containsExactly("https://example.com");
        server.verify();
    }

    @Test
    void shouldMapSessionFailureJobToNonRetryable() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-2","status":"queued","resultCount":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "job": {
                            "id": "job-2",
                            "status": "failed",
                            "error": {"code": "LOGIN_REQUIRED", "message": "login required"}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverTweets("openai"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("LOGIN_REQUIRED")
                .hasMessageContaining("jobId=job-2")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("login required"));
        server.verify();
    }

    @Test
    void shouldSkipItemWhenBodyMissing() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "1",
                  "url": "https://x.com/openai/status/1",
                  "requestedUsername": "openai",
                  "article": {"title": "No body"}
                }
                """), "openai");

        assertThat(tweet).isNull();
    }
}
