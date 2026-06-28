package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.TwscrapeProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.2b {@link TwscrapeClient} 单测 — 覆盖 JSON 解析 + 异常映射.
 *
 * <p>用 {@link Mockito#spy(Object)} override {@link TwscrapeClient#startProcess(List)} 注入
 * mock {@link Process}, 避免触发真实 twscrape 子进程启动. 每个 fetchTweetDetail 测试用
 * {@link #newSpyClient(TwscrapeProperties)} 工厂构造独立 spy 实例.
 */
@ExtendWith(MockitoExtension.class)
class TwscrapeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===================== parseResponse 单测(真实对象, 无 spy) =====================

    @Test
    void shouldParseValidJsonToEnrichedTweet() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);
        String stdout = """
                {
                  "id": "abc",
                  "text": "Hello twscrape",
                  "replyCount": 3,
                  "retweetCount": 7,
                  "likeCount": 42,
                  "photos": [
                    {"url": "https://example.com/1.jpg"},
                    {"url": "https://example.com/2.jpg"}
                  ]
                }
                """;

        Tweet result = client.parseResponse(stdout, "123");

        assertThat(result.getId()).isEqualTo("123");
        assertThat(result.getContent()).isEqualTo("Hello twscrape");
        assertThat(result.getReplyCount()).isEqualTo(3);
        assertThat(result.getRetweetCount()).isEqualTo(7);
        assertThat(result.getLikeCount()).isEqualTo(42);
        assertThat(result.getImageUrls()).containsExactly(
                "https://example.com/1.jpg", "https://example.com/2.jpg");
    }

    @Test
    void shouldUseZeroDefaultsWhenInteractionFieldsMissing() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);
        String stdout = """
                {"text": "minimal"}
                """;

        Tweet result = client.parseResponse(stdout, "1");

        assertThat(result.getReplyCount()).isZero();
        assertThat(result.getRetweetCount()).isZero();
        assertThat(result.getLikeCount()).isZero();
        assertThat(result.getImageUrls()).isEmpty();
    }

    @Test
    void shouldReturnEmptyImagesWhenPhotosMissing() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);
        String stdout = """
                {"text": "no photos", "replyCount": 1}
                """;

        Tweet result = client.parseResponse(stdout, "1");

        assertThat(result.getImageUrls()).isEmpty();
        assertThat(result.getContent()).isEqualTo("no photos");
    }

    @Test
    void shouldThrowNonRetryableWhenStdoutEmpty() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);

        assertThatThrownBy(() -> client.parseResponse("", "1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("stdout 为空");
        assertThatThrownBy(() -> client.parseResponse(null, "1"))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldThrowNonRetryableWhenJsonInvalid() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);

        assertThatThrownBy(() -> client.parseResponse("not-a-json", "1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("JSON 解析失败");
    }

    @Test
    void shouldThrowNonRetryableWhenRootJsonIsNull() {
        TwscrapeClient client = new TwscrapeClient(newProperties(true), objectMapper);

        assertThatThrownBy(() -> client.parseResponse("null", "1"))
                .isInstanceOf(NonRetryableException.class);
    }

    // ===================== fetchTweetDetail 异常映射(用 spy) =====================

    @Test
    void shouldThrowRetryableWhenDisabled() {
        TwscrapeClient client = newSpyClient(newProperties(false));

        assertThatThrownBy(() -> client.fetchTweetDetail("1", "https://twitter.com/x/status/1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("已禁用");
    }

    @Test
    void shouldReturnEnrichedTweetOnHappyPath() throws Exception {
        TwscrapeClient client = newSpyClient(newProperties(true));
        String stdout = """
                {"text": "happy", "replyCount": 2, "retweetCount": 3, "likeCount": 4,
                 "photos": [{"url": "https://img.example/a.jpg"}]}
                """;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(streamOf(stdout));
        when(process.waitFor(120, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        doReturn(process).when(client).startProcess(anyList());

        Tweet result = client.fetchTweetDetail("99", "https://twitter.com/x/status/99");

        assertThat(result.getId()).isEqualTo("99");
        assertThat(result.getContent()).isEqualTo("happy");
        assertThat(result.getReplyCount()).isEqualTo(2);
        assertThat(result.getRetweetCount()).isEqualTo(3);
        assertThat(result.getLikeCount()).isEqualTo(4);
        assertThat(result.getImageUrls()).containsExactly("https://img.example/a.jpg");
    }

    @Test
    void shouldThrowRetryableAndDestroyForciblyOnTimeout() throws Exception {
        TwscrapeClient client = newSpyClient(newProperties(true));
        Process process = mock(Process.class);
        when(process.waitFor(120, TimeUnit.SECONDS)).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        doReturn(process).when(client).startProcess(anyList());

        assertThatThrownBy(() -> client.fetchTweetDetail("1", "https://twitter.com/x/status/1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("超时");

        // 超时路径 + finally 防御性兜底 — destroyForcibly 至少调用 1 次
        verify(process, org.mockito.Mockito.atLeastOnce()).destroyForcibly();
    }

    @Test
    void shouldThrowRetryableOnNonZeroExitCode() throws Exception {
        TwscrapeClient client = newSpyClient(newProperties(true));
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(streamOf("error: no accounts"));
        when(process.waitFor(120, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(2);
        doReturn(process).when(client).startProcess(anyList());

        assertThatThrownBy(() -> client.fetchTweetDetail("1", "https://twitter.com/x/status/1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("退出码非 0");
    }

    @Test
    void shouldThrowNonRetryableOnProcessStartFailure() throws Exception {
        TwscrapeClient client = newSpyClient(newProperties(true));
        doThrow(new IOException("enoent")).when(client).startProcess(anyList());

        assertThatThrownBy(() -> client.fetchTweetDetail("1", "https://twitter.com/x/status/1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("可执行文件不存在");
    }

    @Test
    void shouldThrowNonRetryableWhenStdoutExceedsLimit() throws Exception {
        TwscrapeProperties properties = newProperties(true);
        properties.setTimeoutSeconds(60);
        TwscrapeClient client = newSpyClient(properties);
        // waitFor 完成(exitCode=0)后 readStream 触发 5MB 上限检查
        byte[] big = new byte[6 * 1024 * 1024];
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(big));
        when(process.waitFor(60, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        doReturn(process).when(client).startProcess(anyList());

        assertThatThrownBy(() -> client.fetchTweetDetail("1", "https://twitter.com/x/status/1"))
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    // ============ helper ============

    private static TwscrapeProperties newProperties(boolean enabled) {
        TwscrapeProperties p = new TwscrapeProperties();
        p.setEnabled(enabled);
        p.setTimeoutSeconds(120);
        p.setExecutable("twscrape");
        return p;
    }

    private static TwscrapeClient newSpyClient(TwscrapeProperties properties) {
        return spy(new TwscrapeClient(properties, new ObjectMapper()));
    }

    private static InputStream streamOf(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
