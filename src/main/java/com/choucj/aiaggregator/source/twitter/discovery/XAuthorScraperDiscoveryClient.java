package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.ScraperProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * x-author-self-scraper 本地服务发现实现.
 *
 * <p>该 provider 通过本地 HTTP API 运行同仓库的 Apify SDK/Crawlee Actor:
 * {@code POST /v1/jobs -> GET /v1/jobs/{id} -> GET /v1/jobs/{id}/results}. 它与
 * Apify 云端 Actor API 协议不同, 因此作为 {@code twitter.discovery-provider=scraper}
 * / {@code twitter.discovery-providers[]=scraper} 的独立 provider 接入.
 */
@Slf4j
@Component
public class XAuthorScraperDiscoveryClient implements NamedTwitterDiscoveryProvider {

    private static final Pattern TWEET_ID_PATTERN = Pattern.compile("/status/(\\d+)");
    private static final int SUMMARY_ENTRY_LIMIT = 3;
    private static final int SUMMARY_TEXT_LIMIT = 120;
    private static final Set<String> SESSION_FAILURE_CODES = Set.of(
            "RATE_LIMITED", "LOGIN_EXPIRED", "LOGIN_REQUIRED", "CHALLENGE");

    private final ScraperProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public XAuthorScraperDiscoveryClient(ScraperProperties properties,
                                         @Qualifier("xAuthorScraperRestClient") RestClient restClient,
                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "scraper";
    }

    @Override
    public List<Tweet> discoverTweets(String username) {
        String normalizedUsername = normalizeUsername(username);
        if (!StringUtils.hasText(normalizedUsername)) {
            return List.of();
        }
        Map<String, Object> input = buildInput(normalizedUsername);
        long startNanos = System.nanoTime();
        String jobId = submitJob(input, normalizedUsername);
        JsonNode job = waitForCompletion(jobId, normalizedUsername);
        List<Tweet> tweets = fetchResults(jobId, normalizedUsername);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("x-author scraper 抓取完成: username={}, jobId={}, status={}, 条数={}, 耗时={}ms",
                normalizedUsername, jobId, job.path("status").asText("unknown"), tweets.size(), elapsedMs);
        return tweets;
    }

    Map<String, Object> buildInput(String username) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("usernames", List.of(username));
        input.put("includePosts", properties.isIncludePosts());
        input.put("includeArticles", properties.isIncludeArticles());
        input.put("includeReplies", properties.isIncludeReplies());
        input.put("maxItemsPerAuthor", properties.getMaxItemsPerAuthor());
        input.put("maxArticlesPerAuthor", properties.getMaxArticlesPerAuthor());
        input.put("maxScrolls", properties.getMaxScrolls());
        input.put("scrollDelayMs", properties.getScrollDelayMs());
        input.put("proxyConfiguration", Map.of("useApifyProxy", false));

        // 记录实际发送的配置，便于调试
        log.debug("x-author scraper 输入参数: username={}, includePosts={}, includeArticles={}, includeReplies={}",
                username, input.get("includePosts"), input.get("includeArticles"), input.get("includeReplies"));

