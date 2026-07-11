package com.choucj.aiaggregator.source.github.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.github.config.GitHubProperties;
import com.choucj.aiaggregator.source.github.config.GitHubProperties.Trending;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GitHub REST API v3 客户端实现 (spike-4.1 §2.1 决策: Search API 替代官方无 /trending 端点).
 *
 * <p><b>核心流程:</b>
 * <ol>
 *   <li>渲染 query: {@code queryTemplate.replace("{date}", ...).replace("{language}", ...)} (B2 模式)</li>
 *   <li>调用 {@code GET /search/repositories?q={query}&sort=stars&order=desc&per_page={topN}}</li>
 *   <li>请求头: {@code Authorization: Bearer {token}} (若 token 非空) + {@code Accept} +
 *       {@code X-GitHub-Api-Version}</li>
 *   <li>解析 JSON: items[].{id, full_name, name, description, language, stargazers_count,
 *       forks_count, html_url} → {@link GitHubRepo}</li>
 * </ol>
 *
 * <p><b>异常映射 (复用 Story 2.1 RSSHubClient 决策表, spike-4.1 §3.4 B1 模式):</b>
 * <table>
 *   <tr><th>HTTP 响应/异常</th><th>映射异常</th><th>处理</th></tr>
 *   <tr><td>200 + 有效 JSON</td><td>(正常)</td><td>返回 {@code List<GitHubRepo>}</td></tr>
 *   <tr><td>200 + 空 items</td><td>(正常)</td><td>返回空列表 + info 日志</td></tr>
 *   <tr><td>200 + JSON 解析失败</td><td>(不抛)</td><td>warn + 返回空列表</td></tr>
 *   <tr><td>403 + X-RateLimit-Remaining:0</td><td>{@link RetryableException}</td><td>warn + 抛 (上层跳过本轮)</td></tr>
 *   <tr><td>429</td><td>{@link RetryableException}</td><td>warn + 抛</td></tr>
 *   <tr><td>5xx</td><td>{@link RetryableException}</td><td>warn + 抛</td></tr>
 *   <tr><td>4xx (除 403-rate-limit / 429)</td><td>{@link NonRetryableException}</td><td>error + 抛</td></tr>
 *   <tr><td>ResourceAccessException (超时/连接)</td><td>{@link RetryableException}</td><td>warn + 抛</td></tr>
 * </table>
 *
 * <p><b>引用模式 (lessons-learned.md §1.5):</b>
 * <ul>
 *   <li>B2 — query-template 占位符用 String.replace, 禁用 String.format (query 含 ':''+')</li>
 *   <li>W1+W2 — RestClient 调用显式 catch HttpClientErrorException / HttpServerErrorException /
 *       ResourceAccessException, 不需 catch RuntimeException 兜底</li>
 *   <li>W11 — log 含 query + language + lookbackDays + 返回条数 (N4: 不打印响应体)</li>
 *   <li>N4 — 异常 message 仅含 endpoint + statusCode + 简短 reason, 不含响应正文</li>
 *   <li>D3 — {@code GitHubRepo.stars/forks} 是 primitive int, JSON 解析用 {@code asInt(0)} 默认值</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = {"features.github.enabled", "feature-flags.github.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class GitHubClientImpl implements GitHubClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String SEARCH_PATH = "/search/repositories";
    private static final String ACCEPT_HEADER = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int MAX_README_CODE_POINTS = 1024 * 1024;

    private final GitHubProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean unauthenticatedWarningLogged = new AtomicBoolean(false);

    /**
     * 构造器注入 — 显式而非 {@code @RequiredArgsConstructor}, 因为 {@code @Qualifier} 需要直接标注在
     * 构造器参数上才能让 Spring 精确解析多个 {@link RestClient} Bean
     * (github / rsshub / fxtwitter / 默认).
     *
     * @param properties   GitHub 配置
     * @param restClient   注入名为 {@code githubRestClient} 的 RestClient
     * @param objectMapper Jackson JSON 解析器
     */
    public GitHubClientImpl(GitHubProperties properties,
                            @Qualifier("githubRestClient") RestClient restClient,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GitHubRepo> fetchTrending() {
        if (!properties.isEnabled()) {
            log.info("GitHub 已禁用, 跳过抓取");
            return List.of();
        }

        Trending trending = properties.getTrending();
        String date = LocalDate.now().minusDays(trending.getLookbackDays()).format(ISO_DATE);
        String query = renderQuery(trending, date);
        URI uri = buildSearchUri(query, trending.getTopN());

        log.info("开始抓取 GitHub Trending: query={}, language={}, lookbackDays={}, topN={}",
                query, trending.getLanguage(), trending.getLookbackDays(), trending.getTopN());

        String body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .headers(this::applyCommonHeaders)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("GitHub 限流 (429): endpoint={}, query={}", SEARCH_PATH, query);
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 限流 (429): query=" + query, e);
        } catch (HttpClientErrorException e) {
            String remaining = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("X-RateLimit-Remaining")
                    : null;
            if (isRateLimited(e, remaining)) {
                String reset = getRateLimitReset(e);
                log.warn("GitHub rate limit exhausted: endpoint={}, query={}, reset={}",
                        SEARCH_PATH, query, reset);
                throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "GitHub rate limit exhausted, reset at " + reset + ": query=" + query, e);
            }
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 客户端错误 (" + e.getStatusCode() + "): query=" + query, e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 服务端错误 (" + e.getStatusCode() + "): query=" + query, e);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 连接失败: query=" + query, e);
        }

        List<GitHubRepo> repos = parseResponse(body, query);
        log.info("GitHub Trending 抓取成功: query={}, 返回条数={}", query, repos.size());
        return repos;
    }

    @Override
    public String fetchReadme(String owner, String repo) {
        if (!properties.isEnabled()) {
            log.info("GitHub 已禁用, 跳过 README 抓取");
            return null;
        }
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(repo, "repo must not be null");

        long started = System.currentTimeMillis();
        URI uri = buildReadmeUri(owner, repo);
        String endpoint = "/repos/" + owner + "/" + repo + "/readme";

        String body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .headers(this::applyCommonHeaders)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("GitHub README 不存在 (404): owner={}, repo={}", owner, repo);
            return null;
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("GitHub 限流 (429): endpoint={}", endpoint);
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 限流 (429): endpoint=" + endpoint, e);
        } catch (HttpClientErrorException e) {
            String remaining = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("X-RateLimit-Remaining")
                    : null;
            if (isRateLimited(e, remaining)) {
                String reset = getRateLimitReset(e);
                log.warn("GitHub rate limit exhausted: endpoint={}, reset={}", endpoint, reset);
                throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "GitHub rate limit exhausted, reset at " + reset + ": endpoint=" + endpoint, e);
            }
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 客户端错误 (" + e.getStatusCode() + "): endpoint=" + endpoint, e);
        } catch (HttpServerErrorException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 服务端错误 (" + e.getStatusCode() + "): endpoint=" + endpoint, e);
        } catch (ResourceAccessException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 连接失败: endpoint=" + endpoint, e);
        } catch (RuntimeException e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub README 调用异常: endpoint=" + endpoint, e);
        }

        String readme = decodeReadme(body, owner, repo);
        if (readme == null) {
            return null;
        }

        int readmeCodePoints = readme.codePointCount(0, readme.length());
        if (readmeCodePoints > MAX_README_CODE_POINTS) {
            log.warn("GitHub README 超过 1MB 直接拒绝: owner={}, repo={}, length={}",
                    owner, repo, readmeCodePoints);
            return null;
        }
        int maxCodePoints = properties.getReadme().getMaxSizeKb() * 1024;
        String truncated = truncateByCodePoints(readme, maxCodePoints);
        int truncatedCodePoints = truncated.codePointCount(0, truncated.length());

        long elapsed = System.currentTimeMillis() - started;
        if (truncatedCodePoints < readmeCodePoints) {
            log.warn("GitHub README 截断: owner={}, repo={}, original={}, truncated={}",
                    owner, repo, readmeCodePoints, truncatedCodePoints);
        }
        log.info("GitHub README 抓取成功: owner={}, repo={}, 字符数={}, 耗时={}ms",
                owner, repo, truncatedCodePoints, elapsed);
        return truncated;
    }

    URI buildReadmeUri(String owner, String repo) {
        String encodedOwner = UriUtils.encodePathSegment(owner, StandardCharsets.UTF_8);
        String encodedRepo = UriUtils.encodePathSegment(repo, StandardCharsets.UTF_8);
        return URI.create(API_BASE + "/repos/" + encodedOwner + "/" + encodedRepo + "/readme");
    }

    /**
     * 解码 GitHub README 响应 (base64 MIME 解码 → UTF-8 String).
     *
     * <p>响应字段:
     * <ul>
     *   <li>{@code content}: base64 编码的 README 全文 (GitHub 注入 {@code \n} 换行)</li>
     *   <li>{@code encoding}: 应为 {@code "base64"}</li>
     *   <li>{@code size}: 原始字节数</li>
     * </ul>
     *
     * <p>容错:
     * <ul>
     *   <li>响应空 / JSON 解析失败: {@code RetryableException}</li>
     *   <li>{@code content} 字段缺失 / null: warn + 返 null</li>
     *   <li>base64 解码失败 ({@link IllegalArgumentException}): warn + 返 null</li>
     * </ul>
     */
    String decodeReadme(String body, String owner, String repo) {
        if (body == null || body.isBlank()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub README 响应为空: owner=" + owner + ", repo=" + repo);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub README 响应 JSON 解析失败: owner=" + owner + ", repo=" + repo, e);
        }
        JsonNode encodingNode = root.get("encoding");
        if (encodingNode == null || !encodingNode.isTextual()
                || !"base64".equalsIgnoreCase(encodingNode.asText())) {
            log.warn("GitHub README 响应 encoding 非 base64: owner={}, repo={}", owner, repo);
            return null;
        }
        JsonNode sizeNode = root.get("size");
        if (sizeNode != null && sizeNode.canConvertToLong()
                && sizeNode.asLong() > MAX_README_CODE_POINTS) {
            log.warn("GitHub README 响应 size 超过 1MB, 解码前拒绝: owner={}, repo={}, size={}",
                    owner, repo, sizeNode.asLong());
            return null;
        }
        JsonNode contentNode = root.get("content");
        if (contentNode == null || contentNode.isNull() || !contentNode.isTextual()) {
            log.warn("GitHub README 响应缺少 content 字段: owner={}, repo={}", owner, repo);
            return null;
        }
        String encoded = contentNode.asText();
        if (encoded.isBlank()) {
            log.warn("GitHub README content 字段为空: owner={}, repo={}", owner, repo);
            return null;
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("GitHub README base64 解码失败: owner={}, repo={}, error={}",
                    owner, repo, e.getMessage());
            return null;
        }
    }

    /**
     * 按 code point 截断 (N2 + R3-1 模式).
     *
     * <p>项目模式 (Story 3.2 ArticleToWxArticleConverter L57 决策): {@code SingleModelRewriter#truncateByCodePoints}
     * 仍为 package-private, 跨包调用时在调用方内联实现 4 行 (YAGNI, 不为单调用点提升可见性).
     */
    static String truncateByCodePoints(String content, int maxCodePoints) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int total = content.codePointCount(0, content.length());
        if (total <= maxCodePoints) {
            return content;
        }
        int endIndex = content.offsetByCodePoints(0, maxCodePoints);
        return content.substring(0, endIndex);
    }

    String renderQuery(Trending trending, String date) {
        return trending.getQueryTemplate()
                .replace("+", " ")
                .replace("{date}", date)
                .replace("{language}", trending.getLanguage());
    }

    URI buildSearchUri(String query, int topN) {
        String encodedQuery = UriUtils.encodeQueryParam(query, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%2B");
        return URI.create(API_BASE + SEARCH_PATH
                + "?q=" + encodedQuery
                + "&sort=stars&order=desc"
                + "&per_page=" + topN);
    }

    private boolean isRateLimited(HttpClientErrorException e, String remaining) {
        if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return true;
        }
        if (!e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
            return false;
        }
        if ("0".equals(remaining)) {
            return true;
        }
        HttpHeaders headers = e.getResponseHeaders();
        if (headers != null && headers.containsKey(HttpHeaders.RETRY_AFTER)) {
            return true;
        }
        String body = e.getResponseBodyAsString();
        return body != null && body.toLowerCase().contains("rate limit");
    }

    private String getRateLimitReset(HttpClientErrorException e) {
        if (e.getResponseHeaders() == null) {
            return "unknown";
        }
        String reset = e.getResponseHeaders().getFirst("X-RateLimit-Reset");
        if (reset == null) {
            return "unknown";
        }
        try {
            return java.time.Instant.ofEpochSecond(Long.parseLong(reset)).toString();
        } catch (NumberFormatException ex) {
            return reset;
        }
    }

    private void applyCommonHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT_HEADER);
        headers.set("X-GitHub-Api-Version", API_VERSION);
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken());
        } else if (unauthenticatedWarningLogged.compareAndSet(false, true)) {
            log.warn("GitHub token 未配置, 降级为未认证调用 (Search API 10/min)");
        }
    }

    /**
     * 解析 GitHub Search API JSON 响应为 {@code List<GitHubRepo>}.
     *
     * <p>JSON 解析失败不抛异常, 返回空列表 + warn 日志 (实例视为可用, 不切换).
     * items 为空返回空列表 + info 日志.
     *
     * <p><b>D3 模式:</b> {@code stargazers_count} / {@code forks_count} 用 {@code asInt(0)}
     * 避免 NPE (GitHubRepo.stars/forks 是 primitive int).
     */
    List<GitHubRepo> parseResponse(String body, String query) {
        if (body == null || body.isBlank()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 响应为空: query=" + query);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GitHub 响应 JSON 解析失败: query=" + query, e);
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            log.info("GitHub 响应 items 为空: query={}", query);
            return List.of();
        }
        List<GitHubRepo> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            GitHubRepo repo = parseRepo(item);
            if (repo != null) {
                result.add(repo);
            }
        }
        return result;
    }

    private GitHubRepo parseRepo(JsonNode item) {
        String fullName = textOrNull(item, "full_name");
        if (fullName == null) {
            log.warn("GitHub item 缺少 full_name 字段, 跳过");
            return null;
        }
        String[] parts = fullName.split("/", 2);
        String name = parts.length == 2 ? parts[1] : fullName;
        String htmlUrl = textOrNull(item, "html_url");

        return GitHubRepo.builder()
                .id(textOrZero(item, "id"))
                .fullName(fullName)
                .name(name)
                .description(textOrNull(item, "description"))
                .language(textOrNull(item, "language"))
                .stars(item.path("stargazers_count").asInt(0))
                .forks(item.path("forks_count").asInt(0))
                .readmeUrl(htmlUrl != null ? htmlUrl + "#readme" : null)
                .url(htmlUrl)
                .build();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        String text = child.asText();
        return text.isBlank() ? null : text;
    }

    private String textOrZero(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return "0";
        }
        if (child.isTextual()) {
            return child.asText();
        }
        if (child.isNumber()) {
            return String.valueOf(child.asLong());
        }
        return "0";
    }
}
