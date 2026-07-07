package com.choucj.aiaggregator.publish.wechat;

import com.binarywang.spring.starter.wxjava.mp.config.WxMpAutoConfiguration;
import com.choucj.aiaggregator.publish.wechat.config.WeChatConfig;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.config.WxMpConfigStorage;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 3.1 — WeChatConfig stable access token Bean 条件装配测试.
 */
class WeChatConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WxMpAutoConfiguration.class))
            .withUserConfiguration(WeChatConfig.class)
            .withPropertyValues(
                    "wechat.mp.enabled=true",
                    "wx.mp.app-id=wx-test-app",
                    "wx.mp.secret=test-secret");

    @Test
    void wxMpConfigStorageBeanRegisteredWhenStableEnabled() {
        contextRunner
                .withPropertyValues("wechat.mp.client.stable-access-token=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WxMpConfigStorage.class);

                    WxMpConfigStorage storage = context.getBean(WxMpConfigStorage.class);
                    assertThat(storage).isInstanceOf(WxMpDefaultConfigImpl.class);
                    assertThat(storage.getAppId()).isEqualTo("wx-test-app");
                    assertThat(storage.getSecret()).isEqualTo("test-secret");
                    assertThat(storage.isStableAccessToken()).isTrue();
                });
    }

    @Test
    void wxMpConfigStorageFallsBackToStarterDefaultWhenStableDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WxMpAutoConfiguration.class))
                .withUserConfiguration(WeChatConfig.class)
                .withPropertyValues(
                        "wechat.mp.enabled=true",
                        "wx.mp.app-id=wx-test-app",
                        "wx.mp.secret=test-secret",
                        "wechat.mp.client.stable-access-token=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(WxMpConfigStorage.class);

                    WxMpConfigStorage storage = context.getBean(WxMpConfigStorage.class);
                    assertThat(storage).isInstanceOf(WxMpDefaultConfigImpl.class);
                    assertThat(storage.getAppId()).isEqualTo("wx-test-app");
                    assertThat(storage.getSecret()).isEqualTo("test-secret");
                    assertThat(storage.isStableAccessToken()).isFalse();
                });
    }

    @Test
    void projectLayerConfigStorageBeanSkippedWhenWechatDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(WeChatConfig.class)
                .withPropertyValues(
                        "wechat.mp.enabled=false",
                        "wechat.mp.client.stable-access-token=true")
                .run(context -> assertThat(context).doesNotHaveBean(WxMpConfigStorage.class));
    }

    @Test
    void wxMpConfigStorageFailsFastWhenAppIdMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WxMpAutoConfiguration.class))
                .withUserConfiguration(WeChatConfig.class)
                .withPropertyValues(
                        "wechat.mp.enabled=true",
                        "wx.mp.secret=test-secret",
                        "wechat.mp.client.stable-access-token=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("wx.mp.app-id must be configured when wechat.mp.enabled=true");
                });
    }

    @Test
    void wxMpServiceUsesProjectLayerConfigStorageWhenStableEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WxMpAutoConfiguration.class))
                .withUserConfiguration(WeChatConfig.class)
                .withPropertyValues(
                        "wx.mp.app-id=wx-test-app",
                        "wx.mp.secret=test-secret",
                        "wechat.mp.enabled=true",
                        "wechat.mp.client.stable-access-token=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WxMpConfigStorage.class);
                    assertThat(context).hasSingleBean(WxMpService.class);

                    WxMpConfigStorage storage = context.getBean(WxMpConfigStorage.class);
                    WxMpService service = context.getBean(WxMpService.class);
                    assertThat(storage).isSameAs(service.getWxMpConfigStorage());
                    assertThat(storage).isInstanceOf(WxMpDefaultConfigImpl.class);
                    assertThat(storage.isStableAccessToken()).isTrue();
                });
    }
}
