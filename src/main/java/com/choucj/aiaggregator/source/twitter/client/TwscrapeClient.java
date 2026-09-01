package com.choucj.aiaggregator.source.twitter.client;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.TwscrapeProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * twscrape CLI 推文补全客户端 — 通过 {@code ProcessBuilder} 子进程调用 twscrape Python 工具.
 *
 * <p>调用 {@code twscrape tweet {url}} 命令拉取单条推文完整字段(text / 互动数 / 图片 URL).
 * 与 {@link FxTwitterClient} 形成双链降级: twscrape(主, 已登录账号授权抓取) → FxTwitter(备, 公共实例).
 *
 * <p><b>返回的 Tweet 仅填充补全字段</b>(content + 互动数 + imageUrls), id 字段为入参的 tweetId,
 * 其余 RSSHub 源字段(author / summary / url / publishedAt) 留空 / 零值, 由
 * {@link com.choucj.aiaggregator.source.twitter.TwitterSource#enrichTweet(Tweet)}
 * 用 {@link Tweet#toBuilder()} 合并.
 *
 * <p><b>异常映射决策表:</b>
 * <table>
 *   <tr><th>CLI 状态</th><th>映射异常</th><th>处理</th></tr>
 *   <tr><td>退出码 0 + 有效 JSON</td><td>(正常)</td><td>返回补全 Tweet</td></tr>
 *   <tr><td>{@code enabled=false}</td><td>{@link RetryableException}</td><td>由编排层捕获降级到 FxTwitter</td></tr>
 *   <tr><td>超时未完成</td><td>{@link RetryableException}</td><td>destroyForcibly 后抛出</td></tr>
 *   <tr><td>退出码非 0(账号问题 / 限流)</td><td>{@link RetryableException}</td><td>twscrape 内部可恢复</td></tr>
 *   <tr><td>可执行文件不存在 / 启动失败</td><td>{@link NonRetryableException}</td><td>环境配置错误, 重试无解</td></tr>
 *   <tr><td>stdout 为空</td><td>{@link NonRetryableException}</td><td>响应畸形</td></tr>
 *   <tr><td>JSON 解析失败</td><td>{@link NonRetryableException}</td><td>响应畸形</td></tr>
 *   <tr><td>stdout 超过 5MB</td><td>{@link NonRetryableException}</td><td>防御性, 避免内存爆炸</td></tr>
 *   <tr><td>线程中断</td><td>{@link RetryableException}</td><td>恢复中断标志后抛出</td></tr>
 * </table>
 *
 * <p><b>字段映射:</b> twscrape stdout JSON 字段名({@code text/replyCount/retweetCount/likeCount})
 * 与 FxTwitter({@code text/replies/retweets/likes})不同 — 本类独立实现解析.
 *
 * <p>架构 delta (Story 2.2b):
 * <ul>
 *   <li>不使用 {@code shell=true}, 避免 command injection(URL 来自 RSSHub, 已 URL 编码)</li>
 *   <li>{@code finally} 块强制 {@code destroyForcibly}, 防止僵尸进程</li>
 *   <li>{@code redirectErrorStream(true)} 合并 stderr 到 stdout 便于排查</li>
 *   <li>stdout 读取限制 5MB 上限, 防御性编程</li>
 * </ul>
 */
@Slf4j
@Component
public class TwscrapeClient {

    /** stdout 最大读取字节数(5MB) — 防御性, twscrape 单条推文响应通常 <50KB. */
    private static final int MAX_STDOUT_BYTES = 5 * 1024 * 1024;

    private final TwscrapeProperties properties;
    private final ObjectMapper objectMapper;

    public TwscrapeClient(TwscrapeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 twscrape CLI 抓取单条推文补全字段.
     *
     * @param tweetId 推文 ID(纯数字, 用于日志与异常上下文)
     * @param url     推文完整 URL({@code https://twitter.com/{user}/status/{id}}), 传给 twscrape tweet 子命令
     * @return 补全 Tweet(id + content + 互动数 + imageUrls)
     * @throws RetryableException   twscrape 禁用 / 超时 / 退出码非 0 / 线程中断
     * @throws NonRetryableException 可执行文件不存在 / stdout 空 / JSON 解析失败 / stdout 超 5MB
     */
    public Tweet fetchTweetDetail(String tweetId, String url) {
        if (!properties.isEnabled()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape 已禁用, 应直接降级到 FxTwitter: tweetId=" + tweetId);
        }
        log.debug("调用 twscrape 补全: tweetId={}, url={}", tweetId, url);

        List<String> command = List.of(properties.getExecutable(), "tweet", url);
        Process process = null;
        try {
            process = startProcess(command);
            // CRITICAL (Story 2.2b CR-W1 fix): waitFor 必须先于 readStream.
            // 旧实现 readStream 在前 — 进程挂起时 stdout 永不关闭, readStream 阻塞,
            // 120s 超时永远不触发, 卡死 ContentScheduler 单线程调度.
            // waitFor 完成后进程已退出(或被 destroyForcibly), pipe buffer 中已写入数据仍可读.
            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "twscrape 超时: tweetId=" + tweetId + ", timeout=" + properties.getTimeoutSeconds() + "s");
            }
            int exitCode = process.exitValue();
            String stdout = readStream(process.getInputStream(), tweetId);
            if (exitCode != 0) {
                throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "twscrape 退出码非 0: code=" + exitCode + ", tweetId=" + tweetId
                                + ", stdout=" + truncate(stdout, 500));
            }
            return parseResponse(stdout, tweetId);
        } catch (IOException e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape 可执行文件不存在或无法启动: " + e.getMessage() + ", tweetId=" + tweetId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape 调用线程中断: tweetId=" + tweetId, e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 启动 twscrape 子进程. package-private 以便单元测试通过 {@code @Spy} override 跳过真实进程启动.
     *
     * @param command 命令列表(可执行文件 + 子命令 + 参数)
     * @return 已启动的 {@link Process}
     * @throws IOException 可执行文件不存在 / 启动失败
     */
    Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /**
     * 解析 twscrape stdout JSON 为补全 Tweet.
     * <p>twscrape 响应结构(简化): {@code { "id": "...", "text": "...", "replyCount": N,
     * "retweetCount": N, "likeCount": N, "photos": [ {"url": "..."}, ... ] }}.
     */
    Tweet parseResponse(String stdout, String tweetId) {
        if (stdout == null || stdout.isBlank()) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape stdout 为空: tweetId=" + tweetId);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stdout);
        } catch (Exception e) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape stdout JSON 解析失败: tweetId=" + tweetId + ", error=" + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "twscrape stdout JSON 为 null: tweetId=" + tweetId);
        }
        String content = textOrNull(root, "text");
        int replyCount = intOrZero(root, "replyCount");
        int retweetCount = intOrZero(root, "retweetCount");
        int likeCount = intOrZero(root, "likeCount");
        List<String> imageUrls = extractPhotos(root);
        List<TweetMedia> media = extractMedia(root);
        JsonNode quote = firstObject(root, "quotedStatus", "quoted_status", "quote", "quotedTweet");

        Tweet enriched = Tweet.builder()
                .id(tweetId)
                .content(content)
                .rawText(content)
                .formattedText(content)
                .replyCount(replyCount)
                .retweetCount(retweetCount)
                .likeCount(likeCount)
                .imageUrls(imageUrls)
                .media(media)
                .links(extractLinks(root))
                .mentions(extractMentions(root))
                .quotedTweetUrl(parseQuotedTweetUrl(quote))
                .quotedTweetText(quote == null ? null : firstText(quote, "text", "fullText", "full_text"))
                .build();
        log.debug("twscrape 补全成功: tweetId={}, replies={}, retweets={}, likes={}, images={}",
                tweetId, replyCount, retweetCount, likeCount, imageUrls.size());
        return enriched;
    }

    /**
     * 读取进程 stdout 流, 限制最大 {@link #MAX_STDOUT_BYTES} 字节, 超限抛 NonRetryable.
     * <p>用 {@code read} 逐字节累计避免一次性把巨型响应加载到内存.
     */
    private String readStream(InputStream is, String tweetId) throws IOException {
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        List<byte[]> chunks = new ArrayList<>();
        int read;
        while ((read = is.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_STDOUT_BYTES) {
                throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "twscrape stdout 超过 " + MAX_STDOUT_BYTES + " 字节: tweetId=" + tweetId);
            }
            chunks.add(java.util.Arrays.copyOf(buffer, read));
        }
        byte[] all = new byte[totalRead];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, offset, chunk.length);
            offset += chunk.length;
        }
        return new String(all, java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<String> extractPhotos(JsonNode root) {
        JsonNode photos = root.get("photos");
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

    private List<TweetMedia> extractMedia(JsonNode root) {
        List<TweetMedia> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        JsonNode photos = root.get("photos");
        if (photos != null && photos.isArray()) {
            int order = 0;
            for (JsonNode photo : photos) {
                addIfNew(result, seenIds, parseMediaItem(photo, TweetMediaType.PHOTO, order++));
            }
        }
        JsonNode media = root.get("media");
        if (media != null && media.isArray()) {
            int order = result.size();
            for (JsonNode item : media) {
                addIfNew(result, seenIds, parseMediaItem(item, parseMediaType(firstText(item, "type")), order++));
            }
        }
        return result;
    }

    private TweetMedia parseMediaItem(JsonNode item, TweetMediaType type, int order) {
        JsonNode videoInfo = item.get("video_info");
        List<TweetMediaVariant> variants = parseVariants(firstArray(videoInfo, "variants"));
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
                .provider("twscrape")
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

    private List<String> extractLinks(JsonNode root) {
        JsonNode urls = firstArray(firstObject(root, "entities"), "urls");
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<String> links = new ArrayList<>();
        for (JsonNode url : urls) {
            String value = firstText(url, "expanded_url", "expandedUrl", "url");
            if (value != null) {
                links.add(value);
            }
        }
        return links;
    }

    private List<String> extractMentions(JsonNode root) {
        JsonNode userMentions = firstArray(firstObject(root, "entities"), "user_mentions");
        if (userMentions == null || userMentions.isEmpty()) {
            return List.of();
        }
        List<String> mentions = new ArrayList<>();
        for (JsonNode userMention : userMentions) {
            String value = firstText(userMention, "screen_name", "screenName", "username", "userName");
            if (value != null) {
                mentions.add(value.startsWith("@") ? value : "@" + value);
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

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(" + s.length() + " chars)";
    }
}
