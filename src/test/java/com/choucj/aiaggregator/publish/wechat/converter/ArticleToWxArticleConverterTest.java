package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 3.2 {@link ArticleToWxArticleConverter} 单测.
 *
 * <p>覆盖 AC 1-9 + review patches P0-P6 + R3-P1~P4 + R4-P1~P2:
 * <ul>
 *   <li>AC-1 title 映射 + 64 codepoint 截断 (含 emoji UTF-16 代理对)</li>
 *   <li>AC-2 digest 映射 + content 120 codepoint fallback + <b>P2 (非空 digest 也强制 120 cp 截断)</b></li>
 *   <li>AC-3 Markdown → HTML (headings/code/link/image/list/table)</li>
 *   <li>AC-4 footer 拼接 + 防二次解析
 *       + <b>P1 (source 规范化 + HTML escape)</b>
 *       + <b>R3-P3 (source normalize 后为空 → 不输出空来源行)</b></li>
 *   <li>AC-5 图片 URL 原样保留</li>
 *   <li>AC-6 default author</li>
 *   <li>AC-7 NonRetryableException
 *       + <b>P3 (title null/blank 抛异常)</b>
 *       + <b>P4 (Markdown 解析异常 message 含 articleId)</b>
 *       + <b>R3-P2 (blank content 抛异常)</b>
 *       + <b>R4-P1/R4-P2 (parser failure seam + root message 截断)</b></li>
 *   <li>AC-8 log.info 含 articleId + 标题截断 + 长度 + <b>P6 (锁定截断结果)</b></li>
 *   <li>AC-9 thumbMediaId 占位空串</li>
 *   <li><b>P0: raw HTML inline/block 强制转义 (防 XSS)</b></li>
 *   <li><b>R3-P1: URL scheme sanitizer (sanitizeUrls=true 过滤 javascript:/data:)</b></li>
 *   <li><b>R3-P4: fenced code class 移除 (微信不支持 CSS class)</b></li>
 * </ul>
 *
 * <p>模式引用: B2 (footer 模板占位符 replace) / D3 (nullable String field null-check, P3 强化) /
 * N2 (codepoint 截断防 UTF-16 代理对破坏) / W1+W2 (catch RuntimeException 防 commonmark 内部异常逃逸) /
 * W11 (log.info 格式) / N4 (异常 message 仅含 articleId) / R3-1 (truncateForLog ≤ max cp).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ArticleToWxArticleConverterTest {

    private static final String DEFAULT_AUTHOR = "AI 内容聚合器";

    private WeChatProperties properties;

    private ArticleToWxArticleConverter converter;

    @BeforeEach
    void setUp() {
        properties = new WeChatProperties();
        properties.setEnabled(true);
        properties.setDefaultAuthor(DEFAULT_AUTHOR);
        converter = new ArticleToWxArticleConverter(properties);
    }

    // ============ AC-1: title 映射 + 64 codepoint 截断 (N2 防 UTF-16 代理对破坏) ============

    @Test
    void shouldMapTitleVerbatim() {
        Article article = sampleArticle("DeepSeek 推理速度提升", "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getTitle()).isEqualTo("DeepSeek 推理速度提升");
    }

    @Test
    void shouldTruncateTitleByCodepoint_WithEmoji() {
        // 65 codepoint: "标" + 64 emoji (🎉) — emoji 是 UTF-16 代理对 (2 char = 1 codepoint)
        // 验证截断不会切断代理对产生乱码 \uD83D
        StringBuilder title = new StringBuilder("标");
        for (int i = 0; i < 64; i++) {
            title.append("🎉");
        }
        Article article = sampleArticle(title.toString(), "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        // 期望截断后为 "标" + 63 emoji (共 64 codepoint)
        assertThat(result.getTitle().codePointCount(0, result.getTitle().length())).isEqualTo(64);
        assertThat(result.getTitle()).startsWith("标");
        assertThat(result.getTitle()).doesNotContain("\uD83D"); // 不含未配对的高代理字符
    }

    @Test
    void shouldTruncateTitleAtExactly_64Codepoints() {
        // 正好 64 codepoint — 不截断
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            title.append("标");
        }
        Article article = sampleArticle(title.toString(), "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getTitle()).isEqualTo(title.toString());
        assertThat(result.getTitle().codePointCount(0, result.getTitle().length())).isEqualTo(64);
    }

    // ============ AC-2: digest 映射 + content 120 codepoint fallback + P2 截断 ============

    @Test
    void shouldMapDigestVerbatim_WhenPresent() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .digest("预定义摘要内容")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getDigest()).isEqualTo("预定义摘要内容");
    }

    @Test
    void shouldFallbackDigestToContent_WhenDigestIsNull() {
        // content 150 codepoint → digest 取前 120
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            content.append("内");
        }
        Article article = sampleArticleBuilder("标题", content.toString())
                .digest(null)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getDigest().codePointCount(0, result.getDigest().length())).isEqualTo(120);
        assertThat(result.getDigest()).isEqualTo(content.substring(0, 120));
    }

    @Test
    void shouldFallbackDigestToContent_WhenDigestIsBlank() {
        Article article = sampleArticleBuilder("标题", "正文内容足够长".repeat(20))
                .digest("   ")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getDigest()).isNotBlank();
        assertThat(result.getDigest().codePointCount(0, result.getDigest().length())).isLessThanOrEqualTo(120);
    }

    /**
     * P2 review fix: 非空 digest 也必须强制 120 cp 截断.
     * <p>原 bug: 上游 LLM 输出 > 120 cp digest 时原样返回, 违反微信摘要限制导致草稿创建失败.
     */
    @Test
    void shouldTruncateDigest_WhenArticleDigestExceeds120Codepoints() {
        // 200 codepoint 非空 digest
        StringBuilder longDigest = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longDigest.append("摘");
        }
        Article article = sampleArticleBuilder("标题", "# 正文")
                .digest(longDigest.toString())
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getDigest().codePointCount(0, result.getDigest().length())).isEqualTo(120);
        assertThat(result.getDigest()).isEqualTo(longDigest.substring(0, 120));
    }

    // ============ AC-3: Markdown → HTML 转换 ============

    @Test
    void shouldConvertMarkdownHeadingsToHtml() {
        Article article = sampleArticle("标题", "# 一级标题\n\n## 二级标题\n\n### 三级标题");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<h1>一级标题</h1>")
                .contains("<h2>二级标题</h2>")
                .contains("<h3>三级标题</h3>");
    }

    @Test
    void shouldConvertMarkdownCodeBlockToPreCode() {
        Article article = sampleArticle("标题", """
                ```java
                String hello = "world";
                ```
                """);

        WxMpDraftArticles result = converter.convert(article);

        // commonmark 在 code block 内默认转义 " 为 &quot; (防 attribute injection)
        assertThat(result.getContent())
                .contains("<pre><code")
                .contains("String hello = &quot;world&quot;;")
                .contains("</code></pre>");
    }

    @Test
    void shouldConvertMarkdownLinkToAnchor() {
        Article article = sampleArticle("标题", "[DeepSeek 官网](https://www.deepseek.com)");

        WxMpDraftArticles result = converter.convert(article);

        // R3-P1: sanitizeUrls=true 默认会给 <a> 加 rel="nofollow" 属性, 接受该副作用.
        assertThat(result.getContent())
                .contains("href=\"https://www.deepseek.com\">DeepSeek 官网</a>")
                .contains("rel=\"nofollow\"");
    }

    @Test
    void shouldConvertMarkdownImageToImgTag() {
        Article article = sampleArticle("标题", "![alt 描述](https://example.com/img.png)");

        WxMpDraftArticles result = converter.convert(article);

        // AC-5: 图片 URL 原样保留
        assertThat(result.getContent())
                .contains("<img src=\"https://example.com/img.png\" alt=\"alt 描述\" />");
    }

    @Test
    void shouldConvertMarkdownUnorderedListToUl() {
        Article article = sampleArticle("标题", """
                - 第一项
                - 第二项
                - 第三项
                """);

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<ul>")
                .contains("<li>第一项</li>")
                .contains("<li>第二项</li>")
                .contains("<li>第三项</li>")
                .contains("</ul>");
    }

    @Test
    void shouldConvertMarkdownTableToHtmlTable() {
        // GFM 表格扩展 — 表格前需空行才能被识别
        Article article = sampleArticle("标题", """
                下方是表格:

                | 列1 | 列2 |
                |-----|-----|
                | A   | B   |
                | C   | D   |
                """);

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<table>")
                .contains("<thead>")
                .contains("<tbody>")
                .contains("<td>A</td>")
                .contains("<td>D</td>")
                .contains("</table>");
    }

    // ============ AC-4: footer 拼接 + P1 normalize/escape ============

    @Test
    void shouldAppendSourceFooterOnly_WhenSourcePresent() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源:@elonmusk")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<hr/>")
                .contains("<strong>来源:</strong>")  // 模板里的"来源:"
                .contains("@elonmusk")  // P1 normalize 后的 source 内容
                // P1: source 中的 "来源:" 前缀已移除, 不会重复输出
                .doesNotContain("来源:@elonmusk")
                .doesNotContain("本文由 AI 辅助生成")
                .doesNotContain("已通过人工审核");
    }

    @Test
    void shouldNotAppendFooter_WhenSourceMissing() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source(null)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("<hr/>")
                .doesNotContain("本文由 AI 辅助生成")
                .doesNotContain("<strong>来源:</strong>");
    }

    @Test
    void shouldNotAppendFooter_WhenSourceIsBlank() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("   ")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("<hr/>")
                .doesNotContain("本文由 AI 辅助生成")
                .doesNotContain("<strong>来源:</strong>");
    }

    @Test
    void shouldAppendSourceFooter_WhenNotAiGeneratedAndSourcePresent() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源:@manual")
                .aiGenerated(false)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<strong>来源:</strong>")
                .contains("@manual")  // P1 normalize 后的内容
                .doesNotContain("来源:@manual")  // P1: 不重复前缀
                .doesNotContain("本文由 AI 辅助生成");
    }

    @Test
    void shouldNotAppendAnyFooter_WhenNotAiGeneratedAndNoSource() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source(null)
                .aiGenerated(false)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).doesNotContain("<hr/>");
        assertThat(result.getContent()).doesNotContain("本文由 AI 辅助生成");
        assertThat(result.getContent()).doesNotContain("<strong>来源:</strong>");
    }

    @Test
    void shouldNotAppendAiDisclaimerToWechatDraft() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("本文由 AI 辅助生成")
                .doesNotContain("已通过人工审核");
    }

    /**
     * P1 review fix: source 含中文冒号前缀 "来源：" 也要被规范化移除.
     */
    @Test
    void shouldNormalizeSourcePrefix_WhenSourceHasChineseColonPrefix() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源：@chinese_colon")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("@chinese_colon")
                .doesNotContain("来源：@chinese_colon");
    }

    /**
     * P1 review fix: source 含 HTML 特殊字符必须被转义, 防 footer HTML 注入.
     */
    @Test
    void shouldEscapeHtmlInSource_WhenSourceHasAngleBrackets() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("<script>alert(1)</script>")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void shouldEscapeHtmlInSource_WhenSourceHasAmpersand() {
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("Tom & Jerry")
                .build();

        WxMpDraftArticles result = converter.convert(article);

        // P1: & 必须转义为 &amp;
        assertThat(result.getContent())
                .contains("Tom &amp; Jerry")
                .doesNotContain("Tom & J");  // 原 & 后跟 J 的形式已被转义
    }

    // ============ AC-5: 图片 URL 原样保留 ============

    @Test
    void shouldPreserveImageUrlsVerbatimInHtml() {
        String url1 = "https://example.com/path/image%20with%20spaces.png";
        Article article = sampleArticle("标题", "![](" + url1 + ")");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).contains(url1);
    }

    // ============ AC-6: default author ============

    @Test
    void shouldUseDefaultAuthorFromProperties() {
        Article article = sampleArticle("标题", "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getAuthor()).isEqualTo(DEFAULT_AUTHOR);
    }

    @Test
    void shouldUseCustomAuthor_WhenPropertiesCustomized() {
        properties.setDefaultAuthor("自定义作者");
        Article article = sampleArticle("标题", "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getAuthor()).isEqualTo("自定义作者");
    }

    // ============ AC-7: NonRetryableException 异常分支 + P3 + P4 ============

    @Test
    void shouldThrowNonRetryableException_WhenArticleIsNull() {
        assertThatThrownBy(() -> converter.convert(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article 为 null");
    }

    @Test
    void shouldThrowNonRetryableException_WhenContentIsNull() {
        Article article = Article.builder()
                .id("tw-123")
                .title("标题")
                .content(null)
                .build();

        assertThatThrownBy(() -> converter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.content 为空")
                .hasMessageContaining("tw-123");  // P4: 异常 message 含 articleId (N4)
    }

    @Test
    void shouldThrowNonRetryableException_WhenContentIsNull_AndIdIsNull_SafeIdFallback() {
        Article article = Article.builder()
                .id(null)
                .title("标题")
                .content(null)
                .build();

        assertThatThrownBy(() -> converter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("unknown");
    }

    /**
     * P3 review fix: Article.title 为 null 时抛 NonRetryableException (D3 nullable 约束).
     * <p>原 bug: title null 直接传给 wxMpDraftArticles.setTitle(null) → 微信 API 后续拒绝/异常.
     */
    @Test
    void shouldThrowNonRetryableException_WhenTitleIsNull() {
        Article article = Article.builder()
                .id("tw-123")
                .title(null)
                .content("# 正文")
                .build();

        assertThatThrownBy(() -> converter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.title 为空")
                .hasMessageContaining("tw-123");  // P4: 异常 message 含 articleId
    }

    /**
     * P3 review fix: Article.title 为 blank 时抛 NonRetryableException (防空标题过微信 API).
     */
    @Test
    void shouldThrowNonRetryableException_WhenTitleIsBlank() {
        Article article = Article.builder()
                .id("tw-123")
                .title("   ")
                .content("# 正文")
                .build();

        assertThatThrownBy(() -> converter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.title 为空");
    }

    /**
     * R4-P1/R4-P2 review fix: 强制触发 parser RuntimeException, 验证 catch 分支:
     * articleId 透传、原始 cause 保留、root message 截断到 200 cp、且不泄漏 markdown 正文.
     */
    @Test
    void shouldThrowNonRetryableException_WhenMarkdownParserFails_AndTruncateRootMessage() {
        Parser parser = mock(Parser.class);
        HtmlRenderer renderer = mock(HtmlRenderer.class);
        String longRootMessage = "根因".repeat(120);
        RuntimeException root = new RuntimeException(longRootMessage);
        RuntimeException parserFailure = new RuntimeException("wrapper", root);
        when(parser.parse(anyString())).thenThrow(parserFailure);
        ArticleToWxArticleConverter failingConverter = new ArticleToWxArticleConverter(properties, parser, renderer);
        String markdown = "# 正文不应进入异常消息\n\n敏感正文".repeat(20);
        Article article = sampleArticleBuilder("标题", markdown)
                .id("tw-parser-fail")
                .build();

        assertThatThrownBy(() -> failingConverter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasCause(parserFailure)
                .hasMessageContaining("Markdown→HTML 解析失败")
                .hasMessageContaining("articleId=tw-parser-fail")
                .hasMessageContaining("根因".repeat(98) + "根...")
                .hasMessageNotContaining("敏感正文")
                .hasMessageNotContaining("根因".repeat(120));
    }

    // ============ AC-8: log.info (W11 模式) + P6 截断锁定 ============

    @Test
    void shouldLogArticleIdAndTruncatedTitleAndLengths(CapturedOutput output) {
        Article article = sampleArticle("这是一个相当长的标题需要被截断到五十个codepoint以内用于日志展示",
                "# 正文");

        converter.convert(article);

        String log = output.getOut();
        assertThat(log).contains("Article 转换成功");
        assertThat(log).contains("articleId=tw-123");
        assertThat(log).contains("markdown 长度=");
        assertThat(log).contains("html 长度=");
    }

    /**
     * P6 review fix: 锁定日志中标题截断的实际结果 (47 字符 + "..." = 50 codepoint).
     * <p>原测试仅检查日志包含 articleId, 不验证截断是否真的发生 — 无法防止 truncateForLog 回归.
     *
     * <p>R3-1 修复版 truncateForLog(s, 50): total=60 > 50, max=50 > 3,
     * 返回 truncateByCodePoints(s, 47) + "..." (即 47 个 "标" + "...")
     */
    @Test
    void shouldTruncateLongTitleInLog_50Codepoints(CapturedOutput output) {
        // 60 codepoint 标题 — 日志中应截断到 50 codepoint (47 字符 + "...")
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            title.append("标");
        }
        Article article = sampleArticle(title.toString(), "# 正文");

        converter.convert(article);

        String log = output.getOut();
        assertThat(log).contains("Article 转换成功");
        // R3-1 修复版: 47 个 "标" + "..."
        String expectedTruncated = "标".repeat(47) + "...";
        assertThat(log).contains(expectedTruncated);
        // 完整 60 字符标题不应出现在日志中
        assertThat(log).doesNotContain("标".repeat(60));
    }

    // ============ AC-9: thumbMediaId 占位空串 ============

    @Test
    void shouldSetEmptyThumbMediaIdAsPlaceholder() {
        Article article = sampleArticle("标题", "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getThumbMediaId()).isEmpty();
    }

    // ============ XSS / HTML 安全验证 + P0 raw HTML 强制转义 ============

    /**
     * 验证 commonmark 对文本内容中特殊字符的转义能力.
     */
    @Test
    void shouldEscapeSpecialCharactersInTextContent() {
        // 文本中的 & < > 应被转义 (防止被误解为 HTML)
        Article article = sampleArticle("标题", "a < b > c & d");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("a &lt; b &gt; c &amp; d");
    }

    /**
     * P0 review fix: raw HTML inline 强制转义为 HTML 实体, 防 XSS.
     *
     * <p>原 bug: commonmark spec 默认 verbatim 保留 raw HTML inline,
     * 导致 {@code <script>alert('xss')</script>} 直接进入 wx content HTML.
     * 微信后端虽有白名单, 但不应依赖外部系统 — converter 层必须自防.
     *
     * <p>修复: {@code sanitizeRawHtml} 用 visitor 把 HtmlInline 节点替换为 Text 节点,
     * renderer 渲染 Text 时自动把 {@code <}/{@code >} 转义为 {@code &lt;}/{@code &gt;}.
     */
    @Test
    void shouldEscapeRawHtmlInline_PreventXss() {
        Article article = sampleArticle("标题",
                "正常文本 <script>alert('xss')</script> 结束");

        WxMpDraftArticles result = converter.convert(article);

        // P0: raw HTML inline 被强制转义, <script> 标签失效
        assertThat(result.getContent())
                .contains("&lt;script&gt;alert('xss')&lt;/script&gt;")
                .doesNotContain("<script>alert('xss')</script>");
    }

    /**
     * P0 review fix: 块级 raw HTML 也必须强制转义.
     */
    @Test
    void shouldEscapeRawHtmlBlock_PreventXss() {
        // 整段 raw HTML block (CommonMark 视为 HtmlBlock)
        Article article = sampleArticle("标题",
                "<div class=\"evil\">block injection</div>");

        WxMpDraftArticles result = converter.convert(article);

        // P0: < > 必须被转义, <div> 失效
        assertThat(result.getContent()).contains("&lt;div");
        assertThat(result.getContent()).contains("&lt;/div&gt;");
        // 不应出现原始 <div 或 </div>
        assertThat(result.getContent()).doesNotContain("<div");
        assertThat(result.getContent()).doesNotContain("</div>");
    }

    @Test
    void shouldEscapeHtmlInTitle_WhenTitleHasAngleBrackets() {
        // title 不经 Markdown 渲染, 直接 verbatim 设置 — 验证 title 不被改写
        Article article = sampleArticle("标题含 <em> 字符", "# 正文");

        WxMpDraftArticles result = converter.convert(article);

        // title verbatim 设置 (微信端会自行处理转义)
        assertThat(result.getTitle()).isEqualTo("标题含 <em> 字符");
    }

    // ============ R3-P1: URL scheme sanitizer (sanitizeUrls=true) ============

    @Test
    void shouldSanitizeJavascriptSchemeInLinkHref() {
        // R3-P1: commonmark sanitizeUrls=true 应把 javascript: scheme 替换为空串,
        // 防 Markdown [x](javascript:alert(1)) 渲染后 href 仍含危险 scheme.
        Article article = sampleArticle("标题",
                "[点击](javascript:alert('xss'))");

        WxMpDraftArticles result = converter.convert(article);

        // href 应被 sanitizer 清空 (javascript: scheme 危险). sanitizeUrls 默认会加 rel="nofollow".
        assertThat(result.getContent()).contains("href=\"\"");
        assertThat(result.getContent()).doesNotContain("javascript:alert");
    }

    @Test
    void shouldSanitizeDataSchemeInImageSrc() {
        // R3-P1: data: scheme 在 sanitizeUrls=true 模式下也被过滤 (commonmark 默认 sanitize 列表).
        Article article = sampleArticle("标题",
                "![img](data:image/png;base64,iVBORw0KGgo=)");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).contains("<img src=\"\" ");
        assertThat(result.getContent()).doesNotContain("data:image/png");
    }

    @Test
    void shouldPreserveHttpHttpsSchemeInLinkHref() {
        // R3-P1 反向断言: http/https scheme 不应被误过滤.
        Article article = sampleArticle("标题",
                "[官网](https://example.com)");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).contains("href=\"https://example.com\"");
    }

    // ============ R3-P2: blank content 抛 NonRetryableException ============

    @Test
    void shouldThrowNonRetryableException_WhenContentIsBlank() {
        // R3-P2: "   " 不应通过 — 否则生成空正文 + footer-only 草稿, 违反 AC-3 "合法 Markdown".
        Article article = Article.builder()
                .id("tw-blank-content")
                .title("标题")
                .content("   ")
                .build();

        assertThatThrownBy(() -> converter.convert(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.content 为空")
                .hasMessageContaining("tw-blank-content");
    }

    // ============ R3-P3: source 仅前缀 normalize 后为空 → 不输出空来源行 ============

    @Test
    void shouldNotEmitEmptySourceLine_WhenSourceIsOnlyColonPrefix() {
        // R3-P3: source="来源:" normalize 后为空, 不输出空来源行.
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源:").build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("<hr/>")
                .doesNotContain("<strong>来源:</strong> ")
                .doesNotContain("<strong>来源:</strong></p>");
    }

    @Test
    void shouldNotEmitEmptySourceLine_WhenSourceIsOnlyChineseColonPrefix() {
        // R3-P3 中文冒号: source="来源：" normalize 后为空, 应走 NO_SOURCE 分支.
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源：").build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("<hr/>")
                .doesNotContain("<strong>来源:</strong> ")
                .doesNotContain("<strong>来源:</strong></p>");
    }

    @Test
    void shouldNotAppendAnyFooter_WhenSourceIsOnlyPrefix_AndNotAiGenerated() {
        // R3-P3 边界: source 仅前缀 + aiGenerated=false → 不追加任何 footer.
        Article article = sampleArticleBuilder("标题", "# 正文")
                .source("来源:")
                .aiGenerated(false)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).doesNotContain("<hr/>");
        assertThat(result.getContent()).doesNotContain("来源:");
    }

    // ============ R3-P4: fenced code class 移除 (微信不支持 CSS class) ============

    @Test
    void shouldStripLanguageClass_FromFencedCodeBlock() {
        // R3-P4: ``` ```java 应渲染为 <pre><code> 而非 <pre><code class="language-java">.
        Article article = sampleArticle("标题",
                "```java\nSystem.out.println(\"hello\");\n```");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).contains("<pre><code>");
        assertThat(result.getContent()).doesNotContain("language-java");
        assertThat(result.getContent()).doesNotContain("class=\"language-");
    }

    @Test
    void shouldStripLanguageClass_FromPlainFencedCodeBlock() {
        // R3-P4 反向: 无 language 的 fenced code 本身就无 class, AttributeProvider 不破坏其结构.
        Article article = sampleArticle("标题",
                "```\nplain code\n```");

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent()).contains("<pre><code>");
        assertThat(result.getContent()).doesNotContain("class=");
    }

    // ============ Story 8.6 AC-2: PRESERVE_ORIGINAL 模式分支 ============

    @Test
    void should_keep_html_tags_verbatim_when_generation_mode_is_preserve_original() {
        // renderer 产出的安全 HTML (含 mmbiz img) 不得被 commonmark sanitize 实体转义成 &lt;p&gt;
        Article article = sampleArticleBuilder("标题", null)
                .content("<p>第一段</p>\n<p>第二段</p>\n<img src=\"https://mmbiz.qpic.cn/mmbiz/abc123\"/>")
                .generationMode(ContentGenerationMode.PRESERVE_ORIGINAL)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<p>第一段</p>")
                .contains("<img src=\"https://mmbiz.qpic.cn/mmbiz/abc123\"/>")
                .doesNotContain("&lt;p&gt;")
                .doesNotContain("&lt;img");
    }

    @Test
    void should_not_append_footer_when_generation_mode_is_preserve_original() {
        // renderer 已含 footer (hr + 来源行), converter 再追加会双重来源行
        Article article = sampleArticleBuilder("标题", "<p>正文</p>\n<hr/>\n<p><strong>来源:</strong> X 原帖 by @a</p>")
                .generationMode(ContentGenerationMode.PRESERVE_ORIGINAL)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        // 输出逐字节等于输入 HTML — 无 converter FOOTER 追加、无 <p> 包裹
        assertThat(result.getContent()).isEqualTo("<p>正文</p>\n<hr/>\n<p><strong>来源:</strong> X 原帖 by @a</p>");
    }

    @Test
    void should_still_truncate_title_and_digest_when_generation_mode_is_preserve_original() {
        String longTitle = "标".repeat(80);
        String longDigest = "摘".repeat(200);
        Article article = sampleArticleBuilder(longTitle, "<p>正文</p>")
                .digest(longDigest)
                .generationMode(ContentGenerationMode.PRESERVE_ORIGINAL)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getTitle()).isEqualTo("标".repeat(64));
        assertThat(result.getDigest()).isEqualTo("摘".repeat(120));
    }

    @Test
    void should_render_markdown_as_rewrite_when_generation_mode_is_rewrite() {
        // REWRITE 显式声明与默认 (旧 JSON 无字段) 行为一致: markdown 渲染 + sanitize + footer
        Article explicitRewrite = sampleArticleBuilder("标题", "**加粗**")
                .generationMode(ContentGenerationMode.REWRITE)
                .build();

        WxMpDraftArticles result = converter.convert(explicitRewrite);

        assertThat(result.getContent())
                .contains("<strong>加粗</strong>")
                .contains("<p><strong>来源:</strong> @sample</p>");
    }

    // ============ Story 9.1 Task 5: REWRITE_WITH_MEDIA 自然落入 Markdown path (AC 1, 7, 8) ============

    @Test
    void should_render_markdown_images_and_footer_when_generation_mode_is_rewrite_with_media() {
        // AD-3: REWRITE_WITH_MEDIA 正文恒为 Markdown — 走既有 parse/sanitize/footer path,
        // MarkdownMediaInserter 嵌入的 image syntax 由 commonmark 渲染为 <img> (AC 7),
        // converter footer 仍在图片之后追加 (AD-12: footer owner 归 converter)
        Article article = sampleArticleBuilder("标题",
                        "改写正文段落。\n\n![原帖图片-1](https://mmbiz.qpic.cn/w1)")
                .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .contains("<img src=\"https://mmbiz.qpic.cn/w1\" alt=\"原帖图片-1\" />")
                .contains("<hr/>\n<p><strong>来源:</strong> @sample</p>");
    }

    @Test
    void should_sanitize_raw_html_when_generation_mode_is_rewrite_with_media() {
        // 新模式不得进入 PRESERVE_ORIGINAL 的 HTML passthrough 分支 —
        // raw HTML 与 REWRITE 同等强制实体转义 (AC 8 安全水位一致)
        Article article = sampleArticleBuilder("标题", "<script>alert(1)</script>\n\n正文")
                .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getContent())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void should_still_truncate_title_and_digest_when_generation_mode_is_rewrite_with_media() {
        Article article = sampleArticleBuilder("标".repeat(80), "<p>正文</p>")
                .digest("摘".repeat(200))
                .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .build();

        WxMpDraftArticles result = converter.convert(article);

        assertThat(result.getTitle()).isEqualTo("标".repeat(64));
        assertThat(result.getDigest()).isEqualTo("摘".repeat(120));
    }

    // ============ 辅助方法 ============

    private Article sampleArticle(String title, String content) {
        return sampleArticleBuilder(title, content).build();
    }

    private Article.ArticleBuilder sampleArticleBuilder(String title, String content) {
        return Article.builder()
                .id("tw-123")
                .title(title)
                .content(content)
                .source("来源:@sample")
                .aiGenerated(true);
    }
}
