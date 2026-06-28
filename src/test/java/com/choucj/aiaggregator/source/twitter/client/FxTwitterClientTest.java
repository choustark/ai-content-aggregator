package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Story 2.2a {@link FxTwitterClient} 单测 — mock RestClient 覆盖异常映射与字段提取.
 */
@ExtendWith(MockitoExtension.class)
class FxTwitterClientTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private FxTwitterClient client;
    private FxTwitterProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new FxTwitterProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(15);
        properties.setInstance("https://api.fxtwitter.com");
        client = new FxTwitterClient(properties, restClient, objectMapper);
    }

    @Test
    void shouldReturnEnrichedTweetWhenResponseHasTweetNode() {
        String body = """
                {
                  "tweet": {
                    "text": "Hello world",
                    "replies": 5,
                    "retweets": 10,
                    "likes": 100,
                    "media": {
                      "photos": [
                        {"url": "https://example.com/1.jpg"},
                        {"url": "https://example.com/2.jpg"}
                      ]
                    }
                  }
                }
                """;
        stubReturn(body);

        Tweet result = client.fetchTweetDetail("12345");

        assertThat(result.getId()).isEqualTo("12345");
        assertThat(result.getContent()).isEqualTo("Hello world");
        assertThat(result.getReplyCount()).isEqualTo(5);
        assertThat(result.getRetweetCount()).isEqualTo(10);
        assertThat(result.getLikeCount()).isEqualTo(100);
        assertThat(result.getImageUrls()).containsExactly(
                "https://example.com/1.jpg", "https://example.com/2.jpg");
    }

    @Test
    void shouldReturnEmptyImagesWhenNoMedia() {
        String body = """
                {"tweet": {"text": "no media", "replies": 0, "retweets": 0, "likes": 0}}
                """;
        stubReturn(body);

        Tweet result = client.fetchTweetDetail("1");

        assertThat(result.getImageUrls()).isEmpty();
        assertThat(result.getContent()).isEqualTo("no media");
    }

    @Test
    void shouldReturnZeroInteractionsWhenFieldsMissing() {
        String body = """
                {"tweet": {"text": "minimal"}}
                """;
        stubReturn(body);

        Tweet result = client.fetchTweetDetail("1");

        assertThat(result.getReplyCount()).isZero();
        assertThat(result.getRetweetCount()).isZero();
        assertThat(result.getLikeCount()).isZero();
    }

    @Test
    void shouldThrowNonRetryableOn404() {
        stubThrow(HttpClientErrorException.NotFound.create(
                HttpStatusCode.valueOf(404), "Not Found", null, null, null));

        assertThatThrownBy(() -> client.fetchTweetDetail("999"))
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldThrowRetryableOn429() {
        stubThrow(HttpClientErrorException.TooManyRequests.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", null, null, null));

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableOn4xx() {
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(400), "Bad Request"));

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn5xx() {
        stubThrow(new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOnConnectionError() {
        stubThrow(new ResourceAccessException("connect timed out"));

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableWhenBodyEmpty() {
        stubReturn("");

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableWhenJsonInvalid() {
        stubReturn("not-a-json");

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableWhenTweetNodeMissing() {
        stubReturn("{\"foo\": 1}");

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowRetryableWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> client.fetchTweetDetail("1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("已禁用");
    }

    // ============ helper ============

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubReturn(String body) {
        doReturn((RestClient.RequestHeadersUriSpec) requestSpec).when(restClient).get();
        doReturn((RestClient.RequestHeadersSpec) requestSpec).when(requestSpec).uri(anyString());
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubThrow(RuntimeException ex) {
        doReturn((RestClient.RequestHeadersUriSpec) requestSpec).when(restClient).get();
        doReturn((RestClient.RequestHeadersSpec) requestSpec).when(requestSpec).uri(anyString());
        when(requestSpec.retrieve()).thenThrow(ex);
    }
}
