package com.choucj.aiaggregator.publish.wechat.client;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link WeChatClient} 的 WxJava 实现.
 *
 * <p>Story 3.1 已实施 access token 获取、stable token 日志和微信错误码异常映射.
 * 依赖 starter 4.6.0 自动注册的 {@link WxMpService} Bean (HttpClient 实现).
 *
 * <p>{@link #addDraft(Article)} 仍为 Story 3.3 的草稿发布占位入口, 先按不可重试业务异常 fail-fast.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WxJavaWeChatClient implements WeChatClient {

    private final WxMpService wxMpService;
    private final WeChatProperties weChatProperties;
    private final SlowOperationRecorder slowOperationRecorder;

    @Override
    public String getAccessToken() {
        String token;
        try {
            token = slowOperationRecorder.observe(
                    Kind.SDK,
                    Dependency.WECHAT,
                    Operation.AUTHENTICATE,
                    () -> {
                        try {
                            return wxMpService.getAccessToken();
                        } catch (WxErrorException exception) {
                            throw mapWxErrorException(exception, "getAccessToken");
                        }
                    });
        } catch (RuntimeException e) {
            if (e instanceof RetryableException || e instanceof NonRetryableException) {
                throw e;
            }
            // W1+W2: 防 SDK / 框架异常逃逸到调度器顶层.
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava 框架异常: " + SingleModelRewriter.getRootMessage(e),
                    e);
        }

        if (token == null) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava getAccessToken 返回 null token");
        }
        log.info("WxJava access token acquired (length={}, stable={})",
                token.length(),
                weChatProperties.getClient().isStableAccessToken());
        return token;
    }

    @Override
    public String addDraft(Article article) {
        // Story 3.3 §3.4 决策 (方案 A): 保留 WeChatClient.addDraft(Article) 接口占位,
        // 不在本 story 实施. WeChatPublisher 走 converter → WxMpDraftArticles → WxMpDraftService
        // 路径, 与 WeChatClient.addDraft(Article) 的 "Article 直通" 语义不符.
        // 此接口预留给未来需要 article → media_id 直通的场景 (如 Story 3.4 批量发布).
        throw new NonRetryableException(
                ErrorCode.WECHAT_API_ERROR,
                "Story 3.3 WeChatPublisher 将实施 addDraft");
    }

    public static RuntimeException mapWxErrorException(WxErrorException e, String operation) {
        WxError error = e.getError();
        int errcode = error == null ? -1 : error.getErrorCode();
        String errmsg = error == null ? "unknown" : error.getErrorMsg();
        String base = "微信 " + operation + " 失败: errcode=" + errcode
                + ", errmsg=" + SingleModelRewriter.truncateForLog(errmsg, 200);

        return switch (errcode) {
            case 40014 -> new RetryableException(ErrorCode.WECHAT_TOKEN_EXPIRED, base, e);
            case -1 -> new RetryableException(ErrorCode.WECHAT_API_ERROR, base, e);
            case 40001 -> new NonRetryableException(ErrorCode.WECHAT_INVALID_CREDENTIAL, base, e);
            case 40164, 45009 -> new NonRetryableException(ErrorCode.WECHAT_API_ERROR, base, e);
            default -> new NonRetryableException(ErrorCode.WECHAT_API_ERROR, base, e);
        };
    }

}
