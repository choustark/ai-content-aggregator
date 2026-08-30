package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.publish.wechat.converter.MarkdownMediaInserter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9.1 Task 6: {@code REWRITE_WITH_MEDIA} 链路 Spring 装配集成测试.
 *
 * <p>验证 {@code wechat.mp.enabled=true} 时媒体感知生成链路整体可装配:
 * <ul>
 *   <li>{@link MediaAwareRewriteArticleGenerator} 按 {@code @ConditionalOnProperty} 注册
 *       (真 gateway 实现挂 wechat.mp.enabled=true, D6 规则)</li>
 *   <li>{@link MediaAwareRewriteGenerationGateway} 接口注入到
 *       {@link TwitterProcessor} 的 Optional 参数 (9 参构造器装配成功 = Bean 创建不抛异常)</li>
 *   <li>{@link MarkdownMediaInserter} 纯文本处理器常驻注册</li>
 * </ul>
 *
 * <p>纯装配验证, 不发起任何外部调用 (无 LLM/微信/X), 不挂 {@code @Tag("external")} —
 * 默认回归即可执行 (AC 3/AC 12 的集成级覆盖)。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "twitter.media.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
class MediaAwareRewriteWiringIntegrationTest {

    @Autowired(required = false)
    private MediaAwareRewriteGenerationGateway mediaAwareRewriteGenerationGateway;

    @Autowired(required = false)
    private TwitterProcessor twitterProcessor;

    @Autowired(required = false)
    private MarkdownMediaInserter markdownMediaInserter;

    @Test
    void should_wire_media_aware_rewrite_chain_when_wechat_enabled() {
        assertThat(mediaAwareRewriteGenerationGateway)
                .as("wechat.mp.enabled=true 时媒体感知 gateway 应注册")
                .isNotNull()
                .isInstanceOf(MediaAwareRewriteArticleGenerator.class);
        // TwitterProcessor 含新 gateway 的 9 参构造器装配成功 (Bean 已创建)
        assertThat(twitterProcessor).as("TwitterProcessor 装配成功").isNotNull();
        assertThat(markdownMediaInserter).as("MarkdownMediaInserter 常驻注册").isNotNull();
    }
}
