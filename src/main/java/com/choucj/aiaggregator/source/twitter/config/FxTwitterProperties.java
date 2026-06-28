package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * FxTwitter 客户端配置.
 *
 * <p>FxTwitter 是第三方 Twitter 代理({@code https://github.com/FixTweet/FxTwitter}),
 * 提供 {@code /i/status/{tweetId}.json} 端点返回单条推文详情(content / 互动数 / 图片).
 * Story 2.2a 用来补全 RSSHub 缺失的字段.
 *
 * <p><b>架构 delta (Story 2.2a):</b> 不像 RSSHub 支持多实例备份, FxTwitter 仅单实例
 * (官方公共实例 {@code https://api.fxtwitter.com}), 原因:
 * <ul>
 *   <li>FxTwitter 公共实例稳定性较好(Vercel global edge 部署), 限流概率低于 RSSHub</li>
 *   <li>多实例备份机制会在 Story 2.2b(twscrape 抓取)中作为降级链路单独设计</li>
 *   <li>简化 MVP 阶段逻辑, tweet:{id} Redis 缓存(24h TTL)已大幅降低实际调用量</li>
 * </ul>
 *
 * <p>调用方 {@link com.choucj.aiaggregator.source.twitter.client.FxTwitterClient} 通过
 * {@link #isEnabled()} 实现降级开关 — 关闭时 {@link com.choucj.aiaggregator.source.twitter.TwitterSource}
 * 仅返回 RSSHub 部分字段.
 */
@ConfigurationProperties(prefix = "fxtwitter")
@Validated
@Data
public class FxTwitterProperties {

    /** FxTwitter 总开关. 关闭时跳过 FxTwitter 调用, 仅使用 RSSHub 部分字段(降级模式). */
    private boolean enabled = true;

    /** HTTP 调用超时(秒). 默认 15s, FxTwitter 单次调用通常 <2s. */
    @Min(value = 1, message = "fxtwitter.timeout-seconds must be positive")
    private int timeoutSeconds = 15;

    /**
     * FxTwitter 实例 URL(末尾不带斜杠).
     * <p>默认 {@code https://api.fxtwitter.com} — 官方公共实例. 自部署可指向 docker 镜像.
     */
    private String instance = "https://api.fxtwitter.com";
}
