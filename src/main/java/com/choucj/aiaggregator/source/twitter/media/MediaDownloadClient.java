package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * Story 7.2: X 媒体 HTTP 二进制下载客户端.
 *
 * <p>使用 Spring {@link RestClient} 执行 GET 请求下载图片/媒体二进制内容.
 * 与 JSON API 客户端 (rsshubRestClient / fxtwitterRestClient) 隔离, 用专用 Bean
 * {@code @Qualifier("mediaDownloadRestClient")}.
 *
 * <p><b>异常映射 (W1+W2):</b>
 * <ul>
 *   <li>4xx (除 429) → {@link NonRetryableException} — 客户端错误, 重试无意义</li>
 *   <li>429 → {@link RetryableException} — 限流, 上层可重试</li>
 *   <li>5xx / 连接错误 / 超时 → {@link RetryableException} — 临时性故障</li>
 *   <li>文件大小超限 → {@link NonRetryableException} — 不应重试</li>
 * </ul>
 *
 * <p><b>日志 (W11/N4):</b> 成功 log.info 含 URL 截断 + 字节数 + 耗时; 失败含 HTTP 状态 + 截断根因.
 * 绝不输出图片字节或完整响应体.
 *
 * <p>引用源: Story 7.2 / architecture.md RestClient 决策 / FxTwitterClient 异常映射模式.
 */
