package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.publish.wechat.client.WeChatClient;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 3.1 Round 2 — 微信发布默认关闭时不注册项目层 Bean.
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
    void projectWechatBeansNotRegisteredWhenDisabled() {
        assertThat(weChatClient).isNull();
        // WxMpService belongs to the WxJava starter layer and may be auto-configured
        // even when the project publishing switch is disabled.
        assertThat(wxMpService).isNotNull();
    }
}
