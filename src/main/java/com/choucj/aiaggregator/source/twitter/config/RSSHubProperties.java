package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * RSSHub 实例配置.
 *
 * <p>支持多实例主备切换 — {@link #instances} 列表按顺序尝试, 主实例失败(连接异常 / 5xx / 超时)自动切到下一个.
 * 公网实例 {@code https://rsshub.app} 经常被限流, 建议配置至少 1 个备用(自部署 docker / 第三方镜像).
 *
 * <p>Story 2.1 引入, 不复用 {@code common/config} 包, 因为这是 source/twitter 模块的私有配置.
 */
@ConfigurationProperties(prefix = "rsshub")
@Validated
@Data
public class RSSHubProperties {

    /** RSSHub 总开关. 关闭时 {@code RSSHubClient.discoverTweets} 直接返回空列表. */
    private boolean enabled = true;

    /** HTTP 调用超时(秒). 默认 30s, RSSHub 单次抓取通常 1-5s, 超过 30s 视为卡死切下一实例. */
    @Min(value = 1, message = "rsshub.timeout-seconds must be positive")
    private int timeoutSeconds = 30;

    /**
     * RSSHub 实例 URL 列表, 主备顺序.
     * <p>启动时 fail-fast 校验非空(enabled=true 时). 末尾不带斜杠, 调用方拼接为 {@code {instance}/twitter/user/{username}.json}.
     */
    @NotEmpty(message = "rsshub.instances must be configured when rsshub.enabled=true (at least 1 instance)")
    private List<String> instances = new ArrayList<>();
}
