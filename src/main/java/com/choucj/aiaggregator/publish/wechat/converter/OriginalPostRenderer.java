package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Story 8.5: 原帖复现草稿确定性渲染器.
 *
 * <p>把 X 原帖的文本三源、链接补遗、引用上下文和已准备媒体状态，按确定性规则拼接为
 * 微信公众号草稿正文 HTML，并派生确定性 title/digest。渲染逻辑是纯函数式拼接，
 * <b>零 LLM 依赖</b> (AC9, AD-3)、零触网 (AD-2 — 只消费调用方传入的已准备媒体状态，
 * 不下载、不上传、不读 sidecar)。
 *
 * <p><b>核心规则 (对应 AC):</b>
 * <ul>
 *   <li>AC1 文本管线: formattedText → rawText → content 优先级；{@code \r\n}/{@code \r}
 *       归一为 {@code \n}；连续 ≥2 个 {@code \n} → 段落边界 {@code <p>}，段内单
 *       {@code \n} → {@code <br/>}；所有外部文本先 escapeHtml 再拼标签</li>
 *   <li>AC2 img src 只允许 {@code TweetMedia.wechatUrl} 非空值 (8.4 幂等契约:
 *       可用性判据 = wechatUrl 非空，不看 uploadStatus)；publishability BLOCKED 不嵌入</li>
 *   <li>AC3 媒体按 preparedMedia 列表顺序渲染，每个媒体二选一: 嵌入或降级提示，
 *       无媒体静默消失 (NFR2)</li>
 *   <li>AC4 引用推 blockquote + 「查看引用推」链接；quotedTweetUrl 空时不渲染</li>
 *   <li>AC5 VIDEO/GIF/UNKNOWN 按 8.2 决策表降级文案 + 原文链接</li>
 *   <li>AC6 PHOTO 无 wechatUrl 时人可读文案 + failureReason 摘要 (单行化 +
 *       truncateForLog 120cp + 转义；8.4 已脱敏，本渲染器不重复脱敏)</li>
 *   <li>AC7 推文级致命失败 (tweet null / id blank / accessStatus 受限 /
 *       三源全 blank) 前置校验抛 NonRetryableException，镜像 7.5 gate T1 语义；
 *       其余单媒体问题一律降级 (AD-5)</li>
 *   <li>AC8 确定性 footer + {@link #toArticle} 组装</li>
 * </ul>
 *
 * <p><b>不接线决策 (scope_decision):</b> 本类不实现 {@code OriginalPostGenerationGateway}
 * (避免与 Pending Bean 注入歧义)、不接线 TwitterProcessor — Article.content 进入发布链会被
 * ArticleToWxArticleConverter 的 commonmark P0 sanitize 实体转义破坏，发布侧模式感知归 Story 8.6。
 *
 * <p><b>D3 警示 (nullable → 判定前 null-check):</b> {@code @Builder.Default} 不走 Jackson
 * 反序列化，老 sidecar 构造的 TweetMedia 的 type/publishability 可能为 null —
 * type null → UNKNOWN 降级；publishability null → 可嵌入；列表含 null 元素 → 按 UNKNOWN
 * 降级不静默跳过；innovationScore 恒写 0 禁止拆箱读取。
 *
 * <p>引用源: Story 8.5(创建) / ARCHITECTURE-SPINE AD-2/AD-3/AD-4/AD-5/AD-9 /
 * lessons-learned N2、N4、B2、W11、D3。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class OriginalPostRenderer {

    /** 微信标题上限 (codepoint, AC10)。 */
    private static final int TITLE_MAX_CODEPOINTS = 64;

    /** 微信摘要上限 (codepoint, AC10)。 */
    private static final int DIGEST_MAX_CODEPOINTS = 120;

    /** 引用推文本展示截断上限 (codepoint, AC4)。 */
    private static final int QUOTED_TEXT_MAX_CODEPOINTS = 200;

    /** 媒体 failureReason 摘要上限 (codepoint, AC6)。 */
    private static final int FAILURE_REASON_MAX_CODEPOINTS = 120;

    /** 日志中 title 截断上限 (codepoint, W11/N4)。 */
    private static final int LOG_TITLE_MAX_CODEPOINTS = 50;

    /** tweetId 合法字符 (镜像 TwitterProcessor.buildDeterministicArticleId 正则语义, AC8)。 */
    private static final Pattern TWEET_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /** 8.2 spike §9 视频降级推荐文案 (AC5)。 */
    private static final String VIDEO_DEGRADE_COPY = "视频内容暂不支持稳定内嵌，请到原文查看或在公众号后台人工插入。";

    /** 8.2 spike §9 GIF 降级推荐文案 (AC5)。 */
    private static final String GIF_DEGRADE_COPY = "动图暂不走正式图片接口，请查看原文或人工替换为静态预览。";

    /** 未识别媒体类型 (含 type null / 列表 null 元素) 的降级文案 (AC5/T2.4)。 */
    private static final String UNKNOWN_TYPE_DEGRADE_COPY = "未识别的媒体类型，请人工核对原文。";

    /** publishability BLOCKED 的合规降级文案 (AC2)。 */
    private static final String BLOCKED_MEDIA_DEGRADE_COPY = "该媒体不适宜自动呈现，请到原文查看。";

    /** PHOTO 无 wechatUrl 且 failureReason 空时的固定文案 (AC6, UX-DR2)。 */
    private static final String PHOTO_FAILURE_DEFAULT_COPY = "图片未上传至微信";

    /** PHOTO 无 wechatUrl 时的人可读前缀 (AC6)。 */
    private static final String PHOTO_FAILURE_PREFIX = "图片未能呈现";

    /** 引用推文本缺失时的缺省文案 (AC4)。 */
    private static final String QUOTE_UNAVAILABLE_COPY = "(引用内容不可得)";

    /** tweet.url 缺失时的降级纯文案 — 不伪造 URL (T2.5)。 */
    private static final String NO_TWEET_URL_COPY = "请到 X 原帖查看。";

    /** title 无可用行时的兜底标题 (AC10)。 */
    private static final String FALLBACK_TITLE = "X 原帖";

    /**
     * 渲染原帖为微信草稿正文 HTML + 确定性 title/digest (AC1-AC7, AC9, AC10).
     *
     * <p>渲染管线顺序固定 (T2.3): 归一换行 → 选取文本源 → 段落/br 映射 → links 补遗 →
     * 引用块 → 媒体段 (按列表顺序逐项决策表) → footer。同输入必产出逐字节相同的 HTML (AC9)。
     *
     * @param tweet         原帖 (null / id blank 或含非法字符 / accessStatus 受限 /
     *                      三源全 blank 抛 NonRetryableException, AC7; 非法字符校验与
     *                      toArticle 统一，防伪造 id 注入日志, CR Round 1 patch#1)
     * @param preparedMedia 已准备的媒体状态 (由调用方从 sidecar 权威状态合并)；null/空
     *                      按纯文本推文处理；渲染器只认本参数，不回读 tweet.media 渲染图片
     * @return 确定性渲染结果
     * @throws NonRetryableException 推文级致命失败 (只含 tweetId + 原因标识, N4/B2)
     */
    public OriginalPostRenderResult render(Tweet tweet, List<TweetMedia> preparedMedia) {
        long startNanos = System.nanoTime();
        validateTweet(tweet);

        List<TweetMedia> mediaList = preparedMedia == null ? List.of() : preparedMedia;
        String textSource = normalizeNewlines(selectTextSource(tweet));

        String title = deriveTitle(tweet, textSource);
        String digest = deriveDigest(textSource, title);

        StringBuilder html = new StringBuilder();
        html.append(renderTextParagraphs(textSource));
        html.append(renderLinkAppendix(textSource, tweet.getLinks()));
        html.append(renderQuoteBlock(tweet));

        int embeddedImageCount = 0;
        int degradedMediaCount = 0;
        List<String> embeddedImageUrls = new ArrayList<>();
        for (TweetMedia media : mediaList) {
            if (isEmbeddablePhoto(media)) {
                String wechatUrl = media.getWechatUrl();
                html.append("<img src=\"").append(ArticleToWxArticleConverter.escapeHtml(wechatUrl))
                        .append("\"/>\n");
                embeddedImageCount++;
                embeddedImageUrls.add(wechatUrl);
            } else {
                html.append(renderDegradedMedia(media, tweet.getUrl())).append('\n');
                degradedMediaCount++;
            }
        }

        html.append(renderFooter(tweet));

        String htmlString = html.toString();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // W11 + N4: 单条 log.info 七要素，title 单行化 + 截断，不输出 HTML 正文/完整 URL
        log.info("原帖渲染完成: tweetId={}, mediaCount={}, embeddedImageCount={}, degradedMediaCount={}, "
                        + "htmlLength={}, 耗时={}ms, 标题={}",
                tweet.getId(), mediaList.size(), embeddedImageCount, degradedMediaCount,
                htmlString.length(), elapsedMs,
                TextTruncateUtil.truncateForLog(singleLine(title), LOG_TITLE_MAX_CODEPOINTS));

        return new OriginalPostRenderResult(title, digest, htmlString,
                embeddedImageCount, degradedMediaCount, embeddedImageUrls);
    }

    /**
     * 把渲染结果组装为 {@link Article} (AC8).
     *
     * <p>字段表: {@code id = "tw-" + tweetId} (tweetId 不匹配 {@code [A-Za-z0-9_-]+} 抛
     * NonRetryable，镜像 TwitterProcessor.buildDeterministicArticleId)、title/digest 取自
     * 渲染结果、content = html、{@code aiGenerated = false} (原帖复现非 AI 生成, AR8)、
     * {@code innovationScore = 0} (无 LLM 评分，禁止读取 tweet.innovationScore 拆箱, D3)、
     * originalUrl = tweet.url、createdAt = now、source = "来源:@" + author (author null 时
     * 「来源:X 原帖」)。
     *
     * <p><b>不在本方法做的事 (T4.2):</b> 不设 generationMode (字段不存在，8.6 决策)、
     * 不写 ArticleStatus、不进 publisher 链。
     *
     * @param tweet  原帖 (id 非法抛 NonRetryableException)
     * @param result 渲染结果
     * @return 组装完成的 Article
     * @throws NonRetryableException tweetId null/blank/含非法字符
     */
    public Article toArticle(Tweet tweet, OriginalPostRenderResult result) {
        if (tweet == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖 Article 组装失败: tweet=null");
        }
        if (result == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖 Article 组装失败: tweetId=" + tweet.getId() + ", reason=render result null");
        }
        String tweetId = tweet.getId();
        if (tweetId == null || tweetId.isBlank() || !TWEET_ID_PATTERN.matcher(tweetId).matches()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 tweetId: " + tweetId);
        }
        String author = tweet.getAuthor();
        String source = (author == null || author.isBlank())
                ? "来源:X 原帖"
                : "来源:@" + author;
        return Article.builder()
                .id("tw-" + tweetId)
                .title(result.title())
                .digest(result.digest())
                .content(result.html())
                .aiGenerated(false)
                .innovationScore(0)
                .originalUrl(tweet.getUrl())
                .createdAt(LocalDateTime.now())
                .source(source)
                .build();
    }

    // ===== AC7: 推文级致命输入前置校验 =====

    /**
     * 校验推文级致命输入 (AC7, 镜像 7.5 gate T1 BLOCKED 语义)。
     *
     * <p>message 遵守 B2 拼接式 + N4 (只含 tweetId + 原因标识，不含正文)。
     */
    private static void validateTweet(Tweet tweet) {
        if (tweet == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖渲染失败: tweet=null");
        }
        String tweetId = tweet.getId();
        if (tweetId == null || tweetId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖渲染失败: tweetId=" + tweetId + ", reason=blank tweet id");
        }
        if (!TWEET_ID_PATTERN.matcher(tweetId).matches()) {
            // N4: 非法 id 本体不进 message (可含换行等伪造内容，防日志注入)，只输出长度
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖渲染失败: tweetIdLength=" + tweetId.length() + ", reason=illegal tweet id characters");
        }
        if (tweet.hasStructuredAccessBlock()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖渲染失败: tweetId=" + tweetId + ", reason=source access blocked");
        }
        if (isBlank(tweet.getFormattedText()) && isBlank(tweet.getRawText()) && isBlank(tweet.getContent())) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "原帖渲染失败: tweetId=" + tweetId + ", reason=all text sources blank");
        }
    }

    // ===== AC1: 文本渲染管线 =====

    /** 选取文本源: formattedText → rawText → content (isBlank 判定, AC1)。 */
    private static String selectTextSource(Tweet tweet) {
        if (!isBlank(tweet.getFormattedText())) {
            return tweet.getFormattedText();
        }
        if (!isBlank(tweet.getRawText())) {
            return tweet.getRawText();
        }
        return tweet.getContent();
    }

    /**
     * 换行归一: {@code \r\n}/{@code \r} → {@code \n} (AC1)；Unicode 行分隔符
     * U+2028/U+2029/U+0085 一并归一 (CR Round 1 patch#7 — 否则段落/br 映射失效、
     * 不可见分隔符可进入 title/digest)。
     */
    private static String normalizeNewlines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n');
    }

    /**
     * 段落映射: 连续 ≥2 个 {@code \n} → 段落边界 {@code <p>...</p>}，段内单 {@code \n} →
     * {@code <br/>}；段落内容先 escapeHtml 再映射 (AC1)。
     */
    private static String renderTextParagraphs(String normalizedText) {
        if (normalizedText.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String paragraph : normalizedText.split("\\n{2,}")) {
            if (paragraph.isBlank()) {
                continue;
            }
            sb.append("<p>").append(ArticleToWxArticleConverter.escapeHtml(paragraph)
                    .replace("\n", "<br/>")).append("</p>\n");
        }
        return sb.toString();
    }

    /**
     * 链接补遗: links 中未被文本源包含的 URL (确定性 contains 判断) 追加补遗段，
     * 已在文本中的不重复列出；重复 URL 去重 (LinkedHashSet 保持首次出现顺序,
     * CR Round 1 patch#6)；非 http(s) scheme 的 URL 以转义纯文本呈现、不作为 href
     * (CR Round 1 patch#5, 防 {@code javascript:} 注入)。
     */
    private static String renderLinkAppendix(String normalizedText, List<String> links) {
        if (links == null || links.isEmpty()) {
            return "";
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String link : links) {
            if (link != null && !link.isBlank() && !normalizedText.contains(link)) {
                missing.add(link);
            }
        }
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<p><strong>原文链接补遗:</strong></p>\n");
        for (String url : missing) {
            String escaped = ArticleToWxArticleConverter.escapeHtml(url);
            if (isSafeHttpUrl(url)) {
                sb.append("<p><a href=\"").append(escaped).append("\">").append(escaped)
                        .append("</a></p>\n");
            } else {
                sb.append("<p>").append(escaped).append("</p>\n");
            }
        }
        return sb.toString();
    }

    // ===== AC4: 引用推上下文 =====

    /**
     * 引用块: quotedTweetUrl 非空时渲染 blockquote，内容 = quotedTweetText
     * (转义 + 截断 ≤200 codepoints，段内换行映射 {@code <br/>} 与主文本管线一致,
     * CR Round 1 patch#2) 或缺省文案 + 「查看引用推」链接；quotedTweetUrl 为
     * null/blank 时不渲染 (graceful, AC4)；quoteUrl 非 http(s) 时不渲染锚点
     * (CR Round 1 patch#5, 防 {@code javascript:} 注入)。
     */
    private static String renderQuoteBlock(Tweet tweet) {
        String quoteUrl = tweet.getQuotedTweetUrl();
        if (quoteUrl == null || quoteUrl.isBlank()) {
            return "";
        }
        String quotedText = tweet.getQuotedTweetText();
        String body;
        if (isBlank(quotedText)) {
            body = QUOTE_UNAVAILABLE_COPY;
        } else {
            body = ArticleToWxArticleConverter.escapeHtml(TextTruncateUtil.truncateByCodePoints(
                    normalizeNewlines(quotedText), QUOTED_TEXT_MAX_CODEPOINTS))
                    .replace("\n", "<br/>");
        }
        String linkPart = isSafeHttpUrl(quoteUrl)
                ? "<p><a href=\"" + ArticleToWxArticleConverter.escapeHtml(quoteUrl)
                        + "\">查看引用推</a></p>\n"
                : "";
        return "<blockquote><p>" + body + "</p>\n" + linkPart + "</blockquote>\n";
    }

    // ===== AC2/AC3/AC5/AC6: 媒体决策表 =====

    /**
     * 可嵌入判定: 非 BLOCKED + PHOTO + wechatUrl 非空 (AC2)。
     *
     * <p>publishability null → 按 UNKNOWN 可嵌入 (D3 警示 #1)；wechatUrl 判据与
     * uploadStatus 无关 (8.4 幂等契约: SKIPPED 也可能携带 URL)。
     */
    private static boolean isEmbeddablePhoto(TweetMedia media) {
        if (media == null) {
            return false;
        }
        if (media.getPublishability() == PublishabilityStatus.BLOCKED) {
            return false;
        }
        return media.getType() == TweetMediaType.PHOTO
                && media.getWechatUrl() != null
                && !media.getWechatUrl().isBlank();
    }

    /**
     * 降级渲染 (T2.4 决策表): BLOCKED → 合规降级；PHOTO 无 URL → 人可读文案 +
     * failureReason 摘要 (AC6)；VIDEO/GIF → 8.2 文案；UNKNOWN (含 null 元素/type null) →
     * 未识别提示。每个降级项附原文链接 (tweet.url 空时纯文案，不伪造 URL, T2.5)。
     */
    private static String renderDegradedMedia(TweetMedia media, String tweetUrl) {
        String copy;
        if (media != null && media.getPublishability() == PublishabilityStatus.BLOCKED) {
            copy = BLOCKED_MEDIA_DEGRADE_COPY;
        } else if (media != null && media.getType() == TweetMediaType.PHOTO) {
            copy = PHOTO_FAILURE_PREFIX + ": " + photoFailureSummary(media);
        } else if (media != null && media.getType() == TweetMediaType.VIDEO) {
            copy = VIDEO_DEGRADE_COPY;
        } else if (media != null && media.getType() == TweetMediaType.GIF) {
            copy = GIF_DEGRADE_COPY;
        } else {
            copy = UNKNOWN_TYPE_DEGRADE_COPY;
        }
        return "<p>" + copy + " " + sourceLink(tweetUrl) + "</p>";
    }

    /**
     * PHOTO 失败 reason 摘要 (AC6): 单行化 + truncateForLog 120cp + HTML 转义；
     * reason null/blank 时用固定文案。failureReason 已由 8.4 做 {@code <url>} 脱敏，
     * 本方法不重复脱敏但必须转义。
     */
    private static String photoFailureSummary(TweetMedia media) {
        String reason = media.getFailureReason();
        if (isBlank(reason)) {
            return PHOTO_FAILURE_DEFAULT_COPY;
        }
        return ArticleToWxArticleConverter.escapeHtml(
                TextTruncateUtil.truncateForLog(singleLine(reason), FAILURE_REASON_MAX_CODEPOINTS));
    }

    /**
     * 原文链接: tweet.url 为 http(s) URL 时 {@code <a>} (href 转义)，空或非安全
     * scheme 时纯文案不伪造 URL (T2.5; scheme 白名单 CR Round 1 patch#5)。
     */
    private static String sourceLink(String tweetUrl) {
        if (!isSafeHttpUrl(tweetUrl)) {
            return NO_TWEET_URL_COPY;
        }
        return "<a href=\"" + ArticleToWxArticleConverter.escapeHtml(tweetUrl) + "\">原文链接</a>";
    }

    // ===== AC8: footer =====

    /**
     * 确定性 footer: {@code <hr/>} + 来源行 (author 空时省略 by 段) +
     * tweet.url 非空时的「原文链接」锚点 (href 转义, AC8)。
     */
    private static String renderFooter(Tweet tweet) {
        String author = tweet.getAuthor();
        StringBuilder sb = new StringBuilder("<hr/>\n");
        if (author == null || author.isBlank()) {
            sb.append("<p><strong>来源:</strong> X 原帖</p>\n");
        } else {
            sb.append("<p><strong>来源:</strong> X 原帖 by @")
                    .append(ArticleToWxArticleConverter.escapeHtml(author)).append("</p>\n");
        }
        String url = tweet.getUrl();
        if (isSafeHttpUrl(url)) {
            sb.append("<p><a href=\"").append(ArticleToWxArticleConverter.escapeHtml(url))
                    .append("\">原文链接</a></p>\n");
        }
        return sb.toString();
    }

    // ===== AC10: title/digest 确定性派生 =====

    /**
     * title = 文本源第一个非空行 strip 后截断 ≤64 codepoints (N2 码点安全)；
     * 无可用行时 fallback「X 原帖」+ author 非空时 {@code · @{author}}，
     * fallback 分支同样截断 ≤64 codepoints (CR Round 1 patch#4 — 当前因 AC7
     * 三源全 blank 抛出而不可达，防御性保证 record 契约恒成立)。
     */
    private static String deriveTitle(Tweet tweet, String normalizedText) {
        for (String line : normalizedText.split("\\n")) {
            if (!line.isBlank()) {
                return TextTruncateUtil.truncateByCodePoints(line.strip(), TITLE_MAX_CODEPOINTS);
            }
        }
        String author = tweet.getAuthor();
        if (author == null || author.isBlank()) {
            return FALLBACK_TITLE;
        }
        return TextTruncateUtil.truncateByCodePoints(
                FALLBACK_TITLE + " · @" + author, TITLE_MAX_CODEPOINTS);
    }

    /**
     * digest = 文本源换行折叠为空格 (连续换行折叠为单个空格) 后截断 ≤120 codepoints；
     * blank 时用 title 兜底 (AC10)。
     */
    private static String deriveDigest(String normalizedText, String title) {
        String folded = TextTruncateUtil.truncateByCodePoints(
                normalizedText.replaceAll("\\n+", " ").strip(), DIGEST_MAX_CODEPOINTS);
        return folded.isBlank() ? title : folded;
    }

    // ===== 小工具 =====

    /** null 安全 isBlank。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * href 安全校验: 仅允许 http/https scheme (大小写不敏感) — escapeHtml 对
     * {@code javascript:alert(1)} 这类无特殊字符的 scheme 无效，URL 进 href 前必须
     * 先过本白名单，不满足走纯文案降级 (CR Round 1 patch#5)。
     */
    private static boolean isSafeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 换行/回车折叠为单空格 (日志与 reason 摘要单行化，镜像 8.4 patch#4)。 */
    private static String singleLine(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
