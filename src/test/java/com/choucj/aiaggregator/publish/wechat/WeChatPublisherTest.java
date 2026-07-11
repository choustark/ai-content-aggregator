package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import com.choucj.aiaggregator.publish.wechat.converter.ArticleToWxArticleConverter;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpDraftService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 3.3 — WeChatPublisher 单元测试.
 *
 * <p>覆盖 AC-1 ~ AC-10: Bean 注册 / 成功路径 / 异常映射 / RuntimeException 兜底 /
 * null Article / 空 media_id / thumbMediaId 占位决策.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WeChatPublisherTest {

    @Mock
    private WxMpService wxMpService;

    @Mock
    private WxMpDraftService wxMpDraftService;

    @Mock
    private ArticleToWxArticleConverter converter;

    @Mock
    private ArticleStatusService articleStatusService;

    private WeChatProperties weChatProperties;
    private WeChatPublisher publisher;

    @BeforeEach
    void setUp() {
        weChatProperties = new WeChatProperties();
        weChatProperties.setThumbMediaId("test-thumb-media-id");
        publisher = new WeChatPublisher(wxMpService, converter, weChatProperties,
                articleStatusService, true);
    }

    // ===== Task 1: AC-1 Bean 注册 =====

    @Test
    void shouldCreateWeChatDraftDelegate() {
        assertThat(publisher).isNotNull();
    }

    // ===== Task 2: AC-2 成功路径 + AC-5 成功日志 =====

    @Test
    void shouldPublishArticleAndReturnMediaId(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        WxMpDraftArticles wxArticle = sampleWxArticle("html-body-xyz");
        when(converter.convert(article)).thenReturn(wxArticle);
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-123");

        publisher.publish(article);

        ArgumentCaptor<WxMpAddDraft> captor = ArgumentCaptor.forClass(WxMpAddDraft.class);
        verify(wxMpDraftService).addDraft(captor.capture());
        verify(articleStatusService).markProcessing("tw-art-001");
        assertThat(captor.getValue().getArticles()).containsExactly(wxArticle);
        assertThat(output)
                .contains("微信草稿创建成功")
                .contains("articleId=tw-art-001")
                .contains("mediaId=media-id-123");
    }

    @Test
    void shouldLogInfoWithArticleIdMediaIdTitleAndHtmlLengthOnSuccess(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        String html = "<p>html</p>";
        WxMpDraftArticles wxArticle = sampleWxArticle(html);
        when(converter.convert(article)).thenReturn(wxArticle);
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-xyz");

        publisher.publish(article);

        assertThat(output)
                .contains("articleId=tw-art-001")
                .contains("mediaId=media-id-xyz")
                .contains("标题=\"标题-tw-art-001\"")
                .contains("html 长度=" + html.length());
    }

    // ===== Task 3: AC-3 / AC-6 WxErrorException 异常路径 =====

    @Test
    void shouldThrowRetryableWhenTokenExpired(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(40014, "invalid access_token"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(RetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_TOKEN_EXPIRED))
                .hasMessageContaining("微信 addDraft 失败")
                .hasMessageContaining("errcode=40014");
        assertThat(output)
                .contains("articleId=tw-art-001")
                .contains("operation=addDraft")
                .contains("errcode=40014");
    }

    @Test
    void shouldThrowRetryableWhenSystemBusy() throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(-1, "system busy"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(RetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("errcode=-1");
    }

    @Test
    void shouldThrowNonRetryableWhenInvalidCredential() throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(40001, "invalid credential"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_INVALID_CREDENTIAL))
                .hasMessageContaining("errcode=40001");
    }

    @Test
    void shouldThrowNonRetryableOnOtherErrcode() throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(45009, "quota limit"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("errcode=45009");
    }

    @Test
    void shouldLogErrorWithArticleIdOperationErrcodeErrmsgOnFailure(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(40014, "invalid access_token"));

        assertThatThrownBy(() -> publisher.publish(article));

        assertThat(output)
                .contains("微信 addDraft 失败")
                .contains("articleId=tw-art-001")
                .contains("operation=addDraft")
                .contains("errcode=40014")
                .contains("errmsg=invalid access_token")
                .contains("rootMessage=");
    }

    @Test
    void shouldTruncateLongRootMessageInWxErrorExceptionLogAndMessage(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        // 造一个超长 errmsg (> 200 cp) 触发 R3-1 截断 (P2 + N4 防泄漏)
        String longErrmsg = "X".repeat(500);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(wxError(40001, longErrmsg));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg.length()).isLessThan(longErrmsg.length());
                    assertThat(msg).doesNotContain("X".repeat(300));
                });
    }

    // ===== Task 4: AC-7 RuntimeException 兜底 =====

    @Test
    void shouldWrapRuntimeExceptionAsNonRetryable(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava 框架异常")
                .hasMessageContaining("connection reset");
        assertThat(output)
                .contains("WxJava 框架异常")
                .contains("articleId=tw-art-001")
                .contains("operation=addDraft");
    }

    @Test
    void shouldTruncateLongRootMessageInRuntimeExceptionWrap(CapturedOutput output) throws WxErrorException {
        // P1: SDK RuntimeException root cause 含超长文本时, 异常 message 必须复用截断后的 rootMessage (N4 防泄漏)
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        String longRoot = "Z".repeat(500);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class)))
                .thenThrow(new IllegalStateException(longRoot));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    // 异常 message 不应包含完整的 500 字符 root, R3-1 截断到 ≤ 200 cp + "WxJava 框架异常: " 前缀
                    assertThat(msg.length()).isLessThan(longRoot.length());
                    assertThat(msg).doesNotContain("Z".repeat(300));
                    assertThat(msg).contains("WxJava 框架异常");
                });
        assertThat(output).contains("operation=addDraft").contains("rootMessage=");
    }

    // ===== Task 5: AC-8 / AC-9 边缘场景 =====

    @Test
    void shouldThrowNonRetryableWhenArticleIsNull() {
        assertThatThrownBy(() -> publisher.publish(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NON_RETRYABLE_ERROR))
                .hasMessageContaining("Article 为 null");
        verify(converter, never()).convert(any());
    }

    @Test
    void shouldThrowNonRetryableWhenArticleIdIsNullBeforeRemoteCall() throws WxErrorException {
        Article article = Article.builder()
                .title("标题")
                .content("## hello")
                .build();

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 不能为空");

        verify(converter, never()).convert(any());
        verify(wxMpService, never()).getDraftService();
        verify(articleStatusService, never()).markProcessing(any());
        verify(articleStatusService, never()).markDraftCreated(any());
    }

    @Test
    void shouldThrowNonRetryableWhenArticleIdIsMalformedBeforeRemoteCall() throws WxErrorException {
        Article article = Article.builder()
                .id("article:001")
                .title("标题")
                .content("## hello")
                .build();

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须匹配");

        verify(converter, never()).convert(any());
        verify(wxMpService, never()).getDraftService();
        verify(articleStatusService, never()).markProcessing(any());
        verify(articleStatusService, never()).markDraftCreated(any());
    }

    @Test
    void shouldAcceptGithubArticleIdWithGhPrefix(CapturedOutput output) throws WxErrorException {
        // Story 4.4 Task 3.3 — ARTICLE_ID_PATTERN 扩展为 (tw|gh)-[A-Za-z0-9_-]+,
        // gh-{owner}-{repo} 应通过校验并到达 draft service (spike-4.1 §3.3 风险 #3 缓解)
        Article article = Article.builder()
                .id("gh-octocat-Hello-World")
                .title("标题-gh")
                .content("## hello-gh")
                .aiGenerated(true)
                .innovationScore(8)
                .source("GitHub Repo:octocat/Hello-World")
                .build();
        WxMpDraftArticles wxArticle = sampleWxArticle("html-body-gh");
        when(converter.convert(article)).thenReturn(wxArticle);
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-gh");

        publisher.publish(article);

        verify(articleStatusService).markProcessing("gh-octocat-Hello-World");
        verify(articleStatusService).markDraftCreated("gh-octocat-Hello-World");
        assertThat(output).contains("articleId=gh-octocat-Hello-World")
                .contains("mediaId=media-id-gh");
    }

    @Test
    void shouldThrowNonRetryableWhenMediaIdIsEmpty(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("");

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava addDraft 返回空 media_id")
                .hasMessageContaining("articleId=tw-art-001");
    }

    @Test
    void shouldThrowNonRetryableWhenMediaIdIsNull(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn(null);

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava addDraft 返回空 media_id");
    }

    // ===== Task 6: AC-10 thumbMediaId 决策 (Story 3.3 review D1→Patch: 配置覆盖 + fail-fast) =====

    @Test
    void shouldOverrideThumbMediaIdFromConfig(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        WxMpDraftArticles wxArticle = sampleWxArticle("html");
        // converter 占位 thumbMediaId="" (Story 3.2 行为); WeChatPublisher 必须用配置覆盖
        when(converter.convert(article)).thenReturn(wxArticle);
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-ok");

        publisher.publish(article);

        ArgumentCaptor<WxMpAddDraft> captor = ArgumentCaptor.forClass(WxMpAddDraft.class);
        verify(wxMpDraftService).addDraft(captor.capture());
        WxMpDraftArticles passed = captor.getValue().getArticles().get(0);
        assertThat(passed.getThumbMediaId()).isEqualTo("test-thumb-media-id");
    }

    @Test
    void shouldTrimThumbMediaIdFromConfigBeforeAddDraft() throws WxErrorException {
        weChatProperties.setThumbMediaId("  test-thumb-media-id  ");
        Article article = sampleArticle();
        WxMpDraftArticles wxArticle = sampleWxArticle("html");
        when(converter.convert(article)).thenReturn(wxArticle);
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-ok");

        publisher.publish(article);

        ArgumentCaptor<WxMpAddDraft> captor = ArgumentCaptor.forClass(WxMpAddDraft.class);
        verify(wxMpDraftService).addDraft(captor.capture());
        WxMpDraftArticles passed = captor.getValue().getArticles().get(0);
        assertThat(passed.getThumbMediaId()).isEqualTo("test-thumb-media-id");
    }

    @Test
    void shouldThrowNonRetryableWhenThumbMediaIdConfigIsBlank() {
        weChatProperties.setThumbMediaId("   ");
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("wechat.mp.thumb-media-id 未配置")
                .hasMessageContaining("articleId=tw-art-001");
        verify(wxMpService, never()).getDraftService();
    }

    @Test
    void shouldThrowNonRetryableWhenThumbMediaIdConfigIsNull() {
        weChatProperties.setThumbMediaId(null);
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));

        assertThatThrownBy(() -> publisher.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("wechat.mp.thumb-media-id 未配置");
        verify(wxMpService, never()).getDraftService();
    }

    // ===== Task 4: Story 3.5 集成 — DRAFT_CREATED 写入 + 提醒日志 (AC-3, AC-12) =====

    @Test
    void shouldMarkDraftCreatedAndLogReminderAfterSuccess(CapturedOutput output) throws WxErrorException {
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-xyz");

        publisher.publish(article);

        // PROCESSING + DRAFT_CREATED 被调用 (状态追踪是核心功能)
        verify(articleStatusService).markProcessing("tw-art-001");
        verify(articleStatusService).markDraftCreated("tw-art-001");
        // 提醒日志输出 (reviewReminderEnabled=true 默认)
        assertThat(output).contains("请到微信公众号后台预览并手动发布");
        assertThat(output).contains("articleId=tw-art-001");
        assertThat(output).contains("mediaId=media-id-xyz");
    }

    @Test
    void shouldSkipReminderLogWhenReviewReminderDisabled(CapturedOutput output) throws WxErrorException {
        // reviewReminderEnabled=false 时提醒日志跳过, 但 markDraftCreated 仍调用
        publisher = new WeChatPublisher(wxMpService, converter, weChatProperties,
                articleStatusService, false);
        Article article = sampleArticle();
        when(converter.convert(article)).thenReturn(sampleWxArticle("html"));
        when(wxMpService.getDraftService()).thenReturn(wxMpDraftService);
        when(wxMpDraftService.addDraft(any(WxMpAddDraft.class))).thenReturn("media-id-xyz");

        publisher.publish(article);

        verify(articleStatusService).markProcessing("tw-art-001");
        verify(articleStatusService).markDraftCreated("tw-art-001");
        assertThat(output).doesNotContain("请到微信公众号后台预览并手动发布");
        // 成功日志仍存在
        assertThat(output).contains("微信草稿创建成功");
    }

    // ===== Helpers =====

    private static Article sampleArticle() {
        return Article.builder()
                .id("tw-art-001")
                .title("标题-tw-art-001")
                .content("## hello")
                .source("来源:@test")
                .aiGenerated(true)
                .build();
    }

    private static WxMpDraftArticles sampleWxArticle(String html) {
        WxMpDraftArticles wx = new WxMpDraftArticles();
        wx.setTitle("标题-tw-art-001");
        wx.setDigest("digest");
        wx.setContent(html);
        wx.setThumbMediaId("");
        return wx;
    }

    private static WxErrorException wxError(int code, String message) {
        return new WxErrorException(new WxError(code, message));
    }
}
