package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.publish.wechat.client.WeChatClient;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 3.1 Round 2 — 微信集成默认关闭时不注册项目层或 SDK 层 Bean.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=false",
        "schedule.run-on-startup=false"
})
class WxJavaDisabledSmokeTest {

    @Autowired(required = false)
    private WeChatClient weChatClient;

    @Autowired(required = false)
    private WxMpService wxMpService;

    @Test
    void wechatIntegrationBeansNotRegisteredWhenDisabled() {
        assertThat(weChatClient).isNull();
        assertThat(wxMpService).isNull();
    }
}
