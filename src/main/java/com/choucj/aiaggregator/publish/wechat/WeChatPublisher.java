package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import com.choucj.aiaggregator.publish.wechat.converter.ArticleToWxArticleConverter;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Story 3.3: 微信公众号草稿发布器.
 *
 * <p>微信草稿创建 delegate, 将 {@link Article} 经 Story 3.2 converter 转换为
 * {@link WxMpDraftArticles} 后, 包装为 {@link WxMpAddDraft} 调用
 * {@code wxMpService.getDraftService().addDraft(WxMpAddDraft)} 创建草稿, 返回 {@code media_id}.
 * Story 3.4 后本类不再实现通用 ContentPublisher, 避免 TwitterProcessor 同时调用
 * raw WeChatPublisher 与 PublishingModeDecider 造成重复草稿; 微信发布统一经 PublishingModeDecider 分流.
 *
 * <p><b>异常映射:</b> 复用 Story 3.1 {@link WxJavaWeChatClient#mapWxErrorException} (W1+W2 模式),
 * 同时 catch {@link RuntimeException} 兜底防 SDK 内部异常逃逸到 Pipeline 顶层.
 *
 * <p><b>lessons-learned 模式引用:</b>
 * <ul>
 *   <li>W1+W2 — try-catch (WxErrorException + RuntimeException 兜底)</li>
 *   <li>W11 — log.info/log.error 含 articleId + mediaId/errcode + 标题/errmsg 截断</li>
 *   <li>N4 — 异常 message 不含 content/html 正文, 不含 token/secret, 仅含 articleId + errcode/errmsg 截断 + cause</li>
 *   <li>R3-1 — {@link SingleModelRewriter#truncateForLog} (修复版 ≤ max codepoint)</li>
 *   <li>跨包可见性 — 静态调用 {@code SingleModelRewriter.truncateForLog/getRootMessage} +
 *       {@code WxJavaWeChatClient.mapWxErrorException}</li>
 *   <li>D3 — article null 入口校验 (AC-8); mediaId null/blank 校验 (AC-9)</li>
 *   <li>B2 — 异常 message 走拼接式 (mapWxErrorException base 字符串), 不用 String.format</li>
 *   <li>M1 — WxMpDraftService 真实 API 已 verify: {@code addDraft(WxMpAddDraft)} +
 *       {@code WxMpAddDraft(List<WxMpDraftArticles>)} 构造器</li>
 * </ul>
 *
 * <p>引用源: Story 3.3 (本 story) / Story 3.4 (混合发布模式注入) /
 * Story 3.1 (mapWxErrorException 复用) / Story 3.2 (converter 复用).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatPublisher {

    /** 日志中标题截断长度 (codepoint, R3-1 ≤ max). */
    private static final int LOG_TITLE_MAX_CODEPOINTS = 50;

    /** 日志/异常中 errmsg / rootMessage 截断长度 (codepoint, R3-1 ≤ max, N4 防泄漏). */
    private static final int LOG_ERROR_MSG_MAX_CODEPOINTS = 200;

    /**
     * Story 3.5 B8 + Story 4.4: Article.id 确定性前缀格式 — {@code tw-{tweetId}} (Twitter) /
     * {@code gh-{owner}-{repo}} (GitHub). 字符集 {@code [A-Za-z0-9_-]+} 两者兼容
     * (GitHubProcessor.buildDeterministicArticleId 校验 owner/repo 字符集同为
     * {@code [A-Za-z0-9_-]+}, 与本正则一致 — GitHub 实际允许 {@code .}, 但项目收窄为更严格的
     * URL-safe 字符集, 简化 Redis key / Article.id 治理).
     */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("(tw|gh)-[A-Za-z0-9_-]+");

    private final WxMpService wxMpService;
    private final ArticleToWxArticleConverter converter;
    private final WeChatThumbMediaIdResolver thumbMediaIdResolver;
    private final ArticleStatusService articleStatusService;
    private final boolean reviewReminderEnabled;

    /**
     * Story 3.5 — 手写构造器 (W2 模式): {@code reviewReminderEnabled} 是
     * {@code @Value} 注入的 boolean, 不能用 {@code @RequiredArgsConstructor} (Lombok 只处理 final 字段).
     *
     * @param wxMpService           WxJava 微信服务
     * @param converter             Article → WxMpDraftArticles 转换器 (Story 3.2)
     * @param thumbMediaIdResolver  草稿封面 media_id 解析器
     * @param articleStatusService  状态机服务 (Story 3.5, 写 DRAFT_CREATED)
     * @param reviewReminderEnabled 人工审核提醒开关 (Story 3.5 AC-12, 默认 true)
     */
    public WeChatPublisher(WxMpService wxMpService,
                           ArticleToWxArticleConverter converter,
                           WeChatThumbMediaIdResolver thumbMediaIdResolver,
                           ArticleStatusService articleStatusService,
                           @Value("${wechat.mp.review-reminder-enabled:true}") boolean reviewReminderEnabled) {
        this.wxMpService = wxMpService;
        this.converter = converter;
        this.thumbMediaIdResolver = thumbMediaIdResolver;
        this.articleStatusService = articleStatusService;
        this.reviewReminderEnabled = reviewReminderEnabled;
    }

    public void publish(Article article) {
        // AC-8: 入口 null check, 防 NPE 在 converter 内部触发.
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article 为 null");
        }
        String articleId = requireArticleId(article);

        WxMpDraftArticles wxArticle = converter.convert(article);

        // AC-10 (Story 3.3 review D1→Patch): 必须在 addDraft 调用前覆盖 Story 3.2 converter 占位的空串.
        // 微信 draft/add 接口要求 thumb_media_id 必须为有效的永久素材 media_id; 默认按素材名称动态查询并缓存.
        String thumbMediaId = thumbMediaIdResolver.resolve();
        if (thumbMediaId == null || thumbMediaId.isBlank()) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信草稿封面 media_id 解析为空, articleId=" + articleId);
        }
        wxArticle.setThumbMediaId(thumbMediaId);

        String mediaId;
        try {
            // Story 3.5 AC-2: 只有真正进入微信草稿创建路径时才写 PROCESSING; 批量入队路径保持 PENDING.
            articleStatusService.markProcessing(articleId);
            // M1 verify: WxMpDraftService.addDraft(WxMpAddDraft) + WxMpAddDraft(List<WxMpDraftArticles>)
            mediaId = wxMpService.getDraftService().addDraft(new WxMpAddDraft(java.util.List.of(wxArticle)));
        } catch (WxErrorException e) {
            // AC-3 / AC-6: 经 mapWxErrorException 映射 errcode → Retryable/NonRetryable
            int errcode = (e.getError() == null) ? -1 : e.getError().getErrorCode();
            String errmsg = (e.getError() == null) ? "unknown" : e.getError().getErrorMsg();
            // P2: log.error 含截断 rootMessage 字段 (AC-6 cause message 要求)
            String truncatedErrmsg = SingleModelRewriter.truncateForLog(errmsg, LOG_ERROR_MSG_MAX_CODEPOINTS);
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_ERROR_MSG_MAX_CODEPOINTS);
            log.error("微信 addDraft 失败: articleId={}, operation=addDraft, errcode={}, errmsg={}, rootMessage={}",
                    articleId, errcode, truncatedErrmsg, truncatedCause, e);
            throw WxJavaWeChatClient.mapWxErrorException(e, "addDraft");
        } catch (RuntimeException e) {
            // AC-7: W1+W2 兜底, 防 SDK 内部异常逃逸到 Pipeline 顶层.
            // P1: message 复用截断后的 rootMessage, 防止超长 SDK 异常泄漏到上层日志 (N4 防泄漏)
            String truncatedRoot = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_ERROR_MSG_MAX_CODEPOINTS);
            log.error("WxJava 框架异常: articleId={}, operation=addDraft, rootMessage={}",
                    articleId, truncatedRoot, e);
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "WxJava 框架异常: " + truncatedRoot, e);
        }

        // AC-9: 校验返回 media_id 非空, 不让 null/"" 流到 Pipeline 上层.
        if (mediaId == null || mediaId.isBlank()) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "WxJava addDraft 返回空 media_id, articleId=" + articleId);
        }

        // AC-5: 成功日志 (W11)
        log.info("微信草稿创建成功: articleId={}, mediaId={}, 标题=\"{}\", html 长度={}",
                articleId,
                mediaId,
                SingleModelRewriter.truncateForLog(article.getTitle(), LOG_TITLE_MAX_CODEPOINTS),
                wxArticle.getContent() == null ? 0 : wxArticle.getContent().length());

        // Story 3.5 AC-3: 状态机写 DRAFT_CREATED (状态追踪不可跳, ArticleStatusService 内部软失败)
        articleStatusService.markDraftCreated(articleId);

        // Story 3.5 AC-12: 条件输出人工审核提醒日志 (reviewReminderEnabled=false 时跳过, 但状态写入不跳过)
        if (reviewReminderEnabled) {
            log.info("请到微信公众号后台预览并手动发布: articleId={}, mediaId={}, 标题=\"{}\"",
                    articleId,
                    mediaId,
                    SingleModelRewriter.truncateForLog(article.getTitle(), LOG_TITLE_MAX_CODEPOINTS));
        }
    }

    /** Article.id fail-fast 校验, 防止远程草稿已创建后才写入 unknown / 非法状态 key. */
    private static String requireArticleId(Article article) {
        String articleId = article.getId();
        if (articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article.id 不能为空");
        }
        if (!ARTICLE_ID_PATTERN.matcher(articleId).matches()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article.id 非法 (必须匹配 (tw|gh)-[A-Za-z0-9_-]+): " + articleId);
        }
        return articleId;
    }
}
