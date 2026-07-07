package com.choucj.aiaggregator.publish.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 3.4 AC-5 — wechat.mp.enabled=false 时混合发布 Bean 全部不注册.
 *
 * <p>验证 {@link PublishingModeDecider} 和 {@link BatchPublishingScheduler} 的
 * {@code @ConditionalOnProperty(prefix="wechat.mp", name="enabled", havingValue="true")}
 * 级联条件生效.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=false",
        "schedule.run-on-startup=false"
})
class PublishingBeanConditionalTest {

    @Autowired(required = false)
    private PublishingModeDecider publishingModeDecider;

    @Autowired(required = false)
    private BatchPublishingScheduler batchPublishingScheduler;

    @Test
    void publishingBeansNotRegisteredWhenWechatDisabled() {
        assertThat(publishingModeDecider).isNull();
        assertThat(batchPublishingScheduler).isNull();
    }
}
