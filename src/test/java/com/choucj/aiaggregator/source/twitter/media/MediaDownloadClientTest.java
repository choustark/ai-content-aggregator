package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Story 7.2: MediaDownloadClient HTTP 下载与异常映射测试.
 */
class MediaDownloadClientTest {

    private static final String URL = "https://example.com/image.jpg";

    private MockRestServiceServer server;
    private MediaDownloadClient downloadClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        downloadClient = new MediaDownloadClient(builder.build(), 10);
    }

    @Test
    void shouldDownloadBinarySuccessfully_whenUrlIsValid() {
        byte[] expectedBytes = new byte[]{1, 2, 3, 4};
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expectedBytes, MediaType.IMAGE_JPEG)
                        .header("Content-Length", String.valueOf(expectedBytes.length)));

        MediaDownloadClient.DownloadResult result = downloadClient.downloadBinary(URL, "tweet-1", "media-1");

        assertThat(result.bytes()).isEqualTo(expectedBytes);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        server.verify();
    }

    @Test
    void shouldRejectOversizedContentLength_beforeReadingBody() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_JPEG)
                        .header("Content-Length", "11"));

        assertThatThrownBy(() -> downloadClient.downloadBinary(URL, "tweet-1", "media-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("contentLength=11");
        server.verify();
    }

    @Test
    void shouldRejectOversizedBody_whenContentLengthMissing() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[11], MediaType.IMAGE_JPEG));

        assertThatThrownBy(() -> downloadClient.downloadBinary(URL, "tweet-1", "media-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("size>");
        server.verify();
    }

    @Test
    void shouldMapHttpClientError4xxToNonRetryableException() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("not found"));

        assertThatThrownBy(() -> downloadClient.downloadBinary(URL, "tweet-1", "media-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("httpStatus=404")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(URL));
        server.verify();
    }

    @Test
    void shouldMapHttp429ToRetryableException() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> downloadClient.downloadBinary(URL, "tweet-1", "media-1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("httpStatus=429");
        server.verify();
    }

    @Test
    void shouldMapHttpServerError5xxToRetryableException() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("server down"));

        assertThatThrownBy(() -> downloadClient.downloadBinary(URL, "tweet-1", "media-1"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("httpStatus=500");
        server.verify();
    }

    @Test
    void shouldRejectInvalidUrlAsNonRetryable() {
        assertThatThrownBy(() -> downloadClient.downloadBinary("not a url", "tweet-1", "media-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("URL 非法");
    }
}
