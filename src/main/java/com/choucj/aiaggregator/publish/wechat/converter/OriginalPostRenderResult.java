package com.choucj.aiaggregator.publish.wechat.converter;

import java.util.List;
import java.util.Objects;

/**
 * 原帖确定性渲染结果，承载 {@link OriginalPostRenderer} 的全部产物。
 *
 * <p>镜像 {@code WeChatMediaPreparer.MediaPreparationResult} 的暴露模式（计数 + 摘要列表）：
 * {@code embeddedImageCount}/{@code degradedMediaCount} 供日志与调用方观测，
 * {@code embeddedImageUrls} 供日志摘要与「无外部 URL」测试断言 (AD-4)。
 *
 * <p><b>边界：</b> 本结果只承载确定性渲染产物；微信草稿装配
 * ({@code WxMpDraftArticles}/thumbMediaId) 属 Story 8.6 发布侧职责，不在本模型内。
 *
 * <p>引用源: Story 8.5(创建) / ARCHITECTURE-SPINE AD-2、AD-3。
 *
 * @param title              确定性派生标题 (≤64 codepoints, AC10)
 * @param digest             确定性派生摘要 (≤120 codepoints, AC10)
 * @param html               微信草稿正文 HTML (img src 仅来自 wechatUrl, AC2)
 * @param embeddedImageCount 成功嵌入的图片数
 * @param degradedMediaCount 降级呈现 (提示文案) 的媒体数
 * @param embeddedImageUrls  实际嵌入的微信图片 URL 列表；无嵌入图片时为空列表
 */
public record OriginalPostRenderResult(String title, String digest, String html,
                                       int embeddedImageCount, int degradedMediaCount,
                                       List<String> embeddedImageUrls) {

    public OriginalPostRenderResult {
        // CR Round 1 patch#9: 本 record 是 public API (8.6 调用方可手工构造)，
        // 三个字符串字段 fail-fast 拒绝 null (否则错误延迟到微信草稿装配处才爆发)；
        // embeddedImageUrls 整体 null → 空列表，含 null 元素 → 过滤 (而非无信息 NPE)
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(html, "html");
        embeddedImageUrls = embeddedImageUrls == null
                ? List.of()
                : embeddedImageUrls.stream().filter(Objects::nonNull).toList();
    }
}
