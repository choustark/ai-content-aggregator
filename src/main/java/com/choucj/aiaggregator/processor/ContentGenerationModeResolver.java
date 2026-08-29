package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.wechat.config.OriginalPostProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterTargetProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内容生成模式解析器.
 *
 * <p>在进入 {@code ContentRewriter} 之前, 根据显式 override、原帖复现配置与指定内容信号
 * 解析当前 Tweet 应走的生成模式.
 *
 * <p><b>Story 8.6 D-G (per-tweet 判定):</b> target urls 信号路径从全局配置级收敛为
 * per-tweet 匹配 — 仅当 {@code tweet.url} 归一化 (trim + 去末尾斜杠) 后 equals 任一配置 url
 * 才路由 {@code PRESERVE_ORIGINAL}, 未命中 → {@code REWRITE};
 * {@code defaultMode=PRESERVE_ORIGINAL} (无 target urls) 路径保持全局语义不变。
 *
 * <p><b>指定抓取入口现状:</b> {@code discoverSpecifiedTweets} (指定内容 fetch 入口) 当前无生产
 * 调用方, 接线 defer (Story 8.6 scope 决策)。因此在 target urls 信号配置命中场景用进程级
 * {@link AtomicBoolean} warn 一次, 提示 PRESERVE 仅对 fetch 结果中命中 target urls 的推文生效。
 *
 * <p>{@code wechat.mp.original-post.enabled} 是唯一总开关: 关闭时任何配置组合都解析为
 * {@code REWRITE}, 防止 {@code default-mode} 旁路开关把全量流量送入未实现路径 (CR Round 1)。
 *
 * <p>引用源: Story 8.3 / Story 6.4 / Epic 8 AD-9 / Story 8.6 D-G.
 */
@Slf4j
@Component
public class ContentGenerationModeResolver {

    /**
     * 指定抓取入口未接线 warn-once 标记 (进程级, D-G)。
     */
    private final AtomicBoolean specifiedTargetWarned = new AtomicBoolean(false);

    private final OriginalPostProperties originalPostProperties;
    private final TwitterTargetProperties twitterTargetProperties;

    public ContentGenerationModeResolver(OriginalPostProperties originalPostProperties,
                                         TwitterTargetProperties twitterTargetProperties) {
        this.originalPostProperties = originalPostProperties;
        this.twitterTargetProperties = twitterTargetProperties;
    }

    /**
     * 解析 Tweet 的内容生成模式 (per-tweet 判定, D-G).
     *
     * @param tweet 当前正在处理的 Tweet; 其 {@code url} 与 target urls 归一化比对
     * @return 解析出的生成模式
     */
    public ContentGenerationMode resolve(Tweet tweet) {
        return resolve(tweet, null);
    }

    /**
     * 解析 Tweet 的内容生成模式, 允许调用方显式覆盖.
     *
     * <p>当前优先级:
     * <ol>
     *   <li>调用方显式 override</li>
     *   <li>{@code enabled=false} → 强制 {@code REWRITE} (总开关, CR Round 1)</li>
     *   <li>{@code enabled=true} 且 target urls 信号有效 → per-tweet URL 匹配:
     *       命中 → {@code PRESERVE_ORIGINAL}; 未命中 → {@code REWRITE}</li>
     *   <li>{@code enabled=true} 且无 target urls 信号 → 配置的 {@code default-mode}
     *       (全局语义, 8.3 行为不变)</li>
     * </ol>
     *
     * @param tweet 当前 Tweet
     * @param override 调用方显式覆盖; 为 {@code null} 时按配置与指定内容信号解析
     * @return 解析出的生成模式
     */
    public ContentGenerationMode resolve(Tweet tweet, @Nullable ContentGenerationMode override) {
        if (override != null) {
            return override;
        }
        if (!originalPostProperties.isEnabled()) {
            return ContentGenerationMode.REWRITE;
        }
        if (hasSpecifiedTargetSignal()) {
            warnSpecifiedEntryNotWiredOnce();
            boolean matched = tweet != null && matchesAnyTargetUrl(tweet.getUrl());
            return matched
                    ? ContentGenerationMode.PRESERVE_ORIGINAL
                    : ContentGenerationMode.REWRITE;
        }
        return originalPostProperties.getDefaultMode();
    }

    /** target urls 信号是否有效: {@code twitter.target.enabled=true} 且存在非空白 url。 */
    private boolean hasSpecifiedTargetSignal() {
        return twitterTargetProperties.isEnabled()
                && twitterTargetProperties.getUrls() != null
                && twitterTargetProperties.getUrls().stream().anyMatch(url -> url != null && !url.isBlank());
    }

    /**
     * per-tweet URL 匹配 (D-G): tweet.url 归一化 (trim + 去末尾斜杠) 后与任一配置 url
     * 归一化值 equals 即命中。
     */
    private boolean matchesAnyTargetUrl(String tweetUrl) {
        if (tweetUrl == null || tweetUrl.isBlank()) {
            return false;
        }
        String normalizedTweetUrl = normalizeUrl(tweetUrl);
        List<String> urls = twitterTargetProperties.getUrls();
        return urls != null && urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .anyMatch(url -> normalizeUrl(url).equals(normalizedTweetUrl));
    }

    /** URL 归一化: trim + 去末尾斜杠 (D-G)。null 安全。 */
    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        return trimmed.replaceAll("/+$", "");
    }

    /**
     * 信号命中场景进程级 warn 一次 (D-G): 指定抓取入口 ({@code discoverSpecifiedTweets})
     * 无生产调用方, PRESERVE 仅对 fetch 结果中命中 target urls 的推文生效。
     */
    private void warnSpecifiedEntryNotWiredOnce() {
        if (specifiedTargetWarned.compareAndSet(false, true)) {
            log.warn("指定抓取入口未接线, PRESERVE 仅对 fetch 结果中命中 target urls 的推文生效, "
                    + "未命中推文走 REWRITE");
        }
    }
}
