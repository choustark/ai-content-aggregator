package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.ApifyTwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

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
@ConditionalOnProperty(prefix = "twitter", name = "discovery-provider", havingValue = "apify")
public class ApifyDiscoveryClient implements TwitterDiscoveryClient {

    private final ApifyTwitterProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ApifyDiscoveryClient(ApifyTwitterProperties properties,
                                @Qualifier("apifyTwitterRestClient") RestClient restClient,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
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
        String content = firstText(item, "text", "fullText", "content", "description");
        String summary = firstText(item, "title", "summary");
        if (!StringUtils.hasText(summary)) {
            summary = content;
        }
        return Tweet.builder()
                .id(id)
                .author(author)
                .summary(summary)
                .content(content)
                .url(StringUtils.hasText(url) ? url : "https://twitter.com/" + stripAt(author) + "/status/" + id)
                .publishedAt(parsePublishedAt(item))
                .replyCount(firstInt(item, "replyCount", "replies", "repliesCount"))
                .retweetCount(firstInt(item, "retweetCount", "retweets", "retweetsCount"))
                .likeCount(firstInt(item, "likeCount", "likes", "likesCount", "favoriteCount"))
                .imageUrls(parseImageUrls(item))
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

    private List<String> parseImageUrls(JsonNode item) {
        JsonNode images = item.get("images");
        if (images == null) {
            images = item.get("imageUrls");
        }
        List<String> urls = new ArrayList<>();
        addImageUrls(urls, images);
        addImageUrls(urls, item.get("media"));
        addImageUrls(urls, item.path("extendedEntities").get("media"));
        addImageUrls(urls, item.path("entities").get("media"));
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
