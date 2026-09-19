package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import com.choucj.aiaggregator.source.twitter.config.RSSHubProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSSHub Twitter 推文发现客户端.
 *
 * <p>调用 RSSHub {@code /twitter/user/{username}.json} 端点获取目标账号的最新推文列表(仅基础字段:
 * id / author / summary / url / publishedAt). 完整字段(content / replyCount / ...)由 Story 2.2
 * 通过 FxTwitter / twscrape 补全.
 *
 * <p><b>多实例备份:</b> 按 {@link RSSHubProperties#getInstances()} 顺序依次尝试, 主实例失败
 * (连接异常 / 5xx / 超时 / 429) 自动切下一实例; 4xx(除 429) 视为参数错跳过该实例不切换;
 * 全部实例失败抛 {@link RetryableException}.
 *
 * <p><b>架构 delta (Story 2.1):</b>
 * <ul>
 *   <li>本类<b>不实现</b>{@code DataSource<Tweet>}. 这是底层 HTTP helper,
 *       Story 2.2 的 {@code TwitterSource} 才是 {@code DataSource<Tweet>} 的实现,
 *       编排 {@code RSSHubClient} + {@code FxTwitterClient}(架构 delta 登记: RSSHubClient 不实现 DataSource 显式化).</li>
 *   <li>使用 Spring 6.1 {@link RestClient}(同步) 替代 WebClient.</li>
 * </ul>
 *
 * <p><b>异常映射决策表:</b>
 * <table>
 *   <tr><th>HTTP 响应/异常</th><th>映射异常</th><th>处理</th></tr>
 *   <tr><td>200 + 有效 JSON</td><td>(正常)</td><td>返回 {@code List<Tweet>}</td></tr>
 *   <tr><td>200 + 空 items</td><td>(正常)</td><td>返回空列表 + info 日志</td></tr>
 *   <tr><td>200 + JSON 解析失败</td><td>(不抛)</td><td>warn + 返回空列表(实例视为可用)</td></tr>
 *   <tr><td>429 / 5xx</td><td>{@link RetryableException}</td><td>切下一实例</td></tr>
 *   <tr><td>4xx 除 429</td><td>{@link NonRetryableException}</td><td>跳过该实例(不切)</td></tr>
 *   <tr><td>连接异常 / 超时</td><td>{@link RetryableException}</td><td>切下一实例</td></tr>
 *   <tr><td>全部实例失败</td><td>{@link RetryableException}</td><td>抛给调用方决定</td></tr>
 * </table>
 */
@Slf4j
@Component
public class RSSHubClient {

    private static final Pattern TWEET_ID_PATTERN = Pattern.compile("/status/(\\d+)");
    private static final DateTimeFormatter RFC_2822 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final RSSHubProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SlowOperationRecorder slowOperationRecorder;

    /**
     * 构造器注入 — 显式而非 {@code @RequiredArgsConstructor}, 因为 {@code @Qualifier} 需要直接标注在
     * 构造器参数上才能让 Spring 精确解析多个 {@link RestClient} Bean(RSSHub 专用 + 默认).
     *
     * @param properties   RSSHub 配置
     * @param restClient   注入名为 {@code rsshubRestClient} 的 RestClient
     * @param objectMapper Jackson JSON 解析器
     */
    public RSSHubClient(RSSHubProperties properties,
                        @Qualifier("rsshubRestClient") RestClient restClient,
                        ObjectMapper objectMapper,
                        SlowOperationRecorder slowOperationRecorder) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.slowOperationRecorder = slowOperationRecorder;
    }

    /**
     * 发现目标账号的最新推文列表.
     *
     * @param username Twitter 账号 handle(不带 @, 如 {@code karpathy})
     * @return 推文列表(可能为空, 但不为 {@code null}); 仅填充 5 字段(id/author/summary/url/publishedAt)
     * @throws RetryableException 当 RSSHub 禁用时仍被调用(配置错误), 或全部实例失败时
     */
    public List<Tweet> discoverTweets(String username) {
        if (!properties.isEnabled()) {
            log.info("RSSHub 已禁用, 跳过抓取: username={}", username);
            return List.of();
        }
        if (properties.getInstances().isEmpty()) {
            // 启动 @Validated 已校验, 这里防御性二次检查
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub instances 未配置, username=" + username);
        }
        log.info("开始抓取 RSSHub 推文: username={}, 尝试实例数={}", username, properties.getInstances().size());

        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        NonRetryableException nonRetryableSeen = null;
        for (String instance : properties.getInstances()) {
            try {
                List<Tweet> tweets = tryInstance(instance, encoded, username);
                log.info("RSSHub 抓取成功: instance={}, username={}, 条数={}", instance, username, tweets.size());
                return tweets;
            } catch (NonRetryableException e) {
                // 4xx(除 429): 参数错误, 换实例也解决不了(username 拼错等), 跳过不切
                // 记下首个 NonRetryable, 全部实例失败时按 NonRetryable 抛出(语义一致: 重试无意义)
                if (nonRetryableSeen == null) {
                    nonRetryableSeen = e;
                }
                log.warn("RSSHub 实例 {} 返回不可重试错误, 跳过该实例: username={}, reason={}",
                        instance, username, e.getMessage());
            } catch (RetryableException e) {
                // 连接异常 / 429 / 5xx / 超时: 切下一实例
                log.warn("RSSHub 实例 {} 调用失败, 切换下一个: username={}, reason={}",
                        instance, username, e.getMessage());
            }
        }
        log.error("RSSHub 全部实例失败: username={}, 尝试实例={}", username, properties.getInstances());
        // 任一实例返回 4xx(参数错) → 视为 NonRetryable(重试无意义); 否则全是 Retryable 失败 → Retryable
        if (nonRetryableSeen != null) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub 全部实例失败(含客户端错误): username=" + username
                            + ", instances=" + properties.getInstances(), nonRetryableSeen);
        }
        throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "RSSHub 全部实例失败: username=" + username + ", instances=" + properties.getInstances());
    }

    /**
     * 调用单个 RSSHub 实例并解析响应.
     *
     * @param instance     实例 URL(末尾不带斜杠)
     * @param encodedUsername URL 编码后的 username
     * @param rawUsername  原始 username(用于 author fallback 日志)
     * @return 解析出的推文列表(可能为空)
     */
    private List<Tweet> tryInstance(String instance, String encodedUsername, String rawUsername) {
        String url = buildUrl(instance, encodedUsername);
        String body;
        try {
            body = slowOperationRecorder.observe(
                    Kind.HTTP,
                    Dependency.RSSHUB,
                    Operation.DISCOVER,
                    () -> restClient.get().uri(url).retrieve().body(String.class));
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub 限流(429): instance=" + instance + ", username=" + rawUsername, e);
        } catch (HttpClientErrorException e) {
            // 4xx 除 429: 视为参数错(username 不存在 / 路由失效等), 不可重试
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub 客户端错误(" + e.getStatusCode() + "): instance=" + instance
                            + ", username=" + rawUsername, e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub 服务端错误(" + e.getStatusCode() + "): instance=" + instance
                            + ", username=" + rawUsername, e);
        } catch (ResourceAccessException e) {
            // 连接超时 / 读超时 / 未知 host
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RSSHub 连接失败: instance=" + instance + ", username=" + rawUsername, e);
        }
        return parseResponse(body, rawUsername);
    }

    private String buildUrl(String instance, String encodedUsername) {
        String base = instance.endsWith("/") ? instance.substring(0, instance.length() - 1) : instance;
        return base + "/twitter/user/" + encodedUsername + ".json";
    }

    /**
     * 解析 RSSHub JSON 响应为 {@code List<Tweet>}.
     * <p>JSON 解析失败不抛异常, 返回空列表 + warn 日志(本实例视为可用, 不切换).
     */
    List<Tweet> parseResponse(String body, String username) {
        if (body == null || body.isBlank()) {
            log.warn("RSSHub 响应为空: username={}", username);
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("RSSHub 响应 JSON 解析失败: username={}, error={}", username, e.getMessage());
            return List.of();
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            log.info("RSSHub 响应 items 为空: username={}", username);
            return List.of();
        }
        List<Tweet> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            Tweet tweet = parseItem(item, username);
            if (tweet != null) {
                result.add(tweet);
            }
        }
        return result;
    }

    private Tweet parseItem(JsonNode item, String fallbackAuthor) {
        String url = textOrNull(item, "url");
        String id = extractTweetId(url, item);
        if (id == null) {
            log.warn("无法提取 tweet id, 跳过该条: url={}, id={}", url, textOrNull(item, "id"));
            return null;
        }
        String author = extractAuthor(item, fallbackAuthor);
        String summary = textOrNull(item, "title");
        if (summary == null) {
            summary = textOrNull(item, "summary");
        }
        LocalDateTime publishedAt = extractPublishedAt(item);

        return Tweet.builder()
                .id(id)
                .author(author)
                .summary(summary)
                .url(url)
                .publishedAt(publishedAt)
                .build();
    }

    private String extractTweetId(String url, JsonNode item) {
        if (url != null) {
            Matcher m = TWEET_ID_PATTERN.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        }
        // 退回 items[].id 字段, RSSHub 偶尔直接返回数字 ID
        JsonNode idNode = item.get("id");
        if (idNode != null && idNode.isNumber()) {
            return String.valueOf(idNode.asLong());
        }
        if (idNode != null && idNode.isTextual()) {
            String idText = idNode.asText();
            Matcher m = TWEET_ID_PATTERN.matcher(idText);
            if (m.find()) {
                return m.group(1);
            }
            // 纯数字 ID
            if (idText.matches("\\d+")) {
                return idText;
            }
        }
        return null;
    }

    private String extractAuthor(JsonNode item, String fallback) {
        JsonNode authors = item.get("authors");
        if (authors != null && authors.isArray() && !authors.isEmpty()) {
            JsonNode first = authors.get(0);
            JsonNode name = first.get("name");
            if (name != null && name.isTextual() && !name.asText().isBlank()) {
                return name.asText();
            }
        }
        return fallback;
    }

    private LocalDateTime extractPublishedAt(JsonNode item) {
        String date = textOrNull(item, "date_published");
        if (date == null) {
            date = textOrNull(item, "date_modified");
        }
        if (date == null) {
            return null;
        }
        // 先尝试 ISO 8601 (RSSHub JSON feed 默认)
        try {
            return LocalDateTime.ofInstant(Instant.parse(date), ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
            // 继续 RFC 2822 fallback
        }
        try {
            return LocalDateTime.parse(date, RFC_2822);
        } catch (DateTimeParseException e) {
            log.warn("无法解析 publishedAt, 留空: date={}, error={}", date, e.getMessage());
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }
}