public class MediaDownloadClient {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloadClient.class);

    /** 日志中 sourceUrl 截断长度 (N4). */
    private static final int URL_LOG_MAX_LENGTH = 100;

    /** 日志中根因截断长度 (N4). */
    private static final int CAUSE_LOG_MAX_LENGTH = 200;

    /** HTTP 响应体大小截断日志长度 (N4), 防大响应撑爆日志. */
    private static final int RESPONSE_LOG_MAX_LENGTH = 100;

    private final RestClient restClient;
    private final long maxFileSizeBytes;
    private final Duration downloadTimeout;
    private final SlowOperationRecorder slowOperationRecorder;

    /**
     * @param restClient 专用下载 RestClient Bean (超时由 TwitterMediaProperties.downloadTimeoutSeconds 控制)
     * @param maxFileSizeBytes 单文件大小上限 (bytes), 超过则拒绝下载
     * @param downloadTimeout 下载读超时, 作为慢操作观测的 expectedWait 补偿 —
     *                        媒体下载设计内即可能耗时数十秒, 不补偿会让每次成功下载都刷 slow_operation WARN
     */
    public MediaDownloadClient(RestClient restClient, long maxFileSizeBytes, Duration downloadTimeout,
                               SlowOperationRecorder slowOperationRecorder) {
        this.restClient = restClient;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.downloadTimeout = downloadTimeout == null ? Duration.ZERO : downloadTimeout;
        this.slowOperationRecorder = slowOperationRecorder;
    }

    /**
     * 下载二进制内容.
     *
     * @param url      下载 URL (sourceUrl)
     * @param tweetId  业务标识符 (日志用, W11)
     * @param mediaId  媒体标识符 (日志用, W11)
     * @return 下载结果 (字节数组 + contentType)
     * @throws NonRetryableException 4xx (除 429) / 文件过大 / 内容为 null
     * @throws RetryableException    5xx / 429 / 连接错误 / 超时
     */
    public DownloadResult downloadBinary(String url, String tweetId, String mediaId) {
        long startNanos = System.nanoTime();
        try {
            URI uri = validateHttpUrl(url, tweetId, mediaId);
            DownloadResult result = slowOperationRecorder.observe(
                    Kind.HTTP,
                    Dependency.TWITTER_MEDIA,
                    Operation.DOWNLOAD,
                    downloadTimeout,
                    () -> restClient.get().uri(uri).exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is4xxClientError()) {
                            byte[] safeBody = readErrorBody(response.getBody());
                            throw HttpClientErrorException.create(status, response.getStatusText(),
                                    response.getHeaders(), safeBody, null);
                        }
                        if (status.is5xxServerError()) {
                            byte[] safeBody = readErrorBody(response.getBody());
                            throw HttpServerErrorException.create(status, response.getStatusText(),
                                    response.getHeaders(), safeBody, null);
                        }
                        rejectOversizedContentLength(response.getHeaders(), tweetId, mediaId);
                        byte[] body;
                        try {
                            body = readBounded(response.getBody(), maxFileSizeBytes + 1L);
                        } catch (IOException e) {
                            throw new RetryableException("媒体下载读取失败: tweetId=" + tweetId
                                    + " mediaId=" + mediaId
                                    + " cause=" + TextTruncateUtil.truncateForLog(
                                            TextTruncateUtil.getRootMessage(e), CAUSE_LOG_MAX_LENGTH), e);
                        }
                        if (body.length > maxFileSizeBytes) {
                            throw new NonRetryableException("媒体文件超过大小限制: tweetId=" + tweetId
                                    + " mediaId=" + mediaId + " size>" + maxFileSizeBytes
                                    + " max=" + maxFileSizeBytes, null);
                        }
                        String contentType = response.getHeaders().getContentType() != null
                                ? response.getHeaders().getContentType().toString()
                                : "application/octet-stream";
                        if (body.length == 0) {
                            throw new NonRetryableException("媒体下载返回空内容: tweetId=" + tweetId
                                    + " mediaId=" + mediaId, null);
                        }
                        return new DownloadResult(body, contentType);
                    }));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            log.info("媒体下载成功: tweetId={}, mediaId={}, url={}, size={}bytes, 耗时={}ms",
                    tweetId, mediaId,
                    TextTruncateUtil.truncateForLog(url, URL_LOG_MAX_LENGTH),
                    result.bytes().length, elapsedMs);

            return result;
        } catch (NonRetryableException | RetryableException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            // W1+W2: Spring 框架异常统一包装
            int status = e.getStatusCode().value();
            if (status == 429) {
                throw new RetryableException("媒体下载被限流: tweetId=" + tweetId
                        + " mediaId=" + mediaId + " httpStatus=429", e);
            }
            throw new NonRetryableException("媒体下载客户端错误: tweetId=" + tweetId
                    + " mediaId=" + mediaId + " httpStatus=" + status
                    + " cause=" + TextTruncateUtil.truncateForLog(getSafeBody(e), RESPONSE_LOG_MAX_LENGTH), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException("媒体下载服务端错误: tweetId=" + tweetId
                    + " mediaId=" + mediaId + " httpStatus=" + e.getStatusCode().value()
                    + " cause=" + TextTruncateUtil.truncateForLog(getSafeBodyForServer(e), RESPONSE_LOG_MAX_LENGTH), e);
        } catch (ResourceAccessException e) {
            throw new RetryableException("媒体下载连接失败/超时: tweetId=" + tweetId
                    + " mediaId=" + mediaId
                    + " cause=" + TextTruncateUtil.truncateForLog(
                            TextTruncateUtil.getRootMessage(e), CAUSE_LOG_MAX_LENGTH), e);
        } catch (RestClientException e) {
            throw new RetryableException("媒体下载客户端异常: tweetId=" + tweetId
                    + " mediaId=" + mediaId
                    + " cause=" + TextTruncateUtil.truncateForLog(
                            TextTruncateUtil.getRootMessage(e), CAUSE_LOG_MAX_LENGTH), e);
        } catch (RuntimeException e) {
            // W1+W2: 任何未预期 RuntimeException → Retryable (网络层面通常可重试)
            throw new RetryableException("媒体下载未知错误: tweetId=" + tweetId
                    + " mediaId=" + mediaId
                    + " cause=" + TextTruncateUtil.truncateForLog(
                            TextTruncateUtil.getRootMessage(e), CAUSE_LOG_MAX_LENGTH), e);
        }
    }

    private URI validateHttpUrl(String url, String tweetId, String mediaId) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                    && !"https".equals(scheme.toLowerCase(Locale.ROOT)))) {
                throw new NonRetryableException("媒体下载 URL 非 http(s): tweetId=" + tweetId
                        + " mediaId=" + mediaId, null);
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new NonRetryableException("媒体下载 URL 非法: tweetId=" + tweetId
                    + " mediaId=" + mediaId
                    + " cause=" + TextTruncateUtil.truncateForLog(
                            TextTruncateUtil.getRootMessage(e), CAUSE_LOG_MAX_LENGTH), e);
        }
    }

    private void rejectOversizedContentLength(HttpHeaders headers, String tweetId, String mediaId) {
        long contentLength = headers.getContentLength();
        if (contentLength > maxFileSizeBytes) {
            throw new NonRetryableException("媒体文件超过大小限制: tweetId=" + tweetId
                    + " mediaId=" + mediaId + " contentLength=" + contentLength
                    + " max=" + maxFileSizeBytes, null);
        }
    }

    private static byte[] readBounded(InputStream inputStream, long maxBytes) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            int allowed = read;
            if (total > maxBytes) {
                allowed = (int) (read - (total - maxBytes));
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                }
                break;
            }
            output.write(buffer, 0, allowed);
        }
        return output.toByteArray();
    }

    private static byte[] readErrorBody(InputStream inputStream) {
        try {
            return readBounded(inputStream, RESPONSE_LOG_MAX_LENGTH + 1L);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * 安全提取 HTTP 客户端错误响应体 (避免 NullPointerException).
     * 返回截断后的响应体用于日志 (N4), 不用于异常 message.
     */
    private static String getSafeBody(HttpClientErrorException e) {
        try {
            String body = e.getResponseBodyAsString();
            return body != null ? body : "(empty response body)";
        } catch (Exception ex) {
            return "(response body not readable)";
        }
    }

    /**
     * 安全提取 HTTP 服务端错误响应体 (避免 NullPointerException).
     */
    private static String getSafeBodyForServer(HttpServerErrorException e) {
        try {
            String body = e.getResponseBodyAsString();
            return body != null ? body : "(empty response body)";
        } catch (Exception ex) {
            return "(response body not readable)";
        }
    }

    /**
     * 下载结果.
     *
     * @param bytes       下载的二进制内容
     * @param contentType Content-Type 响应头 (用于文件扩展名 fallback)
     */
    public record DownloadResult(byte[] bytes, String contentType) {
    }
}
