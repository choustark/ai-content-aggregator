package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.observability.TestSlowOperationRecorder;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.RSSHubProperties;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Story 2.1 AC-11 {@link RSSHubClient} 单测 — mock RestClient 覆盖 7 种 HTTP 场景.
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>AC-4 / AC-7: happy path(200 + 有效 JSON)返回 5 字段填充</li>
 *   <li>AC-5: 多实例切换(主 5xx → 备成功) + 全部失败抛 RetryableException</li>
 *   <li>AC-10: 异常映射 429 Retryable / 4xx NonRetryable / 5xx Retryable / 连接异常 Retryable</li>
 *   <li>AC-7: JSON 解析失败返回空列表(不抛) / 空 items / 空响应</li>
 *   <li>AC-8: tweet id 提取(URL 末段 / 纯数字 / 缺失跳过)</li>
 *   <li>AC-9: publishedAt ISO 8601 解析</li>
 *   <li>AC-14: enabled=false 返回空列表</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RSSHubClientTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private RSSHubClient client;
    private RSSHubProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new RSSHubProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(30);
        properties.setInstances(List.of("https://rsshub.app"));
        client = new RSSHubClient(properties, restClient, objectMapper, TestSlowOperationRecorder.create());
    }

    // ============ AC-4 / AC-7: happy path ============

    @Test
    void shouldReturnTweetsWhenResponseHasItems() {
        String body = """
                {
                  "title": "Tweets from @karpathy",
                  "items": [
                    {
                      "id": "https://twitter.com/karpathy/status/1234567890",
                      "title": "AI summary text",
                      "url": "https://twitter.com/karpathy/status/1234567890",
                      "date_published": "2026-06-27T10:30:00Z",
                      "authors": [{"name": "@karpathy"}]
                    }
                  ]
                }
                """;
        stubReturn(body);

        List<Tweet> result = client.discoverTweets("karpathy");

        assertThat(result).hasSize(1);
        Tweet t = result.get(0);
        assertThat(t.getId()).isEqualTo("1234567890");
        assertThat(t.getAuthor()).isEqualTo("@karpathy");
        assertThat(t.getSummary()).isEqualTo("AI summary text");
        assertThat(t.getUrl()).contains("1234567890");
        assertThat(t.getPublishedAt()).isNotNull();
    }

    @Test
    void shouldUseUsernameAsAuthorFallbackWhenAuthorsMissing() {
        String body = """
                {"items":[{"id":"https://twitter.com/x/status/999","url":"https://twitter.com/x/status/999","title":"t"}]}
                """;
        stubReturn(body);

        List<Tweet> result = client.discoverTweets("fallback-user");

        assertThat(result.get(0).getAuthor()).isEqualTo("fallback-user");
    }

    // ============ AC-7: 空响应 / 空 items / 解析失败 ============

    @Test
    void shouldReturnEmptyWhenResponseBodyIsNull() {
        stubReturn(null);
        assertThat(client.discoverTweets("anyone")).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenItemsArrayEmpty() {
        stubReturn("{\"items\":[]}");
        assertThat(client.discoverTweets("anyone")).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenJsonInvalid() {
        stubReturn("not-a-json");
        assertThat(client.discoverTweets("anyone")).isEmpty();
    }

    // ============ AC-8: tweet id 提取 ============

    @Test
    void shouldExtractIdFromNumericIdFieldWhenUrlMissing() {
        String body = """
                {"items":[{"id":9876543210,"title":"t"}]}
                """;
        stubReturn(body);
        List<Tweet> result = client.discoverTweets("anyone");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("9876543210");
    }

    @Test
    void shouldSkipItemWhenIdNotExtractable() {
        String body = """
                {"items":[{"title":"no id"}]}
                """;
        stubReturn(body);
        assertThat(client.discoverTweets("anyone")).isEmpty();
    }

    // ============ AC-10: 4xx / 5xx / 429 / 连接异常 ============

    @Test
    void shouldThrowNonRetryableOn4xx() {
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(404), "Not Found"));
        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldThrowRetryableOn5xxAndAllInstancesFail() {
        stubThrow(new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));
        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldThrowRetryableOn429() {
        stubThrow(HttpClientErrorException.TooManyRequests.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", null, null, null));
        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOnConnectionError() {
        stubThrow(new ResourceAccessException("connect timed out"));
        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(RetryableException.class);
    }

    // ============ AC-5: 多实例切换 ============

    @Test
    void shouldFallbackToNextInstanceWhenPrimary5xx() {
        properties.setInstances(List.of("https://primary.example", "https://backup.example"));
        String body = """
                {"items":[{"id":"https://twitter.com/x/status/1","url":"https://twitter.com/x/status/1","title":"t"}]}
                """;

        // 第一次 retrieve 抛 5xx(primary), 第二次返回 responseSpec(backup) — Mockito sequential stubbing
        stubReturnThenFallback(body,
                new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));

        List<Tweet> result = client.discoverTweets("anyone");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("1");
    }

    @Test
    void shouldThrowRetryableWhenAllInstancesFail() {
        properties.setInstances(List.of("https://a.example", "https://b.example"));
        stubThrow(new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));

        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("全部实例失败");
    }

    @Test
    void shouldNotFallbackOn4xxSinceUsernameErrorSameAcrossInstances() {
        properties.setInstances(List.of("https://a.example", "https://b.example"));
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(404), "Not Found"));

        assertThatThrownBy(() -> client.discoverTweets("anyone"))
                .isInstanceOf(NonRetryableException.class);
    }

    // ============ AC-14: 总开关 ============

    @Test
    void shouldReturnEmptyWhenDisabled() {
        properties.setEnabled(false);
        assertThat(client.discoverTweets("anyone")).isEmpty();
    }

    // ============ helper ============

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubReturn(String body) {
        // doReturn 绕过 RestClient.RequestHeadersUriSpec<?> 通配符 capture 问题
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubReturnThenFallback(String body, RuntimeException firstCallException) {
        doReturn((RestClient.RequestHeadersUriSpec) requestSpec).when(restClient).get();
        doReturn((RestClient.RequestHeadersSpec) requestSpec).when(requestSpec).uri(anyString());
        when(requestSpec.retrieve())
                .thenThrow(firstCallException)
                .thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(body);
    }
}
