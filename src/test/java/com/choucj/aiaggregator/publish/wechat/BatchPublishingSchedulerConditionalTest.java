package com.choucj.aiaggregator.publish.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 3.4 AC-5 — wechat.mp.enabled=true 但 batch-enabled=false 时
 * {@link BatchPublishingScheduler} 不注册 (实时路径仍可用).
 *
 * <p>验证 {@code OnWeChatAndBatchEnabled} 双条件级联 (AllNestedConditions):
 * 仅 wechat.mp.enabled 满足时 PublishingModeDecider 注册, BatchPublishingScheduler 不注册.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 清空 application.yml 默认的 spring.autoconfigure.exclude, 让 WxMpAutoConfiguration 生效
        // (Story 3.1 设计: 默认排除 WxMpAutoConfiguration, 仅 wechat profile 或显式清空时启用)
        "spring.autoconfigure.exclude=",
        "wechat.mp.enabled=true",
        "wechat.mp.publishing.batch-enabled=false",
        "archive.enabled=false",
        "schedule.run-on-startup=false",
        "wx.mp.app-id=wx-test",
        "wx.mp.secret=test-secret"
})
class BatchPublishingSchedulerConditionalTest {

    @Autowired(required = false)
    private PublishingModeDecider publishingModeDecider;

    @Autowired(required = false)
    private BatchPublishingScheduler batchPublishingScheduler;

    @Test
    void onlyBatchSchedulerSkippedWhenBatchDisabled() {
        assertThat(publishingModeDecider).isNotNull();
        assertThat(batchPublishingScheduler).isNull();
    }
}