        return input;
    }

    private String submitJob(Map<String, Object> input, String username) {
        String body;
        try {
            body = restClient.post()
                    .uri(buildUrl("/v1/jobs"))
                    .body(input)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw mapClientError(e, "submit", null, username);
        } catch (HttpServerErrorException e) {
            throw mapServerError(e, "submit", null, username);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 连接失败: stage=submit, username=" + username, e);
        } catch (RestClientException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 响应读取失败: stage=submit, username=" + username, e);
        }
        JsonNode root = parseJson(body, "submit", null, username);
        String jobId = root.path("job").path("id").asText(null);
        if (!StringUtils.hasText(jobId)) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 创建 job 响应缺少 job.id: username=" + username
                            + ", bodyLen=" + length(body));
        }
        return jobId;
    }

    private JsonNode waitForCompletion(String jobId, String username) {
        long deadline = System.nanoTime() + properties.getMaxWaitSeconds() * 1_000_000_000L;
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = getJob(jobId, username);
            String status = latest.path("status").asText("");
            if ("completed".equals(status)) {
                return latest;
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                throw mapJobFailure(latest, jobId, username);
            }
            sleep(jobId, username);
        }
        String status = latest == null ? "unknown" : latest.path("status").asText("unknown");
        throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "x-author scraper job 轮询超时: jobId=" + jobId + ", username=" + username
                        + ", status=" + status + ", timeout=" + properties.getMaxWaitSeconds() + "s");
    }

    private JsonNode getJob(String jobId, String username) {
        String body;
        try {
            body = restClient.get()
                    .uri(buildUrl("/v1/jobs/" + jobId))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw mapClientError(e, "poll", jobId, username);
        } catch (HttpServerErrorException e) {
            throw mapServerError(e, "poll", jobId, username);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 连接失败: stage=poll, jobId=" + jobId + ", username=" + username, e);
        } catch (RestClientException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 响应读取失败: stage=poll, jobId=" + jobId + ", username=" + username, e);
        }
        JsonNode root = parseJson(body, "poll", jobId, username);
        JsonNode job = root.path("job");
        if (job.isMissingNode() || job.isNull()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper job 响应缺少 job 字段: jobId=" + jobId + ", username=" + username
                            + ", bodyLen=" + length(body));
        }
        return job;
    }

    private List<Tweet> fetchResults(String jobId, String fallbackAuthor) {
        String body;
        try {
            body = restClient.get()
                    .uri(buildUrl("/v1/jobs/" + jobId + "/results?limit=" + resultLimit()))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw mapClientError(e, "results", jobId, fallbackAuthor);
        } catch (HttpServerErrorException e) {
            throw mapServerError(e, "results", jobId, fallbackAuthor);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 连接失败: stage=results, jobId=" + jobId
                            + ", username=" + fallbackAuthor, e);
        } catch (RestClientException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 响应读取失败: stage=results, jobId=" + jobId
                            + ", username=" + fallbackAuthor, e);
        }
        JsonNode root = parseJson(body, "results", jobId, fallbackAuthor);
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<Tweet> tweets = new ArrayList<>();
        for (JsonNode item : items) {
            Tweet tweet = parseItem(item, fallbackAuthor);
            if (tweet != null) {
                tweets.add(tweet);
            }
        }
        return tweets;
    }

    Tweet parseItem(JsonNode item, String fallbackAuthor) {
        String url = firstText(item, "url", "tweetUrl");
        String id = firstText(item, "id", "tweetId");
        if (!StringUtils.hasText(id)) {
            id = extractTweetId(url);
        }
        if (!StringUtils.hasText(id)) {
            log.warn("x-author scraper 返回项缺少 tweet id, 跳过: fallbackAuthor={}", fallbackAuthor);
            return null;
        }
        String content = firstTextValue(path(item, "article.body"), path(item, "body"),
                path(item, "text"), path(item, "fullText"));
        if (!StringUtils.hasText(content)) {
            log.warn("x-author scraper 返回项正文为空, 跳过: tweetId={}, contentType={}",
                    id, firstText(item, "contentType"));
            return null;
        }
        String author = normalizeAuthor(firstText(item, "requestedUsername", "username", "author.userName", "author.username"),
                fallbackAuthor);
        List<TweetMedia> media = extractMedia(item);
        List<String> imageUrls = media.stream()
                .filter(m -> m.getType() == TweetMediaType.PHOTO)
                .map(TweetMedia::getSourceUrl)
                .filter(StringUtils::hasText)
                .toList();

        return Tweet.builder()
                .id(id)
                .author(author)
                .content(content)
                .rawText(content)
                .formattedText(content)
                .summary(firstTextValue(path(item, "article.title"), path(item, "title"), path(item, "summary")))
                .url(url)
                .publishedAt(parsePublishedAt(firstText(item, "createdAt", "publishedAt")))
                .replyCount(intOrZero(path(item, "metrics.replyCount"), path(item, "metrics.replies")))
                .retweetCount(intOrZero(path(item, "metrics.retweetCount"), path(item, "metrics.reposts")))
                .likeCount(intOrZero(path(item, "metrics.likeCount"), path(item, "metrics.likes")))
                .imageUrls(imageUrls)
                .media(media)
                .links(extractTextArray(path(item, "links"), path(item, "article.links")))
                .mentions(extractTextArray(path(item, "mentions")))
                .quotedTweetUrl(buildQuotedTweetUrl(firstText(item, "quotedTweetUrl", "quotedTweetId")))
                .sourceAccessNote("article".equals(firstText(item, "contentType")) ? "x-author-scraper article" : null)
                .build();
    }

    private RuntimeException mapJobFailure(JsonNode job, String jobId, String username) {
        String code = job.path("error").path("code").asText("ACTOR_FAILED");
        String summary = jobSummaryForLog(job);
        String message = "x-author scraper job 失败: jobId=" + jobId + ", username=" + username
                + ", code=" + code + summary;
        log.warn("x-author scraper job 失败详情: jobId={}, username={}, code={}{}",
                jobId, username, code, summary);
        if (SESSION_FAILURE_CODES.contains(code) || "ARTICLE_FAILED".equals(code)) {
            return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
        }
        return new RetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
    }

    private String jobSummaryForLog(JsonNode job) {
        JsonNode summary = job.path("summary");
        if (summary.isMissingNode() || summary.isNull()) {
            return "";
        }
        return ", resultCount=" + job.path("resultCount").asInt(0)
                + ", itemCount=" + summary.path("itemCount").asInt(0)
                + ", articleDiscoveries=" + summarizeArticleDiscoveries(summary.path("articleDiscoveries"))
                + ", failedArticleDiscoveries=" + summarizeFailedArticleDiscoveries(
                        summary.path("failedArticleDiscoveries"))
                + ", succeededArticles=" + summarizeArticles(summary.path("succeededArticles"))
                + ", failedArticles=" + summarizeFailedArticles(summary.path("failedArticles"));
    }

    private String summarizeArticleDiscoveries(JsonNode discoveries) {
        if (!discoveries.isArray() || discoveries.isEmpty()) {
            return "[]";
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode item : discoveries) {
            entries.add(firstText(item, "username") + ":articleCount=" + item.path("articleCount").asInt(0));
            if (entries.size() >= SUMMARY_ENTRY_LIMIT) {
                break;
            }
        }
        return summarizeEntries(entries, discoveries.size());
    }

    private String summarizeFailedArticleDiscoveries(JsonNode failures) {
        if (!failures.isArray() || failures.isEmpty()) {
            return "[]";
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode item : failures) {
            entries.add(firstText(item, "username") + ":" + firstText(item, "code")
                    + ":" + truncateForLog(firstText(item, "message"), SUMMARY_TEXT_LIMIT));
            if (entries.size() >= SUMMARY_ENTRY_LIMIT) {
                break;
            }
        }
        return summarizeEntries(entries, failures.size());
    }

    private String summarizeArticles(JsonNode articles) {
        if (!articles.isArray() || articles.isEmpty()) {
            return "[]";
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode item : articles) {
            entries.add(firstText(item, "id") + ":" + truncateForLog(firstText(item, "title"), SUMMARY_TEXT_LIMIT));
            if (entries.size() >= SUMMARY_ENTRY_LIMIT) {
                break;
            }
        }
        return summarizeEntries(entries, articles.size());
    }

    private String summarizeFailedArticles(JsonNode failures) {
        if (!failures.isArray() || failures.isEmpty()) {
            return "[]";
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode item : failures) {
            entries.add(firstText(item, "id") + ":" + firstText(item, "code")
                    + ":" + truncateForLog(firstText(item, "message"), SUMMARY_TEXT_LIMIT));
            if (entries.size() >= SUMMARY_ENTRY_LIMIT) {
                break;
            }
        }
        return summarizeEntries(entries, failures.size());
    }

    private String summarizeEntries(List<String> entries, int total) {
        if (total > entries.size()) {
            entries.add("...+" + (total - entries.size()));
        }
        return entries.toString();
    }

    private RuntimeException mapClientError(HttpClientErrorException e, String stage, String jobId, String username) {
        String code = extractErrorCode(e.getResponseBodyAsString());
        String message = "x-author scraper 客户端错误(" + e.getStatusCode() + "): stage=" + stage
                + context(jobId, username) + ", code=" + code + ", bodyLen=" + length(e.getResponseBodyAsString());
        if (SESSION_FAILURE_CODES.contains(code) || "ARTICLE_FAILED".equals(code)) {
            return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
        }
        return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
    }

    private RuntimeException mapServerError(HttpServerErrorException e, String stage, String jobId, String username) {
        String code = extractErrorCode(e.getResponseBodyAsString());
        String message = "x-author scraper 服务端错误(" + e.getStatusCode() + "): stage=" + stage
                + context(jobId, username) + ", code=" + code + ", bodyLen=" + length(e.getResponseBodyAsString());
        if (SESSION_FAILURE_CODES.contains(code) || "ARTICLE_FAILED".equals(code)) {
            return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
        }
        return new RetryableException(ErrorCode.EXTERNAL_API_ERROR, message);
    }

    private JsonNode parseJson(String body, String stage, String jobId, String username) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper JSON 解析失败: stage=" + stage + context(jobId, username)
                            + ", bodyLen=" + length(body), e);
        }
    }

    private void sleep(String jobId, String username) {
        try {
            Thread.sleep(properties.getPollIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "x-author scraper 轮询线程中断: jobId=" + jobId + ", username=" + username, e);
        }
    }

    private List<TweetMedia> extractMedia(JsonNode item) {
        List<TweetMedia> result = new ArrayList<>();
        int photoCount = addMediaUrls(result, path(item, "media"));
        photoCount += addMediaUrls(result, path(item, "images"));
        addArticleImages(result, path(item, "article.images"));
        String cover = firstTextValue(path(item, "article.coverImageUrl"), path(item, "coverImageUrl"),
                path(item, "previewImageUrl"));
        if (StringUtils.hasText(cover)) {
            result.add(buildPhoto(cover, result.size()));
            photoCount++;
        }
        // W11: 仅输出计数摘要, 不含完整 URL (N4)
        int videoCount = (int) result.stream().filter(m -> m.getType() == TweetMediaType.VIDEO).count();
        int gifCount = (int) result.stream().filter(m -> m.getType() == TweetMediaType.GIF).count();
        if (!result.isEmpty()) {
            log.debug("x-author scraper 媒体解析: mediaCount={}, photoCount={}, videoCount={}, gifCount={}",
                    result.size(), photoCount, videoCount, gifCount);
        }
        return result;
    }

    /**
     * 遍历 media array, 按 {@code type} 字段分发: photo → {@link #buildPhoto},
     * video/animated_gif → {@link #buildVideoOrGif}, type 缺失或未知 → 保守降级为 PHOTO (不丢媒体).
     *
     * <p>Story 7.3: 兑现 readiness deferred 决策 (默认主路径 VIDEO/GIF 不再被降级为 PHOTO)。
     *
     * @return 本轮解析中按 PHOTO 路径生成的媒体数 (含保守降级), 用于 W11 debug 计数
     */
    private int addMediaUrls(List<TweetMedia> result, JsonNode array) {
        if (!array.isArray()) {
            return 0;
        }
        int photoAdded = 0;
        for (JsonNode node : array) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                continue;
            }
            String type = firstText(node, "type");
            TweetMediaType resolved = resolveType(type);
            if (resolved == TweetMediaType.VIDEO || resolved == TweetMediaType.GIF) {
                result.add(buildVideoOrGif(node, resolved, result.size()));
            } else {
                // PHOTO 或 type 缺失/未知 → 保守降级为 PHOTO (不丢媒体)
                String url = node.isTextual() ? node.asText() : firstText(node, "url", "src", "sourceUrl");
                if (StringUtils.hasText(url)) {
                    result.add(buildPhoto(url, result.size()));
                    photoAdded++;
                }
            }
        }
        return photoAdded;
    }

    /**
     * 解析 dataset media 的 {@code type} 字段 (photo / video / animated_gif), 未知值返回 null 触发保守降级.
     */
    private TweetMediaType resolveType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        String normalized = type.trim().toLowerCase();
        return switch (normalized) {
            case "photo" -> TweetMediaType.PHOTO;
            case "video" -> TweetMediaType.VIDEO;
            case "animated_gif", "gif" -> TweetMediaType.GIF;
            default -> null;
        };
    }

    /**
     * 构造 VIDEO/GIF 媒体: sourceUrl=videoUrl (可下载候选), previewImageUrl=dataset media 的 url (缩略图),
     * 保留 width/height, 构造单 variant (url=videoUrl). providerRawSummary 记录类型 + variant 数摘要.
     *
     * <p>Story 7.3: allowDownload=false (VIDEO/GIF 本 Story 不下载, 复现路径待 Story 8.2 spike).
     */
    private TweetMedia buildVideoOrGif(JsonNode node, TweetMediaType type, int order) {
        String videoUrl = firstText(node, "videoUrl");
        Integer width = firstInt(node, "width");
        Integer height = firstInt(node, "height");
        String previewUrl = firstText(node, "url");
        List<TweetMediaVariant> variants = extractVariants(node, videoUrl, width, height);
        String summary = type.name().toLowerCase() + ":variants=" + variants.size();
        return TweetMedia.builder()
                .type(type)
                .sourceUrl(videoUrl)
                .previewImageUrl(previewUrl)
                .width(width)
                .height(height)
                .order(order)
                .variants(variants)
                .provider("x-author-scraper")
                .allowDownload(false)
                .providerRawSummary(summary)
                .build();
    }

    private List<TweetMediaVariant> extractVariants(JsonNode node, String videoUrl, Integer width, Integer height) {
        List<TweetMediaVariant> variants = new ArrayList<>();
        JsonNode variantNodes = path(node, "variants");
        if (variantNodes.isArray()) {
            for (JsonNode variantNode : variantNodes) {
                String variantUrl = firstText(variantNode, "url", "videoUrl");
                if (!StringUtils.hasText(variantUrl)) {
                    continue;
                }
                variants.add(TweetMediaVariant.builder()
                        .url(variantUrl)
                        .contentType(firstText(variantNode, "contentType", "content_type", "mimeType", "mime_type"))
                        .bitrate(firstLong(variantNode, "bitrate", "bit_rate"))
                        .width(firstIntValue(variantNode, "width", width))
                        .height(firstIntValue(variantNode, "height", height))
                        .build());
            }
        }
        if (variants.isEmpty() && StringUtils.hasText(videoUrl)) {
            variants.add(TweetMediaVariant.builder()
                    .url(videoUrl).width(width).height(height).build());
        }
        return List.copyOf(variants);
    }

    private void addArticleImages(List<TweetMedia> result, JsonNode images) {
        if (!images.isArray()) {
            return;
        }
        for (JsonNode image : images) {
            String url = firstText(image, "src", "url");
            if (StringUtils.hasText(url)) {
                result.add(buildPhoto(url, result.size()));
            }
        }
    }

    private TweetMedia buildPhoto(String url, int order) {
        return TweetMedia.builder()
                .type(TweetMediaType.PHOTO)
                .sourceUrl(url)
                .previewImageUrl(url)
                .order(order)
                .provider("x-author-scraper")
                .allowDownload(true)
                .build();
    }

    private JsonNode path(JsonNode root, String dottedPath) {
        if (root == null || dottedPath == null) {
            return MissingNode.getInstance();
        }
        JsonNode current = root;
        for (String part : dottedPath.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = firstText(path(node, field));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private String firstTextValue(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = firstText(node);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 从 node 读取整数, 缺失/非数值时返回 null (D3: width/height 是 Integer nullable, 不拆箱).
     *
     * <p>Story 7.3: dataset media 的 width/height 为数值节点, 缺失时返回 null 而非 0, 避免 0 误导下游.
     */
    private Integer firstInt(JsonNode node, String field) {
        JsonNode child = path(node, field);
        if (child == null || child.isMissingNode() || child.isNull()) {
            return null;
        }
        if (child.canConvertToInt()) {
            return child.asInt();
        }
        if (child.isTextual()) {
            try {
                return Integer.parseInt(child.asText().trim().replaceAll("[^0-9-]", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer firstIntValue(JsonNode node, String field, Integer fallback) {
        Integer value = firstInt(node, field);
        return value != null ? value : fallback;
    }

    private Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = path(node, field);
            if (child == null || child.isMissingNode() || child.isNull()) {
                continue;
            }
            if (child.canConvertToLong()) {
                return child.asLong();
            }
            if (child.isTextual()) {
                try {
                    return Long.parseLong(child.asText().trim().replaceAll("[^0-9-]", ""));
                } catch (NumberFormatException ignored) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    private int intOrZero(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.canConvertToInt()) {
                return node.asInt();
            }
            if (node != null && node.isTextual()) {
                try {
                    return Integer.parseInt(node.asText().replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                    // try next candidate
                }
            }
        }
        return 0;
    }

    private List<String> extractTextArray(JsonNode... nodes) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node == null || !node.isArray()) {
                continue;
            }
            for (JsonNode item : node) {
                String value = firstText(item);
                if (StringUtils.hasText(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return username.trim().replaceFirst("^@", "");
    }

    private String normalizeAuthor(String candidate, String fallbackAuthor) {
        String value = StringUtils.hasText(candidate) ? candidate : fallbackAuthor;
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("@") ? trimmed : "@" + trimmed;
    }

    private String extractTweetId(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        Matcher matcher = TWEET_ID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private LocalDateTime parsePublishedAt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String buildQuotedTweetUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return "https://x.com/i/status/" + value;
    }

    private String extractErrorCode(String body) {
        if (!StringUtils.hasText(body)) {
            return "unknown";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String nested = root.path("error").path("code").asText(null);
            return StringUtils.hasText(nested) ? nested : root.path("code").asText("unknown");
        } catch (JsonProcessingException ignored) {
            return "unknown";
        }
    }

    private int length(String body) {
        return body == null ? 0 : body.length();
    }

    private String truncateForLog(String value, int maxCodePoints) {
        if (!StringUtils.hasText(value)) {
            return "n/a";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, Math.max(0, maxCodePoints - 3));
        return normalized.substring(0, end) + "...";
    }

    private int resultLimit() {
        return Math.max(properties.getMaxItemsPerAuthor(), properties.getMaxArticlesPerAuthor());
    }

    private String buildUrl(String path) {
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return baseUrl + path;
    }

    private String context(String jobId, String username) {
        return ", jobId=" + (jobId == null ? "n/a" : jobId) + ", username=" + username;
    }
}
