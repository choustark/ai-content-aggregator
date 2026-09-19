package com.choucj.aiaggregator.source.github.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.TestSlowOperationRecorder;
import com.choucj.aiaggregator.source.github.config.GitHubProperties;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.choucj.aiaggregator.common.observability.LogEventCapture;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Story 4.1 AC-3 / AC-4 {@link GitHubClientImpl} 单测 — 软失败测试范式 (§1.7).
 *
 * <p>真实 GitHubClientImpl + mock RestClient + 真实 ObjectMapper (解析逻辑走真实路径,
 * 仅 HTTP 层隔离). 覆盖 7 种场景:
 * <ul>
 *   <li>AC-3: happy path(200 + 有效 items)返回 8 字段填充 + readmeUrl = html_url + "#readme"</li>
 *   <li>AC-3: 空 items / 空响应 / JSON 解析失败 返回空列表 (不抛)</li>
 *   <li>AC-4: 异常映射 — 403+X-RateLimit-Remaining:0 Retryable / 429 Retryable /
 *       5xx Retryable / 4xx NonRetryable / ResourceAccessException Retryable</li>
 *   <li>enabled=false 返回空列表</li>
 * </ul>
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GitHubClientImplTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private GitHubClientImpl client;
    private GitHubProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GitHubProperties();
        properties.setEnabled(true);
        properties.getTrending().setLanguage("java");
        properties.getTrending().setLookbackDays(7);
        properties.getTrending().setTopN(10);
        client = new GitHubClientImpl(properties, restClient, objectMapper, TestSlowOperationRecorder.create());
    }

    // ============ AC-3: happy path ============

    @Test
    void shouldReturnReposWhenResponseHasItems() {
        String body = """
                {
                  "items": [
                    {
                      "id": 123456,
                      "full_name": "langchain4j/langchain4j",
                      "description": "Java LLM framework",
                      "language": "Java",
                      "stargazers_count": 2500,
                      "forks_count": 480,
                      "html_url": "https://github.com/langchain4j/langchain4j"
                    }
                  ]
                }
                """;
        stubReturn(body);

        List<GitHubRepo> result = client.fetchTrending();

        assertThat(result).hasSize(1);
        GitHubRepo repo = result.get(0);
        assertThat(repo.getId()).isEqualTo("123456");
        assertThat(repo.getFullName()).isEqualTo("langchain4j/langchain4j");
        assertThat(repo.getName()).isEqualTo("langchain4j");
        assertThat(repo.getDescription()).isEqualTo("Java LLM framework");
        assertThat(repo.getLanguage()).isEqualTo("Java");
        assertThat(repo.getStars()).isEqualTo(2500);
        assertThat(repo.getForks()).isEqualTo(480);
        assertThat(repo.getUrl()).isEqualTo("https://github.com/langchain4j/langchain4j");
        assertThat(repo.getReadmeUrl()).isEqualTo("https://github.com/langchain4j/langchain4j#readme");
    }

    // ============ AC-3: 空响应 / 空 items / 解析失败 ============

    @Test
    void shouldThrowRetryableWhenResponseBodyIsNull() {
        stubReturn(null);
        assertThatThrownBy(() -> client.fetchTrending()).isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldReturnEmptyWhenItemsArrayEmpty() {
        stubReturn("{\"items\":[]}");
        assertThat(client.fetchTrending()).isEmpty();
    }

    @Test
    void shouldThrowRetryableWhenJsonInvalid() {
        stubReturn("not-a-json");
        assertThatThrownBy(() -> client.fetchTrending()).isInstanceOf(RetryableException.class);
    }

    // ============ AC-4: 异常映射 ============

    @Test
    void shouldThrowRetryableOn403RateLimitRemainingZero() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", "0");
        headers.add("X-RateLimit-Reset", "1735689600");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "rate limit exceeded", headers, null, null);
        stubThrow(ex);

        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldThrowRetryableOn429() {
        stubThrow(HttpClientErrorException.TooManyRequests.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", null, null, null));
        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOnBase429() {
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(429), "Too Many Requests"));
        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn403WithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "secondary rate limit", headers, null, null);
        stubThrow(ex);

        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableOn403WithoutRateLimit() {
        // 403 但 X-RateLimit-Remaining 非 0 (e.g., 权限不足) → NonRetryable
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", "50");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "forbidden", headers, null, null);
        stubThrow(ex);

        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldThrowNonRetryableOn4xx() {
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(404), "Not Found"));
        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn5xx() {
        stubThrow(new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));
        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOnConnectionError() {
        stubThrow(new ResourceAccessException("connect timed out"));
        assertThatThrownBy(() -> client.fetchTrending())
                .isInstanceOf(RetryableException.class);
    }

    // ============ 总开关 ============

    @Test
    void shouldReturnEmptyWhenDisabled() {
        properties.setEnabled(false);
        assertThat(client.fetchTrending()).isEmpty();
    }

    @Test
    void shouldEncodeSearchQueryParameters() {
        properties.getTrending().setLanguage("C++");
        String query = client.renderQuery(properties.getTrending(), "2026-07-01");
        URI uri = client.buildSearchUri(query, 10);
        assertThat(query).isEqualTo("created:>2026-07-01 language:C++");
        assertThat(uri.toString()).contains("q=created:%3E2026-07-01%20language:C%2B%2B");
    }

    // ============ fetchReadme ============

    @Test
    void shouldReturnDecodedReadmeOnHappyPath() {
        String readme = "# langchain4j\n\nJava LLM framework.\n";
        stubReturn(buildReadmeBody(readme));

        String result = client.fetchReadme("langchain4j", "langchain4j");

        assertThat(result).isEqualTo(readme);
    }

    @Test
    void shouldReturnNullWhenReadmeNotFound() {
        stubThrow(HttpClientErrorException.NotFound.create(
                HttpStatusCode.valueOf(404), "Not Found", null, null, null));

        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldTruncateWhenReadmeExceedsMaxSize(CapturedOutput output) {
        properties.getReadme().setMaxSizeKb(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            sb.append('a');
        }
        stubReturn(buildReadmeBody(sb.toString()));

        try (var logs = new LogEventCapture(GitHubClientImpl.class)) {
            String result = client.fetchReadme("owner", "repo");

            assertThat(result).hasSize(1024);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("GitHub README 截断");
            });
        }
    }

    @Test
    void shouldTruncateByCodePointWithoutSplittingSupplementaryCharacters() {
        properties.getReadme().setMaxSizeKb(1);
        String readme = "😀".repeat(2048);
        stubReturn(buildReadmeBody(readme));

        String result = client.fetchReadme("owner", "repo");

        assertThat(result.codePointCount(0, result.length())).isEqualTo(1024);
        assertThat(result).isEqualTo("😀".repeat(1024));
    }

    @Test
    void shouldReturnNullWhenReadmeExceedsOneMegabyte() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1024 * 1024 + 1; i++) {
            sb.append('a');
        }
        stubReturn(buildReadmeBody(sb.toString()));

        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldRejectOversizedReadmeBeforeBase64Decode() {
        stubReturn(buildReadmeBody("not-used", "base64", 1024 * 1024 + 1));

        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldReturnNullWhenEncodingIsNotBase64() {
        stubReturn(buildReadmeBody("plain text", "utf-8", 10));

        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldReturnNullWhenContentFieldMissing() {
        stubReturn("{\"name\":\"README.md\",\"encoding\":\"base64\"}");
        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldReturnNullWhenContentFieldBlank() {
        stubReturn("{\"content\":\"   \",\"encoding\":\"base64\",\"size\":0}");
        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldReturnNullWhenBase64DecodeFails() {
        stubReturn("{\"content\":\"!!!not-base64!!!\",\"encoding\":\"base64\",\"size\":18}");
        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldThrowRetryableWhenReadmeBodyEmpty() {
        stubReturn(null);
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn429ForReadme() {
        stubThrow(HttpClientErrorException.TooManyRequests.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", null, null, null));
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn403RateLimitForReadme() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", "0");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "rate limit", headers, null, null);
        stubThrow(ex);
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn403RetryAfterForReadme() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "secondary rate limit", headers, null, null);
        stubThrow(ex);

        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableOn403ForbiddenForReadme() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", "50");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "forbidden", headers, null, null);
        stubThrow(ex);
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableOnOrdinary4xxForReadme() {
        stubThrow(new HttpClientErrorException(HttpStatusCode.valueOf(401), "Unauthorized"));

        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowRetryableOn5xxForReadme() {
        stubThrow(new HttpServerErrorException(HttpStatusCode.valueOf(503), "Service Unavailable"));
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldThrowRetryableOnConnectionErrorForReadme() {
        stubThrow(new ResourceAccessException("connect timed out"));
        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    void shouldWrapUnknownRuntimeExceptionForReadme() {
        stubThrow(new IllegalStateException("unexpected RestClient failure"));

        assertThatThrownBy(() -> client.fetchReadme("owner", "repo"))
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldReturnNullWhenDisabledForReadme() {
        properties.setEnabled(false);
        assertThat(client.fetchReadme("owner", "repo")).isNull();
    }

    @Test
    void shouldEncodeReadmePathSegments() {
        URI uri = client.buildReadmeUri("own er", "re po");
        assertThat(uri.toString()).endsWith("/repos/own%20er/re%20po/readme");
    }

    private String buildReadmeBody(String readmeText) {
        return buildReadmeBody(readmeText, "base64",
                readmeText.getBytes(StandardCharsets.UTF_8).length);
    }

    private String buildReadmeBody(String readmeText, String encoding, int size) {
        String encoded = Base64.getEncoder().encodeToString(
                readmeText.getBytes(StandardCharsets.UTF_8));
        return "{\"content\":\"" + encoded + "\",\"encoding\":\"" + encoding + "\",\"size\":"
                + size + "}";
    }

    // ============ helper ============

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubReturn(String body) {
        doReturn((RestClient.RequestHeadersUriSpec) requestSpec).when(restClient).get();
        doReturn((RestClient.RequestHeadersSpec) requestSpec).when(requestSpec).uri(any(URI.class));
        when(requestSpec.headers(any(Consumer.class))).thenReturn((RestClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubThrow(RuntimeException ex) {
        doReturn((RestClient.RequestHeadersUriSpec) requestSpec).when(restClient).get();
        doReturn((RestClient.RequestHeadersSpec) requestSpec).when(requestSpec).uri(any(URI.class));
        when(requestSpec.headers(any(Consumer.class))).thenReturn((RestClient.RequestHeadersSpec) requestSpec);
        when(requestSpec.retrieve()).thenThrow(ex);
    }
}
