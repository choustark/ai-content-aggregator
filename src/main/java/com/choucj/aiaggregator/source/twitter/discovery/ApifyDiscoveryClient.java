package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.exception.DegradationException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.ApifyTwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterTargetProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apify Actor-backed Twitter/X 发现实现.
 *
 * <p>默认适配 parseforge~x-com-scraper: input 使用 usernames/maxItems, 输出字段保守解析.
 */
@Slf4j
@Component
public class ApifyDiscoveryClient implements NamedTwitterDiscoveryProvider {

    private final ApifyTwitterProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TwitterTargetProperties targetProperties;

    public ApifyDiscoveryClient(ApifyTwitterProperties properties,
                                @Qualifier("apifyTwitterRestClient") RestClient restClient,
                                ObjectMapper objectMapper,
                                TwitterTargetProperties targetProperties) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.targetProperties = targetProperties;
    }

    @Override
    public String providerName() {
        return "apify";
    }

    @Override
    public List<Tweet> discoverTweets(String username) {
        validateConfigured(username);
        Map<String, Object> input = buildInput(username);
        String url = buildRunUrl();
        String body;
        try {
            body = restClient.post()
                    .uri(url)
                    .body(input)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 限流(429): username=" + username, e);
        } catch (HttpClientErrorException e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 客户端错误(" + e.getStatusCode() + "): username=" + username, e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 服务端错误(" + e.getStatusCode() + "): username=" + username, e);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 连接失败: username=" + username, e);
        } catch (RestClientException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 响应读取失败: username=" + username, e);
        }

        List<Tweet> tweets = parseResponse(body, username);
        log.info("Apify 抓取成功: username={}, actorId={}, 条数={}",
                username, properties.getActorId(), tweets.size());
        return tweets;
    }

    private void validateConfigured(String username) {
        if (!StringUtils.hasText(properties.getToken())) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "apify.twitter.token 未配置, 无法抓取 username=" + username);
        }
        if (!StringUtils.hasText(properties.getActorId())) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "apify.twitter.actor-id 未配置, 无法抓取 username=" + username);
        }
    }

    Map<String, Object> buildInput(String username) {
        Map<String, Object> input = new LinkedHashMap<>(properties.getInputDefaults());
        if (properties.isUseSearchTerms()) {
            input.put(properties.getSearchTermField(), List.of(buildSearchTerm(username)));
        } else {
            input.put(properties.getHandleField(), List.of(username));
        }
        input.put(properties.getMaxItemsField(), properties.getMaxTweetsPerAccount());
        input.putIfAbsent("sort", "Latest");
        return input;
    }

    /**
     * 抓取用户通过 {@code twitter.target.urls} 指定的 X 内容 (Story 6.4).
     *
     * <p>构造 Apify startUrls input (spike-6.1 F1: 对象数组 {@code [{url:...}]}, x.com 域名),
     * 复用 {@link #parseResponse} (Story 6.3 保真解析). 失败按 spike F1/F6 区分:
     * invalid-input / run-failed / 403 配额 / 429 / 5xx, 并套用 W1+W2 + N4.
     *
     * <p>降级编排不在本方法内 (AC5): Apify 不可用时抛 {@link DegradationException},
     * 由调用方经 {@code TwitterSource.enrichTweet} (twscrape → FxTwitter) 完成降级.
     *
     * @param targets X post URL / post ID / Article URL 列表
     * @return 解析后的保真 Tweet 列表; 入口未启用或 targets 为空时返回空列表
     */
    public List<Tweet> discoverSpecifiedTweets(List<String> targets) {
        if (!targetProperties.isEnabled() || targets == null || targets.isEmpty()) {
            log.debug("指定内容入口未启用或 targets 为空, 跳过: enabled={}", targetProperties.isEnabled());
            return List.of();
        }
        validateConfigured("specified");
        Map<String, Object> input = buildStartUrlsInput(targets);
        if (startUrlCount(input) == 0) {
            log.warn("Apify 指定内容: 所有 target 归一化后为空, 返回空列表: targets={}", targets.size());
            return List.of();
        }
        String url = buildRunUrl();
        long startNanos = System.nanoTime();
        String body;
        try {
            body = restClient.post()
                    .uri(url)
                    .body(input)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 指定内容限流(429): targets=" + targets.size()
                            + ", bodyLen=" + responseBodyLength(e));
        } catch (HttpClientErrorException e) {
            throw mapSpecifiedClientError(e, targets.size());
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 指定内容服务端错误(" + e.getStatusCode() + "): targets=" + targets.size()
                            + ", bodyLen=" + responseBodyLength(e));
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 指定内容连接失败: targets=" + targets.size());
        } catch (RestClientException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 指定内容响应读取失败: targets=" + targets.size());
        } catch (RetryableException | NonRetryableException | DegradationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 指定内容调用失败: targets=" + targets.size(), e);
        }
        List<Tweet> tweets = parseResponse(body, "specified");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Apify 指定内容抓取完成: targets={}, actorId={}, 条数={}, 耗时={}ms",
                targets.size(), properties.getActorId(), tweets.size(), elapsedMs);
        return tweets;
    }

    /**
     * 构造指定内容 Actor input (Story 6.4, spike-6.1 F1).
     *
     * <p>startUrls 为对象数组 {@code [{url: normalizedUrl}]}, 字段名经
     * {@code apify.twitter.start-urls-field} 外置 (默认 {@code startUrls}); 同步设置
     * maxItems 为目标数量. 不写 {@code usernames}/{@code searchTerms}/{@code sort}
     * (spike F2: searchTerms 不稳定, 指定内容路径不依赖).
     *
     * @param targets 原始 target 列表 (URL / ID / Article URL), 内部归一化
     * @return Actor input map
     */
    Map<String, Object> buildStartUrlsInput(List<String> targets) {
        Map<String, Object> input = new LinkedHashMap<>(properties.getInputDefaults());
        // startUrls 分支不写账号发现专属字段 (Task 4.3 / spike F1): inputDefaults 是为 usernames
        // 发现路径调优的(含 sort: Latest 等), 指定内容路径只应携带 startUrls + maxItems + 通用字段.
        input.remove("sort");
        input.remove(properties.getHandleField());
        input.remove(properties.getSearchTermField());
        List<Map<String, String>> startUrls = new ArrayList<>(targets.size());
        int skipped = 0;
        for (String target : targets) {
            String normalized = normalizeTarget(target);
            if (normalized == null) {
                skipped++;
                continue;
            }
            startUrls.add(Map.of("url", normalized));
        }
        input.put(properties.getStartUrlsField(), startUrls);
        input.put(properties.getMaxItemsField(), Math.max(1, startUrls.size()));
        if (skipped > 0) {
            log.warn("Apify 指定内容归一化跳过 {} 个无法识别的 target (共 {} 个)", skipped, targets.size());
        }
        return input;
    }

    /**
     * 归一化单个 target 为 spike-6.1 F1 实测可用的 x.com {@code /status/{id}} 形态.
     *
     * <ul>
     *   <li>null/blank → {@code null} (调用方跳过)</li>
     *   <li>纯数字 post ID → {@code https://x.com/i/status/{id}} (spike Q1a 未测变体, 默认实现)</li>
     *   <li>twitter.com 域名 → {@code https://x.com} (spike F1: x.com 对象数组实测可用)</li>
     *   <li>x.com Article URL → {@code https://x.com/i/article/...} (spike Q1b 未测变体, best-effort)</li>
     *   <li>无法识别的非空输入 (非数字、非允许的 X URL) → {@code null} (Task 3.3 本地跳过, 不进入 startUrls)</li>
     * </ul>
     *
     * <p>使用 URI 解析 host, 只接受 {@code x.com/twitter.com/www.twitter.com/www.x.com}; 拒绝
     * {@code twitter.com.evil} 和任意非 X URL, 避免无关 URL 进入 Apify 消耗配额.
     *
     * @param raw 原始 target
     * @return 归一化 URL, 或 {@code null} 表示应跳过
     */
    String normalizeTarget(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.matches("\\d+")) {
            return "https://x.com/i/status/" + trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            String path = uri.getPath();
            if (!isAllowedXHost(host) || !StringUtils.hasText(path)) {
                return null;
            }
            if (path.matches(".*/status/\\d+/?$")) {
                String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                return "https://x.com" + normalizedPath;
            }
            if (path.startsWith("/i/article/")) {
                return "https://x.com" + path + (StringUtils.hasText(uri.getQuery()) ? "?" + uri.getQuery() : "");
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return null;
    }

    private boolean isAllowedXHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        return switch (host.toLowerCase()) {
            case "x.com", "www.x.com", "twitter.com", "www.twitter.com" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private int startUrlCount(Map<String, Object> input) {
        Object startUrls = input.get(properties.getStartUrlsField());
        return startUrls instanceof List<?> list ? list.size() : 0;
    }

    /**
     * 指定内容路径 4xx 异常映射 (W1+W2 + N4, spike-6.1 F1/F6).
     *
     * <ul>
     *   <li>403 配额/授权不可用 → {@link DegradationException} (信号降级到 twscrape/FxTwitter)</li>
     *   <li>400 run-failed (异步, 消耗配额) → {@link NonRetryableException}, message 标注消耗配额</li>
     *   <li>400 invalid-input (同步, 不耗配额) → {@link NonRetryableException}</li>
     *   <li>其他 4xx → {@link NonRetryableException}, tag 取真实 error.type (无 type 时 {@code unknown})</li>
     * </ul>
     * message 只含 targets 数量 + 受控 error.type + status text + body 长度, <b>不嵌入响应体原文</b>,
     * 不含 token / 完整 body (N4). <b>不传 {@code HttpClientErrorException} 作 cause</b> —— 它携带完整
     * response body, 作为 cause 传播可能在日志中泄漏; 排障所需信息已由受控 message 字段提供.
     */
    private RuntimeException mapSpecifiedClientError(HttpClientErrorException e, int targetCount) {
        HttpStatusCode status = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        String errorType = extractErrorType(body);
        int bodyLen = body == null ? 0 : body.length();
        String statusText = e.getStatusText();
        if (status.value() == 403) {
            if (!isMonthlyUsageLimit(body, errorType)) {
                return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "Apify 指定内容调用失败(" + status + " " + statusText + ", type=" + errorType
                                + "): targets=" + targetCount + ", bodyLen=" + bodyLen);
            }
            return new DegradationException(
                    "Apify 指定内容配额/授权不可用(" + status + " " + statusText + "), 应降级: targets=" + targetCount
                            + ", type=" + errorType + ", bodyLen=" + bodyLen);
        }
        // tag 反映真实 error.type (run-failed 标注消耗配额), 不再把未知错误误标为 invalid-input
        String tag = "run-failed".equals(errorType) ? "run-failed(消耗配额)" : errorType;
        return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "Apify 指定内容调用失败(" + status + " " + statusText + ", type=" + tag + "): targets=" + targetCount
                        + ", bodyLen=" + bodyLen);
    }

    /**
     * 从 Apify 错误响应提取受控的 {@code error.type} token (N4: 只输出该短 token, 不输出原文).
     *
     * <p>spike-6.1 F1 实测错误形态: {@code {"error":{"type":"invalid-input"|"run-failed",...}}}.
     * 解析失败或无 type 字段时返回 {@code "unknown"}.
     */
    private String extractErrorType(String body) {
        if (body == null || body.isBlank()) {
            return "unknown";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode type = root.path("error").path("type");
            if (type.isTextual() && StringUtils.hasText(type.asText())) {
                return type.asText();
            }
            JsonNode topType = root.path("type");
            if (topType.isTextual() && StringUtils.hasText(topType.asText())) {
                return topType.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 解析失败不阻断异常映射, 降级为 unknown
        }
        return "unknown";
    }

    private boolean isMonthlyUsageLimit(String body, String errorType) {
        return "usage-limit".equals(errorType)
                || (body != null && body.toLowerCase().contains("monthly usage hard limit"));
    }

    private int responseBodyLength(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body == null ? 0 : body.length();
    }

    private int responseBodyLength(HttpServerErrorException e) {
        String body = e.getResponseBodyAsString();
        return body == null ? 0 : body.length();
    }

    private String buildSearchTerm(String username) {
        String handle = username.startsWith("@") ? username.substring(1) : username;
        return properties.getSearchTermTemplate().formatted(handle);
    }

    String buildRunUrl() {
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return baseUrl + "/v2/acts/"
                + normalizeActorId(properties.getActorId())
                + "/run-sync-get-dataset-items?clean=true&format=json&token="
                + URLEncoder.encode(properties.getToken(), StandardCharsets.UTF_8);
    }

    private String normalizeActorId(String actorId) {
        return actorId.replace('/', '~');
    }

    List<Tweet> parseResponse(String body, String fallbackAuthor) {
        if (body == null || body.isBlank()) {
            log.warn("Apify 响应为空: username={}", fallbackAuthor);
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 响应 JSON 解析失败: username=" + fallbackAuthor, e);
        }
        if (!root.isArray()) {
            HttpStatusCode status = HttpStatusCode.valueOf(502);
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "Apify 响应不是数组: username=" + fallbackAuthor + ", syntheticStatus=" + status);
        }
        List<Tweet> result = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            Tweet tweet = parseItem(item, fallbackAuthor);
            if (tweet != null) {
                result.add(tweet);
            }
        }
        return result;
    }

    private Tweet parseItem(JsonNode item, String fallbackAuthor) {
        if (item.path("noResults").asBoolean(false)) {
            return null;
        }
        String id = firstText(item, "id", "tweetId", "twitterId", "conversationId");
        String url = firstText(item, "url", "tweetUrl", "twitterUrl");
        if (!StringUtils.hasText(id)) {
            id = extractIdFromUrl(url);
        }
        if (!StringUtils.hasText(id)) {
            log.warn("Apify 返回项缺少 tweet id, 跳过: username={}", fallbackAuthor);
            return null;
        }
        String author = normalizeAuthor(firstText(item, "author.userName", "author.username",
                "username", "userName", "handle", "author"), fallbackAuthor);
        String content = firstText(item, "fullText", "text", "content", "description");
        String summary = firstText(item, "title", "summary");
        if (!StringUtils.hasText(summary)) {
            summary = content;
        }
        List<TweetMedia> media = parseMedia(item, id);
        return Tweet.builder()
                .id(id)
                .author(author)
                .summary(summary)
                .content(content)
                .rawText(content)
                .formattedText(content)
                .url(StringUtils.hasText(url) ? url : "https://twitter.com/" + stripAt(author) + "/status/" + id)
                .publishedAt(parsePublishedAt(item))
                .replyCount(firstInt(item, "replyCount", "replies", "repliesCount"))
                .retweetCount(firstInt(item, "retweetCount", "retweets", "retweetsCount"))
                .likeCount(firstInt(item, "likeCount", "likes", "likesCount", "favoriteCount"))
                .imageUrls(parseImageUrls(item, media))
                .media(media)
                .links(parseLinks(item))
                .mentions(parseMentions(item))
                .quotedTweetUrl(parseQuotedTweetUrl(item))
                .build();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = nestedNode(node, name);
            if (value != null && !value.isNull()) {
                String text = value.asText(null);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private JsonNode nestedNode(JsonNode node, String name) {
        JsonNode current = node;
        for (String part : name.split("\\.")) {
            current = current == null ? null : current.get(part);
        }
        return current;
    }

    private int firstInt(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private List<String> parseImageUrls(JsonNode item, List<TweetMedia> media) {
        List<String> urls = new ArrayList<>();
        if (media != null && !media.isEmpty()) {
            urls.addAll(media.stream()
                    .filter(m -> m.getType() == TweetMediaType.PHOTO)
                    .map(TweetMedia::getSourceUrl)
                    .filter(StringUtils::hasText)
                    .toList());
            if (!urls.isEmpty()) {
                return urls;
            }
        }
        JsonNode images = item.get("images");
        if (images == null) {
            images = item.get("imageUrls");
        }
        addImageUrls(urls, images);
        return urls;
    }

    private void addImageUrls(List<String> urls, JsonNode images) {
        if (images == null || !images.isArray()) {
            return;
        }
        for (JsonNode image : images) {
            String url = image.isTextual() ? image.asText() : firstText(image, "url", "src");
            if (StringUtils.hasText(url)) {
                urls.add(url);
            }
        }
    }

    private List<TweetMedia> parseMedia(JsonNode item, String tweetId) {
        JsonNode mediaArray = firstArray(
                item.path("extendedEntities").get("media"),
                item.get("media"),
                item.path("entities").get("media"));
        if (mediaArray == null) {
            return List.of();
        }
        List<TweetMedia> result = new ArrayList<>(mediaArray.size());
        int order = 0;
        for (JsonNode mediaNode : mediaArray) {
            TweetMediaType type = parseMediaType(firstText(mediaNode, "type"));
            List<TweetMediaVariant> variants = parseVariants(mediaNode.path("video_info").get("variants"));
            String previewUrl = firstText(mediaNode, "media_url_https", "thumbnail_url", "url", "src");
            String sourceUrl = type == TweetMediaType.PHOTO
                    ? firstText(mediaNode, "media_url_https", "url", "src")
                    : bestVariantUrl(variants);
            String availability = firstText(mediaNode, "ext_media_availability.status");
            if (!StringUtils.hasText(sourceUrl) && type == TweetMediaType.PHOTO) {
                sourceUrl = previewUrl;
            }
            result.add(TweetMedia.builder()
                    .id(firstText(mediaNode, "id_str", "media_key", "id"))
                    .type(type)
                    .sourceUrl(sourceUrl)
                    .previewImageUrl(previewUrl)
                    .variants(variants)
                    .order(order)
                    .width(firstInteger(mediaNode, "original_info.width", "width"))
                    .height(firstInteger(mediaNode, "original_info.height", "height"))
                    .availability(availability)
                    .allowDownload(parseAllowDownload(mediaNode, type))
                    .provider("apify")
                    .providerRawSummary("tweetId=" + tweetId + ",type=" + type + ",order=" + order
                            + ",variants=" + variants.size())
                    .failureReason(mediaFailureReason(availability))
                    .build());
            order++;
        }
        return result;
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray() && !node.isEmpty()) {
                return node;
            }
        }
        return null;
    }

    private TweetMediaType parseMediaType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return TweetMediaType.UNKNOWN;
        }
        return switch (rawType.toLowerCase()) {
            case "photo" -> TweetMediaType.PHOTO;
            case "video" -> TweetMediaType.VIDEO;
            case "animated_gif", "gif" -> TweetMediaType.GIF;
            default -> TweetMediaType.UNKNOWN;
        };
    }

    private List<TweetMediaVariant> parseVariants(JsonNode variantsNode) {
        if (variantsNode == null || !variantsNode.isArray()) {
            return List.of();
        }
        List<TweetMediaVariant> variants = new ArrayList<>(variantsNode.size());
        for (JsonNode variant : variantsNode) {
            String url = firstText(variant, "url");
            if (!StringUtils.hasText(url)) {
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

    private String mediaFailureReason(String availability) {
        if (!StringUtils.hasText(availability) || "Available".equalsIgnoreCase(availability)) {
            return null;
        }
        return "provider media availability: " + availability;
    }

    private String bestVariantUrl(List<TweetMediaVariant> variants) {
        String fallback = null;
        TweetMediaVariant best = null;
        for (TweetMediaVariant variant : variants) {
            if (!StringUtils.hasText(variant.getUrl())) {
                continue;
            }
            if (fallback == null) {
                fallback = variant.getUrl();
            }
            if ("video/mp4".equalsIgnoreCase(variant.getContentType())) {
                if (best == null || nullToZero(variant.getBitrate()) > nullToZero(best.getBitrate())) {
                    best = variant;
                }
            }
        }
        return best != null ? best.getUrl() : fallback;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private boolean parseAllowDownload(JsonNode mediaNode, TweetMediaType type) {
        JsonNode allowNode = mediaNode.path("allow_download_status").get("allow_download");
        if (allowNode != null && allowNode.isBoolean()) {
            return allowNode.asBoolean();
        }
        return type == TweetMediaType.PHOTO;
    }

    private Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = nestedNode(node, name);
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    private Long firstLong(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = nestedNode(node, name);
            if (value != null && value.canConvertToLong()) {
                return value.asLong();
            }
        }
        return null;
    }

    private List<String> parseLinks(JsonNode item) {
        JsonNode urls = firstArray(item.path("entities").get("urls"), item.get("links"));
        if (urls == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(urls.size());
        for (JsonNode url : urls) {
            String value = url.isTextual() ? url.asText() : firstText(url, "expanded_url", "expandedUrl", "url");
            if (StringUtils.hasText(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> parseMentions(JsonNode item) {
        JsonNode mentions = firstArray(item.path("entities").get("user_mentions"), item.get("mentions"));
        if (mentions == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(mentions.size());
        for (JsonNode mention : mentions) {
            String value = mention.isTextual() ? mention.asText() : firstText(mention, "screen_name", "userName", "username");
            if (StringUtils.hasText(value)) {
                result.add(value.startsWith("@") ? value : "@" + value);
            }
        }
        return result;
    }

    private String parseQuotedTweetUrl(JsonNode item) {
        String directUrl = firstText(item, "quotedStatus.url", "quoted_status.url", "quote.url", "quotedTweetUrl");
        if (StringUtils.hasText(directUrl)) {
            return directUrl;
        }
        String quotedId = firstText(item, "quotedStatus.id", "quoted_status.id_str", "quote.id", "quotedTweetId");
        return StringUtils.hasText(quotedId) ? "https://x.com/i/status/" + quotedId : null;
    }

    private LocalDateTime parsePublishedAt(JsonNode item) {
        String raw = firstText(item, "createdAt", "created_at", "publishedAt", "timestamp");
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private String extractIdFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        int marker = url.indexOf("/status/");
        if (marker < 0) {
            return null;
        }
        String tail = url.substring(marker + "/status/".length());
        int end = tail.indexOf('?');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        return tail.isBlank() ? null : tail;
    }

    private String normalizeAuthor(String raw, String fallbackAuthor) {
        String author = StringUtils.hasText(raw) ? raw : fallbackAuthor;
        return author.startsWith("@") ? author : "@" + author;
    }

    private String stripAt(String author) {
        return author != null && author.startsWith("@") ? author.substring(1) : author;
    }
}
