package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Story 10.10: VIDEO variant 确定性选择器 (纯函数, 无副作用, 独立可单测).
 *
 * <p>选择规则 (与 provider variants 列表顺序无关):
 * <ol>
 *   <li><b>MIME 白名单过滤</b> — 仅保留 {@code contentType=video/mp4} 的 variant
 *       (trim 后忽略大小写比较); HLS(m3u8)/webm 等非 mp4 variant 一律排除,
 *       且不因候选失败回退到非 mp4 候选 (Story 10.10 Never)</li>
 *   <li><b>URL 非空过滤</b> — url 为 null/blank 的 variant 不可下载, 视为不合法</li>
 *   <li><b>bitrate 降序</b> — null bitrate 排最后 (HLS/未知码率不参与优先)</li>
 *   <li><b>URL 字典序升序 tie-break</b> — 同码率时选中 URL 字典序最小者, 保证重复运行结果一致</li>
 * </ol>
 *
 * <p>输出为 {@link Optional}: empty 表示"过滤后无合法候选" (调用方映射 FAILED_TERMINAL),
 * 不抛业务异常, 便于纯单测枚举确定性 (Story 10.10 Design Notes)。
 *
 * <p>引用源: Story 10.10 Boundaries (确定性选择 + video/mp4 代码内常量) / I/O 矩阵
 * 「多 mp4 不同码率」「同码率确定性」「无合法候选」。
 */
public final class VideoVariantSelector {

    /** 允许下载的 VIDEO MIME 白名单 — 代码内常量, 不引入配置面 (10.9 门禁未 Go, 未验证其他格式). */
    public static final String ALLOWED_VIDEO_MIME = "video/mp4";

    private VideoVariantSelector() {
    }

    /**
     * 从 variants 中确定性选出唯一下载候选.
     *
     * @param variants provider 返回的候选列表 (可为 null/含 null 元素)
     * @return 选中的 mp4 variant; 过滤后无合法候选时 empty
     */
    public static Optional<TweetMediaVariant> select(List<TweetMediaVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        return variants.stream()
                .filter(Objects::nonNull)
                .filter(v -> v.getUrl() != null && !v.getUrl().isBlank())
                .filter(v -> v.getContentType() != null
                        && ALLOWED_VIDEO_MIME.equalsIgnoreCase(v.getContentType().trim()))
                .min(Comparator
                        .comparing(TweetMediaVariant::getBitrate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TweetMediaVariant::getUrl));
    }
}
