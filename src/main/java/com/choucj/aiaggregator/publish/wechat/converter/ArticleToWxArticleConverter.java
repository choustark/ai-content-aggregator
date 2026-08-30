package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Story 3.2: Article → 微信草稿 DTO 转换器.
 *
 * <p>将 {@link Article} (Story 2.4 输出, Markdown 格式) 转换为 {@link WxMpDraftArticles}
 * (WxJava 4.6.0 草稿实体, HTML 格式), 供 Story 3.3 {@code WeChatPublisher} 调用
 * {@code wxMpService.getDraftService().addDraft(Arrays.asList(wxArticle))}.
 *
 * <p><b>核心职责:</b>
 * <ul>
 *   <li>AC-3: Markdown → HTML (commonmark-java 0.22.0 + GFM 表格扩展);
 *       <b>raw HTML inline/block 在 converter 层强制 sanitize</b>
 *       (P0 review fix: 转 Text 节点让 renderer 自动 HTML 实体转义, 严格满足 AC-3 防 XSS)</li>
 *   <li>AC-4: footer 拼接 (仅来源标注, 必须在 Markdown→HTML 后追加防二次解析;
 *       <b>footer 字段强制 HTML escape</b> (P1 review fix) + source 规范化移除前缀防重复)</li>
 *   <li>AC-1/2: title / digest 字段映射 (含 64/120 codepoint 截断, N2 模式;
 *       <b>digest 120 cp 截断应用到所有路径</b> (P2 review fix);
 *       <b>title null/blank 抛 NonRetryableException</b> (P3 review fix, D3 nullable 约束))</li>
 *   <li>AC-5: 图片 URL 原样保留 (上传由 Story 3.3 处理)</li>
 *   <li>AC-7: 转换失败抛 {@link NonRetryableException} (异常 message 不含 content/html 正文, N4 模式;
 *       <b>Markdown 解析异常 message 含 articleId</b> (P4 review fix))</li>
 *   <li>AC-9: thumbMediaId 占位空串 (Story 3.3 覆盖)</li>
 * </ul>
 *
 * <p><b>lessons-learned 模式引用 (Epic 2 retro B1 行动项):</b>
 * <ul>
 *   <li><b>B2</b> — footer 模板用 {@link String#replace(CharSequence, CharSequence)} 占位符, 防 source 含 {@code %} 抛 {@code IllegalFormatException}</li>
 *   <li><b>D3</b> — Article.title/digest/source nullable String 必须 null-check (P3 强化: title 缺失抛异常而非传 null 给微信 API); Article.aiGenerated primitive boolean 不需 null-check; Article.innovationScore primitive int 无对应字段不映射</li>
 *   <li><b>W11</b> — log.info 含 articleId + 标题截断 + markdown 长度 + html 长度</li>
 *   <li><b>N4</b> — 异常 message 仅含 articleId + 根因 message 截断, 不含 content/html 正文</li>
 *   <li><b>R3-1</b> — 复用 {@link SingleModelRewriter#truncateForLog} (修复版, ≤ max codepoint)</li>
 *   <li><b>跨包可见性</b> — 直接调用 {@code SingleModelRewriter.truncateForLog} + {@code getRootMessage} (Story 2.6 已提升 public static); {@code truncateByCodePoints} 仍 package-private, 在本类重新实现 4 行 (YAGNI, 不为 1 个调用提升可见性)</li>
 * </ul>
 *
 * <p><b>微信草稿展示策略:</b>
 * 草稿 HTML 只追加来源标注, 不追加 AI 辅助生成声明; Markdown 归档器保留独立声明逻辑.
 *
 * <p><b>Story 9.1 模式路由:</b> 仅 {@code PRESERVE_ORIGINAL} 走 HTML passthrough;
 * {@code REWRITE} 与 {@code REWRITE_WITH_MEDIA} 均走 Markdown parse/sanitize/footer path —
 * 新模式无专属分支 (Architecture Spine AD-3: 三模式中两个为 Markdown 正文),
 * 由 {@code MediaAwareRewriteArticleGenerator} 在正文尾部预嵌 Markdown image syntax 即可.
 *
 * <p>引用源: Story 3.2 (本 story) / Story 3.3 (WeChatPublisher 消费转换结果).
 *
 * @see WxMpDraftArticles WxJava 草稿实体
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class ArticleToWxArticleConverter {

    /** 微信公众号标题硬限制 (codepoint 数, 含 UTF-16 代理对按 1 cp 计). */
    private static final int TITLE_MAX_CODEPOINTS = 64;

    /**
     * 微信公众号摘要上限 (P2 review fix: 应用到所有 digest 路径, 不再仅 fallback).
     *
     * <p>原实现仅对 fallback digest 截断, 非空 {@code article.getDigest()} 原样返回 —
     * 一旦上游 (人工补丁或 LLM 输出超长) 传入 > 120 cp digest, 可能违反微信摘要限制
     * 导致草稿创建失败. P2 修复统一对所有 digest 路径强制截断.
     */
    private static final int DIGEST_MAX_CODEPOINTS = 120;

    /** 日志中标题截断长度 (复用 SingleModelRewriter.truncateForLog). */
    private static final int LOG_TITLE_MAX_CODEPOINTS = 50;

    /** Markdown 解析失败 root cause message 日志/异常截断长度 (N4 防泄漏). */
    private static final int ERROR_MSG_MAX_CODEPOINTS = 200;

    /**
     * Footer 模板 (仅来源) — B2 模式: 用 {@code {{SOURCE}}}
     * 占位符, 由 {@link String#replace(CharSequence, CharSequence)} 替换, 不用 {@code String.format}
     * (防 source 含 {@code %} 抛 {@code IllegalFormatException}).
     *
     * <p>P1 review fix: 占位符替换前 source 经 {@link #escapeHtml(String)}
     * 强制 HTML 实体转义, 防外部字段含 {@code <}/{@code &}/{@code "} 注入 footer HTML.
     * source 还需经 {@link #normalizeSource(String)} 移除前缀 {@code 来源:}/{@code 来源：}
     * 防与模板中的 {@code <strong>来源:</strong>} 重复输出.
     */
    private static final String FOOTER_TEMPLATE_SOURCE_ONLY = """
            <hr/>
            <p><strong>来源:</strong> {{SOURCE}}</p>""";

    /**
     * commonmark 扩展列表 — Parser 与 HtmlRenderer 必须共享同一扩展实例,
     * 否则 parser 识别但 renderer 不识别的节点会被静默丢弃 (e.g., 表格).
     *
     * <p>启用 GFM 表格扩展 ({@link TablesExtension#create()}) 支持 Markdown 表格语法.
     */
    private static final List<org.commonmark.Extension> COMMONMARK_EXTENSIONS =
            List.of(TablesExtension.create());

    /** commonmark Parser — 应用启动时一次性构建并复用 (线程安全). */
    private final Parser parser;

    /**
     * commonmark HtmlRenderer — 与 parser 共享同一扩展列表 (关键), 同时启用:
     * <ul>
     *   <li><b>R3-P1:</b> {@code sanitizeUrls(true)} 过滤危险 URL scheme
     *       (javascript:/data:/vbscript:/file: 等), 防 Markdown {@code [x](javascript:alert(1))}
     *       / {@code ![x](data:...)} 进入 {@code href}/{@code src}</li>
     *   <li><b>R3-P4:</b> 自定义 {@link WeChatAttributeProvider} 移除 fenced code 的
     *       {@code class="language-xxx"} 属性, 微信公众号不支持 CSS class</li>
     * </ul>
     */
    private final HtmlRenderer renderer;

    private final WeChatProperties weChatProperties;

    @Autowired
    public ArticleToWxArticleConverter(WeChatProperties weChatProperties) {
        this(weChatProperties, defaultParser(), defaultRenderer());
    }

    ArticleToWxArticleConverter(WeChatProperties weChatProperties, Parser parser, HtmlRenderer renderer) {
        this.weChatProperties = weChatProperties;
        this.parser = parser;
        this.renderer = renderer;
    }

    private static Parser defaultParser() {
        return Parser.builder()
                .extensions(COMMONMARK_EXTENSIONS)
                .build();
    }

    private static HtmlRenderer defaultRenderer() {
        return HtmlRenderer.builder()
                .extensions(COMMONMARK_EXTENSIONS)
                .sanitizeUrls(true)
                .attributeProviderFactory(context -> new WeChatAttributeProvider())
                .build();
    }

    /**
     * 将 {@link Article} 转换为 {@link WxMpDraftArticles}, 满足 AC 1-9 + review patches P0-P5.
     *
     * <p><b>异常策略:</b> 任何无法转换的情况 (article/content null, title null/blank, Markdown 解析失败)
     * 抛 {@link NonRetryableException}, message 仅含 articleId 不含正文 (N4 模式).
     *
     * @param article 待转换文章 (null 抛 NonRetryableException)
     * @return 微信草稿实体 (含 sanitized HTML content + footer)
     * @throws NonRetryableException 转换永久失败
     */
    public WxMpDraftArticles convert(Article article) {
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article 为 null, 无法转换");
        }
        // P3 review fix (D3 nullable 约束): title null/blank 抛 NonRetryableException,
        // 不传 null/空串给 wx.setTitle 避免微信 API 后续拒绝. message 仅含 articleId (N4).
        if (article.getTitle() == null || article.getTitle().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article.title 为空, articleId=" + safeId(article));
        }
        // R3-P2 review fix: content null OR blank 均抛 NonRetryableException,
        // 防 "   " 通过生成空正文 + footer-only 草稿 (违反 AC-3 "合法 Markdown" 验收语义).
        // message 仅含 articleId (N4).
        if (article.getContent() == null || article.getContent().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article.content 为空, articleId=" + safeId(article));
        }

        String articleId = safeId(article);
        String html;
        if (article.getGenerationMode() == ContentGenerationMode.PRESERVE_ORIGINAL) {
            // Story 8.6 D-B: PRESERVE_ORIGINAL 分支 — content 视为 OriginalPostRenderer 产出的
            // 已转义安全 HTML, 跳过 commonmark parse + sanitizeRawHtml + footer 追加.
            // 若走 REWRITE 管线, sanitizeRawHtml 会把 HtmlInline/HtmlBlock 替换为 Text 节点,
            // renderer 实体转义后所有标签变成 &lt;p&gt; 字面文本 (P0 转义破坏), 破损草稿进入真实账号.
            // 安全论证: renderer 全量 escapeHtml (5 字符) + isSafeHttpUrl (仅 http/https) 白名单,
            // 安全水位与 sanitizeRawHtml 等价 (Story 8.5 AC1/AC2 + 45 用例验证);
            // footer 由 renderer 已产出 (hr + 来源 + 原文链接), 再追加会双重来源行.
            // title 64cp / digest 120cp 截断保留 (幂等无害).
            html = article.getContent();
            log.info("Article 转换成功(原帖复现直通): articleId={}, mode={}, html 长度={}",
                    articleId, article.getGenerationMode(), html.length());
        } else {
            // P4 review fix: 传 articleId 给 renderMarkdown, 让解析异常 message 含 articleId (AC-7).
            // REWRITE 分支行为与 Story 8.6 之前逐字节不变 (AC-8 零回退).
            // Story 9.1: REWRITE_WITH_MEDIA 自然落入本分支 (AD-3: 新模式正文恒为 Markdown) —
            // MarkdownMediaInserter 嵌入的 image syntax 由 commonmark 渲染为 <img>,
            // footer 仍在图片之后追加 (AD-12). 不新增 HTML passthrough 分支 (architecture guardrail).
            html = renderMarkdown(article.getContent(), articleId);
            html = appendFooter(html, article);
        }

        WxMpDraftArticles wx = new WxMpDraftArticles();
        // P3: title 已校验非 null/blank, 直接 truncate
        wx.setTitle(truncateForCodePoints(article.getTitle(), TITLE_MAX_CODEPOINTS));
        // P2: digest 强制 120 cp 截断 (统一所有路径)
        wx.setDigest(resolveDigest(article));
        wx.setAuthor(weChatProperties.getDefaultAuthor());
        wx.setContent(html);
        // AC-9: Story 3.2 不上传封面图, 占位空串. Story 3.3 WeChatPublisher 必须在 addDraft 调用前覆盖此字段.
        wx.setThumbMediaId("");

        log.info("Article 转换成功: articleId={}, 标题=\"{}\", markdown 长度={}, html 长度={}",
                articleId,
                SingleModelRewriter.truncateForLog(article.getTitle(), LOG_TITLE_MAX_CODEPOINTS),
                article.getContent().length(),
                html.length());

        return wx;
    }

    /**
     * Markdown → HTML 渲染 (AC-3) + raw HTML 强制 sanitize (P0 review fix).
     *
     * <p>commonmark-java 默认行为 (CommonMark spec 合规):
     * <ul>
     *   <li>文本内容中的 {@code &} {@code <} {@code >} 自动转义为 HTML 实体</li>
     *   <li>code block 内 {@code "} 转义为 {@code &quot;}</li>
     *   <li>GFM 表格扩展支持 (表格前需空行)</li>
     *   <li><b>raw HTML inline/block verbatim 保留</b> — commonmark spec 设计;
     *       但 AC-3 明文要求 "防 XSS", 故 P0 fix 在渲染前用 visitor 把 raw HTML 节点
     *       替换为 Text 节点, 让 renderer 自动转义为 HTML 实体</li>
     * </ul>
     *
     * <p><b>P0 sanitize 流程:</b>
     * {@link #sanitizeRawHtml(Node)} 在 parser 生成 AST 之后 / renderer 输出之前介入,
     * 遍历 AST 收集所有 {@link HtmlInline} / {@link HtmlBlock} 节点, 用 {@link Text}
     * 节点替换 (literal 内容相同). 之后 renderer 把 Text 节点中的 {@code <}/{@code >}/{@code &}
     * 自动转义为 {@code &lt;}/{@code &gt;}/{@code &amp;}, 实现 raw HTML 强制转义.
     *
     * <p><b>W1+W2 模式:</b> {@code catch (RuntimeException)} 防止 commonmark 内部异常逃逸到顶层.
     *
     * @param markdown 待渲染 Markdown 字符串
     * @param articleId 仅用于异常 message (N4 模式), 不写入正常输出
     */
    private String renderMarkdown(String markdown, String articleId) {
        try {
            Node document = parser.parse(markdown);
            // P0: 在渲染前用 visitor 把 raw HTML 节点替换为 Text, renderer 会自动 HTML 实体转义
            sanitizeRawHtml(document);
            return renderer.render(document);
        } catch (RuntimeException e) {
            // P4 review fix: 异常 message 含 articleId (AC-7 要求).
            // N4: message 仅含 articleId + cause message 截断, 不含 markdown 正文.
            String rootMessage = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), ERROR_MSG_MAX_CODEPOINTS);
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Markdown→HTML 解析失败, articleId=" + articleId
                            + ": " + rootMessage, e);
        }
    }

    /**
     * P0 review fix: 在 commonmark AST 中把 raw HTML 节点替换为 Text 节点.
     *
     * <p>覆盖两类节点:
     * <ul>
     *   <li>{@link HtmlInline} — 行内 raw HTML (如 {@code <script>alert('xss')</script>})</li>
     *   <li>{@link HtmlBlock} — 块级 raw HTML (整段 HTML, 较少见于 LLM 改写输出)</li>
     * </ul>
     *
     * <p>替换策略: 用 {@link Text} 节点 insertBefore 原节点 + 原节点 unlink.
     * Text 节点的 literal 与原 HtmlInline/HtmlBlock 的 literal 字面相同,
     * 但 renderer 会按 Text 渲染规则把 {@code <}/{@code >}/{@code &} 自动转义为 HTML 实体.
     *
     * <p><b>迭代安全:</b> 先用 visitor 收集所有目标节点到 List, 退出 visit 后再批量替换,
     * 避免 visit 过程中修改 AST 链表导致迭代异常.
     */
    private void sanitizeRawHtml(Node document) {
        List<Node> inlineReplacements = new ArrayList<>();
        List<Node> blockReplacements = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(HtmlInline htmlInline) {
                inlineReplacements.add(htmlInline);
            }

            @Override
            public void visit(HtmlBlock htmlBlock) {
                blockReplacements.add(htmlBlock);
            }
        });

        for (Node node : inlineReplacements) {
            HtmlInline htmlInline = (HtmlInline) node;
            htmlInline.insertBefore(new Text(htmlInline.getLiteral()));
            htmlInline.unlink();
        }
        for (Node node : blockReplacements) {
            HtmlBlock htmlBlock = (HtmlBlock) node;
            htmlBlock.insertBefore(new Text(htmlBlock.getLiteral()));
            htmlBlock.unlink();
        }
    }

    /**
     * 在 HTML 末尾追加来源 footer (AC-4) + P1 review fix (HTML escape + source 规范化).
     *
     * <p><b>关键:</b> footer 必须在 {@link #renderMarkdown(String, String)} 之后追加,
     * 否则 footer 文本会被 commonmark 当 Markdown 二次解析.
     *
     * <p><b>P1 review fix:</b>
     * <ul>
     *   <li>{@link #normalizeSource(String)} — source trim + 移除前缀 {@code 来源:}/{@code 来源：} 防重复 + HTML escape</li>
     * </ul>
     *
     * <p>组合逻辑:
     * source 有值时追加来源行; source 缺失时不追加 footer. AI 声明不进入微信草稿 HTML.
     */
    private String appendFooter(String html, Article article) {
        // R3-P3 review fix: 先 normalize source 再判 hasSource,
        // 防 source="来源:" / "来源：" 这类 normalize 后为空的输入仍输出空来源行
        // (<p><strong>来源:</strong> </p>).
        String normalizedSource = (article.getSource() != null)
                ? normalizeSource(article.getSource())
                : "";
        boolean hasSource = !normalizedSource.isEmpty();

        if (!hasSource) {
            return html;
        }

        return html + FOOTER_TEMPLATE_SOURCE_ONLY.replace("{{SOURCE}}", normalizedSource);
    }

    /**
     * P1 review fix: source 规范化 + HTML escape.
     *
     * <p>步骤:
     * <ol>
     *   <li>trim — 去首尾空白</li>
     *   <li>移除前缀 {@code 来源:} / {@code 来源：} (中英文冒号均支持) —
     *       防与模板里的 {@code <strong>来源:</strong>} 重复输出</li>
     *   <li>{@link #escapeHtml(String)} — HTML 实体转义防 footer HTML 注入</li>
     * </ol>
     */
    private static String normalizeSource(String source) {
        String trimmed = source.trim();
        if (trimmed.startsWith("来源:") || trimmed.startsWith("来源：")) {
            trimmed = trimmed.substring(3).trim();
        }
        return escapeHtml(trimmed);
    }

    /**
     * P1 review fix: HTML 实体转义 (用于 footer 拼接的外部字段 source).
     *
     * <p>转义 5 个 HTML 特殊字符: {@code &} {@code <} {@code >} {@code "} {@code '}.
     * 与 commonmark HtmlRenderer 对 Text 节点的转义规则对齐.
     *
     * <p>Story 8.5: 从 {@code private} 提升为 package-private，供同包
     * {@link OriginalPostRenderer} 复用 (同包可见性提升模式，行为零变化)，
     * 避免在渲染器内复制一份 5 字符转义实现产生 stale 风险。
     */
    static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 解析 digest (AC-2) + P2 review fix (统一 120 cp 截断).
     *
     * <p>P2 修复: 原 fallback 路径才截断, 非空 digest 原样返回. 修复后对所有路径强制
     * {@code truncateForCodePoints(..., DIGEST_MAX_CODEPOINTS)}, 防上游/人工补丁传入超长 digest
     * 违反微信摘要限制导致草稿创建失败.
     */
    private String resolveDigest(Article article) {
        String raw;
        if (article.getDigest() != null && !article.getDigest().isBlank()) {
            raw = article.getDigest();
        } else {
            // article.getContent() 已在 convert() 顶部 null-check
            raw = article.getContent();
        }
        return truncateForCodePoints(raw, DIGEST_MAX_CODEPOINTS);
    }

    /**
     * 按 codepoint 截断 (AC-1 标题 + AC-2 digest).
     *
     * <p><b>N2 模式:</b> 防 UTF-16 代理对 (emoji) 在 char 边界切断产生乱码 {@code \uD83D}.
     *
     * <p><b>跨包可见性决策:</b> {@link SingleModelRewriter#truncateByCodePoints} 是 package-private,
     * 不提升可见性 (YAGNI — 仅 1 处调用, 4 行小函数无 stale 风险), 在本类重新实现.
     */
    private static String truncateForCodePoints(String s, int max) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int total = s.codePointCount(0, s.length());
        if (total <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }

    /** Article.id 安全提取 (null 时回退 "unknown", 仅用于日志 / 异常 message). */
    private static String safeId(Article article) {
        return article.getId() != null ? article.getId() : "unknown";
    }

    /**
     * R3-P4 review fix: 自定义 commonmark {@link AttributeProvider}, 移除 fenced code 的
     * {@code class="language-xxx"} 属性.
     *
     * <p>commonmark 默认会把 Markdown {@code ```java ... ```} 渲染为
     * {@code <pre><code class="language-java">...</code></pre>}, 但微信公众号不支持自定义 CSS class
     * (见 §5.7 微信 HTML 白名单提示), 故移除 {@code <code>} 与 {@code <pre>} 的 {@code class} 属性.
     *
     * <p>仅针对 {@code code}/{@code pre} 节点, 其他标签 ({@code a}/{@code img}/...) 的属性保留
     * (sanitizeUrls 已在 renderer 层处理危险 scheme).
     */
    private static final class WeChatAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if ("code".equals(tagName) || "pre".equals(tagName)) {
                attributes.remove("class");
            }
        }
    }
}
