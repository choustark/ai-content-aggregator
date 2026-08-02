package com.choucj.aiaggregator.source.twitter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 指定 X 内容抓取入口配置 (Story 6.4).
 *
 * <p>命名空间 {@code twitter.target.*}, 与 {@link ApifyTwitterProperties}({@code apify.twitter.*}) 正交:
 * 本配置表达「指定哪些 X 内容」(post URL / post ID / Article URL), Apify 配置表达「如何调用 Actor」.
 *
 * <p>引用源:Story 6.4 / Epic 6 AD-8 / spike-6.1 F1. 集成型开关, 默认关闭
 * ({@code havingValue="true"} 不带 {@code matchIfMissing}); 关闭时指定内容入口完全不激活,
 * 不调用 Apify, 不消费配额, 既有账号发现链路 (Story 6.3) 零回归.
 *
 * <p><b>urls</b> 元素可为: x.com/twitter.com 的 {@code /status/{id}} URL、纯数字 post ID、
 * 或 Article URL. {@code ApifyDiscoveryClient} 负责归一化为 spike-6.1 F1 实测可用的
 * {@code https://x.com/.../status/{id}} 对象数组形态. 纯 post ID / Article URL 为 spike 未测变体
 * (Q1a/Q1b), 采用默认实现, 配额恢复后补测.
 */
@ConfigurationProperties(prefix = "twitter.target")
@Validated
@Data
public class TwitterTargetProperties {

    /**
     * 指定内容入口总开关. 默认 {@code false} (集成型开关).
     *
     * <p>关闭时 {@code ApifyDiscoveryClient.discoverSpecifiedTweets} 直接返回空列表, 不调 Apify.
     */
    private boolean enabled = false;

    /**
     * 指定 X 内容目标列表 (post URL / post ID / Article URL).
     *
     * <p>允许空列表 (降级 / 测试场景); 空列表或 {@code enabled=false} 时返回空, 不抛出.
     */
    private List<String> urls = new ArrayList<>();
}
