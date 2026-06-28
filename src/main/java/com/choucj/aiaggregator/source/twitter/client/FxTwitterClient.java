package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * FxTwitter 单条推文补全客户端.
 *
 * <p>调用 FxTwitter {@code /i/status/{tweetId}.json} 端点补全 RSSHub 缺失的字段:
 * content / replyCount / retweetCount / likeCount / imageUrls.
 *
 * <p><b>返回的 Tweet 仅填充补全字段</b>(content + 互动数 + imageUrls), id 字段为入参的 tweetId,
 * 其余 RSSHub 源字段(author / summary / url / publishedAt) 留空 / 零值, 由
 * {@link com.choucj.aiaggregator.source.twitter.TwitterSource#enrichTweet(Tweet)}
 * 用 {@link Tweet#toBuilder()} 合并.
 *
 * <p><b>异常映射决策表:</b>
 * <table>
 *   <tr><th>HTTP 响应/异常</th><th>映射异常</th><th>处理</th></tr>
 *   <tr><td>200 + 有效 JSON</td><td>(正常)</td><td>返回补全 Tweet</td></tr>
 *   <tr><td>404</td><td>{@link NonRetryableException}</td><td>推文被删 / 不存在, 重试无意义</td></tr>
 *   <tr><td>4xx 除 404</td><td>{@link NonRetryableException}</td><td>参数错误(畸形 tweetId 等)</td></tr>
 *   <tr><td>429</td><td>{@link RetryableException}</td><td>限流, 上层可重试或缓存兜底</td></tr>
 *   <tr><td>5xx</td><td>{@link RetryableException}</td><td>FxTwitter 服务端临时故障</td></tr>
 *   <tr><td>连接异常 / 超时</td><td>{@link RetryableException}</td><td>网络抖动, 上层可重试</td></tr>
 *   <tr><td>JSON 解析失败</td><td>{@link NonRetryableException}</td><td>响应畸形, 重试也无解</td></tr>
 * </table>
 *
 * <p>架构 delta (Story 2.2a): 与 {@link RSSHubClient} 不同, FxTwitter 不实现多实例备份机制
 * (公共实例稳定性较好 + tweet:{id} Redis 缓存兜底).
 */
@Slf4j
@Component
public class FxTwitterClient {

    private static final String TWEET_PATH_PREFIX = "/i/status/";
    private static final String TWEET_PATH_SUFFIX = ".json";

    private final FxTwitterProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入 — 显式而非 {@code @RequiredArgsConstructor}, 因为 {@code @Qualifier} 需要直接标注在
     * 构造器参数上才能让 Spring 精确解析多个 {@link RestClient} Bean(RSSHub / FxTwitter / 默认).
     *
     * @param properties   FxTwitter 配置
     * @param restClient   注入名为 {@code fxtwitterRestClient} 的 RestClient
     * @param objectMapper Jackson JSON 解析器
     */
    public FxTwitterClient(FxTwitterProperties properties,
                           @Qualifier("fxtwitterRestClient") RestClient restClient,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 抓取单条推文补全字段.
     *
     * @param tweetId 推文 ID(纯数字)
     * @return 补全的 Tweet(id + content + 互动数 + imageUrls)
     * @throws RetryableException   FxTwitter 禁用 / 限流 / 5xx / 网络故障
     * @throws NonRetryableException 推文 404 / 参数错误 / JSON 解析失败
     */
    public Tweet fetchTweetDetail(String tweetId) {
        if (!properties.isEnabled()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 已禁用, 不应调用 fetchTweetDetail: tweetId=" + tweetId);
        }
        log.debug("调用 FxTwitter 补全: tweetId={}", tweetId);

        String body;
        try {
            String url = properties.getInstance() + TWEET_PATH_PREFIX + tweetId + TWEET_PATH_SUFFIX;
            body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 404 推文不存在: tweetId=" + tweetId, e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 限流(429): tweetId=" + tweetId, e);
        } catch (HttpClientErrorException e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 客户端错误(" + e.getStatusCode() + "): tweetId=" + tweetId, e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 服务端错误(" + e.getStatusCode() + "): tweetId=" + tweetId, e);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 连接失败: tweetId=" + tweetId, e);
        }
        return parseResponse(body, tweetId);
    }

    /**
     * 解析 FxTwitter JSON 响应为补全 Tweet.
     * <p>FxTwitter 响应结构: {@code { "tweet": { "text": "...", "replies": N, "retweets": N,
     * "likes": N, "media": { "photos": [ {"url": "..."}, ... ] } } }}.
     */
    Tweet parseResponse(String body, String tweetId) {
        if (body == null || body.isBlank()) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 响应为空: tweetId=" + tweetId);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 响应 JSON 解析失败: tweetId=" + tweetId + ", error=" + e.getMessage(), e);
        }
        JsonNode tweetNode = root.get("tweet");
        if (tweetNode == null || tweetNode.isMissingNode() || tweetNode.isNull()) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "FxTwitter 响应缺少 tweet 字段: tweetId=" + tweetId);
        }
        String content = textOrNull(tweetNode, "text");
        int replyCount = intOrZero(tweetNode, "replies");
        int retweetCount = intOrZero(tweetNode, "retweets");
        int likeCount = intOrZero(tweetNode, "likes");
        List<String> imageUrls = extractPhotos(tweetNode);

        Tweet enriched = Tweet.builder()
                .id(tweetId)
                .content(content)
                .replyCount(replyCount)
                .retweetCount(retweetCount)
                .likeCount(likeCount)
                .imageUrls(imageUrls)
                .build();
        log.debug("FxTwitter 补全成功: tweetId={}, replies={}, retweets={}, likes={}, images={}",
                tweetId, replyCount, retweetCount, likeCount, imageUrls.size());
        return enriched;
    }

    private List<String> extractPhotos(JsonNode tweetNode) {
        JsonNode media = tweetNode.get("media");
        if (media == null || media.isMissingNode() || media.isNull()) {
            return List.of();
        }
        JsonNode photos = media.get("photos");
        if (photos == null || !photos.isArray() || photos.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(photos.size());
        for (JsonNode photo : photos) {
            String url = textOrNull(photo, "url");
            if (url != null) {
                result.add(url);
            }
        }
        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }

    private int intOrZero(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.canConvertToInt()) {
            return 0;
        }
        return child.asInt();
    }
}
