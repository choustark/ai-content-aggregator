package com.choucj.aiaggregator.publish.wechat.client;

import com.choucj.aiaggregator.common.model.Article;

/**
 * 微信公众号客户端抽象 — 屏蔽 WxJava SDK 细节, 提供 publish 路径所需的最小契约.
 *
 * <p>设计意图: Pipeline (Story 2.6) 调用 {@link com.choucj.aiaggregator.publish.ContentPublisher},
 * WeChatPublisher (Story 3.3) 通过本接口与微信 API 交互, 单元测试可 mock 本接口, 不依赖真实 SDK.
 *
 * <p>spike 状态: 仅接口定义 + 占位常量, 方法签名留待 Story 3.1 根据 WxMpService 实际 API 细化.
 */
public interface WeChatClient {

    /**
     * 获取微信公众号 access token (调试 / 连通性测试入口).
     *
     * <p>spike 调试用: 调用方 (如 WxJavaConnectivitySmokeTest) 通过本方法验证 wx.mp.app-id / secret 配置正确性,
     * 同时验证网络可达 + IP 白名单已配置 (微信公众号后台 IP 白名单未配置时, 此方法会抛 WxErrorException 40164).
     *
     * @return access token 字符串, 失败抛 {@code RetryableException(EXTERNAL_API_ERROR)}
     */
    String getAccessToken();

    /**
     * 将 Article 上传为微信公众号草稿 (Story 3.3 实施入口).
     *
     * <p>Story 3.1 仅声明签名, 实现见 Story 3.3 WeChatPublisher.publish.
     *
     * @param article 待发布文章 (不应为 null)
     * @return 微信草稿 media_id (用于后续人工发布操作), 失败抛 Retryable/NonRetryable
     */
    String addDraft(Article article);
}
