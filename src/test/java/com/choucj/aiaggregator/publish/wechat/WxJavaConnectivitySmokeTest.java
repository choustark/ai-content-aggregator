package com.choucj.aiaggregator.publish.wechat;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.wechat.client.WeChatClient;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 3.1 spike — WxJava 4.6.0 连通性冒烟测试.
 *
 * <p>三层测试场景:
 * <ol>
 *   <li>{@link #wxMpServiceBeanRegistered()} — 单元层: starter 自动配置生效, WxMpService Bean 注册成功 (无凭据也可验证).</li>
 *   <li>{@link #wechatClientBeanRegisteredWhenEnabled()} — 配置层: wechat.mp.enabled=true 时 WeChatClient Bean 注册.</li>
 *   <li>{@link #getAccessTokenWithRealCredentials()} — 集成层: 调用真实微信 API 获取 token, 仅在 WX_MP_APP_ID 环境变量存在时执行.</li>
 * </ol>
 *
 * <p>设计模式: 复用 Story 1.5a {@code RedisClusterConnectivityTest} 的 {@code assumeThat} 跳过模式 —
 * 无凭据场景静默跳过, 有凭据 (env WX_MP_APP_ID / WX_MP_SECRET) 时真实调用.
 *
 * <p><b>spike 调试方法</b>: 设置 wx.mp.app-id / secret 真实测试号凭据后运行:
 * <pre>
 * WX_MP_APP_ID=wx_xxx WX_MP_SECRET=xxx \
 *   ./mvnw test -Dtest=WxJavaConnectivitySmokeTest#getAccessTokenWithRealCredentials
 * </pre>
 *
 * <p>常见失败: 微信公众号后台 IP 白名单未配置 → {@code WxErrorException} 40164.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Slf4j
@org.junit.jupiter.api.Tag("external")
class WxJavaConnectivitySmokeTest {

    @Autowired
    private WxMpService wxMpService;

    @Autowired(required = false)
    private WeChatClient weChatClient;

    @Autowired
    private WeChatProperties weChatProperties;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Autowired(required = false)
    private WeChatPublisher weChatPublisher;

    @Autowired
    private List<ContentPublisher> contentPublishers;

    @Test
    void wxMpServiceBeanRegistered() {
        // 验证 starter 4.6.0 自动配置链:
        // WxMpAutoConfiguration → WxMpStorageAutoConfiguration (Memory 默认) → WxMpServiceAutoConfiguration (HttpClient 默认)
        assertThat(wxMpService)
                .as("starter 4.6.0 应自动注册 WxMpService Bean (@ConditionalOnMissingBean)")
                .isNotNull();

        // 验证 WxMpConfigStorage 已注入 (否则 getAccessToken 会 NPE)
        assertThat(wxMpService.getWxMpConfigStorage())
                .as("WxMpConfigStorage 应由 WxMpStorageAutoConfiguration 注册 (默认 WxMpDefaultConfigImpl)")
                .isNotNull();
    }

    @Test
    void wechatClientBeanRegisteredWhenEnabled() {
        // wechat.mp.enabled=true (本测试类 @TestPropertySource 设置), WeChatClient 应注册
        // 反之 wechat.mp.enabled=false (默认) 时, @ConditionalOnProperty 跳过注册
        assertThat(weChatClient)
                .as("wechat.mp.enabled=true 时 WxJavaWeChatClient 应注册")
                .isNotNull();
    }

    @Test
    void weChatPublisherBeanRegisteredAsDelegateWhenEnabled() {
        // Story 3.3 AC-1: wechat.mp.enabled=true + WxMpService Bean 已注册时,
        // WeChatPublisher Bean 注册成功. Story 3.4 D1 决策后它只作为微信草稿 delegate,
        // 不再暴露为 ContentPublisher, 避免 TwitterProcessor 绕过 PublishingModeDecider 重复发布.
        assertThat(weChatPublisher)
                .as("wechat.mp.enabled=true 时 WeChatPublisher 应注册")
                .isNotNull();
        assertThat(weChatPublisher)
                .as("WeChatPublisher 不应作为 ContentPublisher 直接进入 Pipeline")
                .isNotInstanceOf(ContentPublisher.class);
        assertThat(contentPublishers)
                .as("ContentPublisher 列表不应包含 raw WeChatPublisher 实例")
                .noneMatch(weChatPublisher::equals)
                .anyMatch(PublishingModeDecider.class::isInstance);
    }

    @Test
    @Timeout(30)
    void getAccessTokenWithRealCredentials() {
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        // 双重保险: 假设 WeChatClient Bean 已注册
        assumeThat(weChatClient)
                .as("WeChatClient Bean 应已注册 (wechat.mp.enabled=true)")
                .isNotNull();

        String token;
        try {
            token = weChatClient.getAccessToken();
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        assertThat(token)
                .as("真实凭据下 getAccessToken 应返回非空 token (约 120 字符)")
                .isNotNull()
                .isNotEmpty();
        assertThat(token.length())
                .as("微信公众号 access_token 通常为 116~120 字符")
                .isBetween(100, 200);

        log.info("WxJava getAccessToken OK (length={}, stable={})",
                token.length(),
                weChatProperties.getClient().isStableAccessToken());
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId());
    }

    private static void skipIfEnvironmentBlocked(AggregatorException e) {
        String message = e.getMessage();
        if (message == null) {
            return;
        }
        boolean whitelistBlocked = message.contains("errcode=40164");
        boolean dnsBlocked = message.contains("UnknownHostException")
                || message.contains("nodename nor servname provided")
                || message.contains("api.weixin.qq.com");
        boolean quotaBlocked = message.contains("errcode=45009");
        assumeTrue(!(whitelistBlocked || dnsBlocked || quotaBlocked), "跳过: 外部微信环境阻塞 - " + message);
    }
}
