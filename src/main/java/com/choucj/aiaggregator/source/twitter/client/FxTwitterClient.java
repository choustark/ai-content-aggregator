package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
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
import java.util.HashSet;
import java.util.Set;

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
        JsonNode rawTextNode = tweetNode.get("raw_text");
        String rawText = rawTextNode == null ? content : textValueOrFirst(rawTextNode, "text", "full_text", "fullText");
        String formattedText = content == null ? rawText : content;
        int replyCount = intOrZero(tweetNode, "replies");
        int retweetCount = intOrZero(tweetNode, "retweets");
        int likeCount = intOrZero(tweetNode, "likes");
        List<String> imageUrls = extractPhotos(tweetNode);
        List<TweetMedia> media = extractMedia(tweetNode);
        List<String> links = extractFacetLinks(rawTextNode);
        List<String> mentions = extractFacetMentions(rawTextNode);
        JsonNode quote = firstObject(tweetNode, "quote", "quoted_tweet", "quotedTweet");

        Tweet enriched = Tweet.builder()
                .id(tweetId)
                .content(content)
                .rawText(rawText)
                .formattedText(formattedText)
                .replyCount(replyCount)
                .retweetCount(retweetCount)
                .likeCount(likeCount)
                .imageUrls(imageUrls)
                .media(media)
                .links(links)
                .mentions(mentions)
                .quotedTweetUrl(parseQuotedTweetUrl(quote))
                .quotedTweetText(quote == null ? null : firstText(quote, "text", "full_text", "fullText"))
                .sourceAccessNote(firstNonBlank(content, rawText) == null ? "源文本为空或 provider 未返回文本" : null)
                .build();
        log.debug("FxTwitter 补全成功: tweetId={}, replies={}, retweets={}, likes={}, images={}",
                tweetId, replyCount, retweetCount, likeCount, imageUrls.size());
        return enriched;
    }

    private List<TweetMedia> extractMedia(JsonNode tweetNode) {
        JsonNode mediaNode = tweetNode.get("media");
        if (mediaNode == null || mediaNode.isMissingNode() || mediaNode.isNull()) {
            return List.of();
        }
        List<TweetMedia> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        JsonNode photos = mediaNode.get("photos");
        if (photos != null && photos.isArray()) {
            int order = 0;
            for (JsonNode photo : photos) {
                TweetMedia media = parseMediaItem(photo, TweetMediaType.PHOTO, order++);
                addIfNew(result, seenIds, media);
            }
        }

        JsonNode all = mediaNode.get("all");
        if (all != null && all.isArray()) {
            int order = result.size();
            for (JsonNode item : all) {
                TweetMedia media = parseMediaItem(item, parseMediaType(firstText(item, "type")), order++);
                addIfNew(result, seenIds, media);
            }
        }
        JsonNode videos = mediaNode.get("videos");
        if (videos != null && videos.isArray()) {
            int order = result.size();
            for (JsonNode video : videos) {
                TweetMedia media = parseMediaItem(video, TweetMediaType.VIDEO, order++);
                addIfNew(result, seenIds, media);
            }
        }
        return result;
    }

    private TweetMedia parseMediaItem(JsonNode item, TweetMediaType type, int order) {
        List<TweetMediaVariant> variants = parseVariants(firstArray(item, "variants"));
        return TweetMedia.builder()
                .id(firstText(item, "id", "id_str"))
                .type(type)
                .sourceUrl(type == TweetMediaType.PHOTO
                        ? firstText(item, "url", "media_url_https", "media_url")
                        : firstNonBlank(bestVariantUrl(variants), firstText(item, "url", "src", "media_url_https", "media_url")))
                .previewImageUrl(firstText(item, "thumbnail_url", "thumbnailUrl", "media_url_https", "media_url"))
                .variants(variants)
                .order(order)
                .width(firstInteger(item, "width"))
                .height(firstInteger(item, "height"))
                .allowDownload(true)
                .provider("fxtwitter")
                .providerRawSummary(type + ":variants=" + variants.size())
                .build();
    }

    private List<TweetMediaVariant> parseVariants(JsonNode variantsNode) {
        if (variantsNode == null || !variantsNode.isArray() || variantsNode.isEmpty()) {
            return List.of();
        }
        List<TweetMediaVariant> variants = new ArrayList<>(variantsNode.size());
        for (JsonNode variant : variantsNode) {
            String url = textOrNull(variant, "url");
            if (url == null) {
                continue;
            }
            variants.add(TweetMediaVariant.builder()
                    .url(url)
                    .contentType(firstText(variant, "content_type", "contentType"))
                    .bitrate(firstLong(variant, "bitrate"))
                    .width(firstInteger(variant, "width"))
                    .height(firstInteger(variant, "height"))
                    .build());
        }
        return variants;
    }

    private String bestVariantUrl(List<TweetMediaVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        TweetMediaVariant best = null;
        for (TweetMediaVariant variant : variants) {
            if (variant.getUrl() == null) {
                continue;
            }
            if (best == null || nullToZero(variant.getBitrate()) > nullToZero(best.getBitrate())) {
                best = variant;
            }
        }
        return best == null ? null : best.getUrl();
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private void addIfNew(List<TweetMedia> result, Set<String> seenIds, TweetMedia media) {
        String id = media.getId();
        if (id != null && !seenIds.add(id)) {
            return;
        }
        result.add(media);
    }

    private TweetMediaType parseMediaType(String value) {
        if (value == null) {
            return TweetMediaType.UNKNOWN;
        }
        return switch (value.toLowerCase()) {
            case "photo", "image" -> TweetMediaType.PHOTO;
            case "video" -> TweetMediaType.VIDEO;
            case "gif", "animated_gif" -> TweetMediaType.GIF;
            default -> TweetMediaType.UNKNOWN;
        };
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && child.isArray()) {
                return child;
            }
        }
        return null;
    }

    private JsonNode firstObject(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && child.isObject()) {
                return child;
            }
        }
        return null;
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
            String url = firstText(photo, "url", "media_url_https", "media_url");
            if (url != null) {
                result.add(url);
            }
        }
        return result;
    }

    private List<String> extractFacetLinks(JsonNode rawTextNode) {
        JsonNode facets = firstArray(rawTextNode, "facets");
        if (facets == null || facets.isEmpty()) {
            return List.of();
        }
        List<String> links = new ArrayList<>();
        for (JsonNode facet : facets) {
            if ("url".equalsIgnoreCase(firstText(facet, "type"))) {
                String link = firstText(facet, "replacement", "expanded_url", "expandedUrl", "url");
                if (link != null) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    private List<String> extractFacetMentions(JsonNode rawTextNode) {
        JsonNode facets = firstArray(rawTextNode, "facets");
        if (facets == null || facets.isEmpty()) {
            return List.of();
        }
        List<String> mentions = new ArrayList<>();
        for (JsonNode facet : facets) {
            if ("mention".equalsIgnoreCase(firstText(facet, "type"))) {
                String mention = firstText(facet, "screen_name", "screenName", "username", "userName");
                if (mention != null) {
                    mentions.add(mention.startsWith("@") ? mention : "@" + mention);
                }
            }
        }
        return mentions;
    }

    private String parseQuotedTweetUrl(JsonNode quote) {
        if (quote == null) {
            return null;
        }
        String url = firstText(quote, "url", "tweetUrl", "tweet_url");
        if (url != null) {
            return url;
        }
        String id = firstText(quote, "id", "id_str");
        return id == null ? null : "https://x.com/i/status/" + id;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String textValueOrFirst(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            String text = node.asText(null);
            return text == null || text.isBlank() ? null : text;
        }
        return firstText(node, fields);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || child.isContainerNode()) {
            return null;
        }
        String text = child.asText(null);
        return text.isBlank() ? null : text;
    }

    private int intOrZero(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.canConvertToInt()) {
            return 0;
        }
        return child.asInt();
    }

    private Integer firstInteger(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() || !child.canConvertToInt() ? null : child.asInt();
    }

    private Long firstLong(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() || !child.canConvertToLong() ? null : child.asLong();
    }
}
