package com.choucj.aiaggregator.source.twitter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Twitter 数据源编排配置.
 *
 * <p>定义 Twitter 抓取目标账号列表, {@link com.choucj.aiaggregator.source.twitter.TwitterSource}
 * 遍历此列表逐个调用配置的发现 provider 链.
 *
 * <p><b>命名规范:</b> 账号 handle <b>不带</b> {@code @} 前缀(如 {@code "karpathy"} 而非 {@code "@karpathy"}),
 * URL 拼接时由调用方处理.
 *
 * <p><b>不做 @NotEmpty 校验:</b> 允许空列表(测试 / 降级场景), 空列表时 {@code TwitterSource.fetch()} 返回空.
 */
@ConfigurationProperties(prefix = "twitter")
@Validated
@Data
public class TwitterProperties {

    /**
     * 旧版单 provider 配置. 默认 rsshub; 仅在 discoveryProviders 为空时作为兼容兜底.
     */
    private String discoveryProvider = "rsshub";

    /**
     * 推文发现 provider 优先级链. 可配置为 scraper / apify / rsshub 的任意顺序.
     * <p>为空时回退到 {@link #discoveryProvider}.
     */
    private List<String> discoveryProviders = new ArrayList<>();

    /**
     * Twitter 账号 handle 列表(不带 @).
     * <p>配置示例:
     * <pre>{@code
     * twitter:
     *   accounts:
     *     - karpathy
     *     - sama
     * }</pre>
     */
    private List<String> accounts = new ArrayList<>();

    public List<String> effectiveDiscoveryProviders() {
        List<String> normalized = normalizeProviderNames(discoveryProviders);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        if (StringUtils.hasText(discoveryProvider)) {
            return normalizeProviderNames(List.of(discoveryProvider));
        }
        return List.of("rsshub");
    }

    private List<String> normalizeProviderNames(List<String> providerNames) {
        if (providerNames == null || providerNames.isEmpty()) {
            return List.of();
        }
        return providerNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }
}
