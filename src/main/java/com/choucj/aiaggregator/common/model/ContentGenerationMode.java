package com.choucj.aiaggregator.common.model;

/**
 * 内容生成模式枚举.
 *
 * <p>用于在编排层显式区分各生成链路, 避免通过隐式 if/else 或注释约定表达生成模式.
 * 当前三种模式语义严格可区分 (Story 9.1 AD-1):
 * <ul>
 *   <li>{@link #REWRITE} — 纯 AI 改写 Markdown ({@code ContentRewriter} 产出, 无媒体副作用)</li>
 *   <li>{@link #PRESERVE_ORIGINAL} — 确定性原帖复现已转义安全 HTML (OriginalPostRenderer 产出)</li>
 *   <li>{@link #REWRITE_WITH_MEDIA} — AI 改写 Markdown + 原帖 PHOTO 媒体归档/微信上传/
 *       Markdown 图片嵌入 (MediaAwareRewriteArticleGenerator 编排)</li>
 * </ul>
 *
 * <p>未来可扩展 {@code SUMMARY}、{@code EDITOR_NOTE} 等模式, 但禁止预实现其行为.
 *
 * <p>引用源: Story 8.3 / Epic 8 AD-9 / Story 9.1 AD-1.
 */
public enum ContentGenerationMode {

    /** 继续走现有 {@code ContentRewriter} 的 AI 改写路径, 不下载/上传/嵌入任何媒体. */
    REWRITE,

    /** 走独立原帖复现边界, 不允许回退到 {@code ContentRewriter}. */
    PRESERVE_ORIGINAL,

    /**
     * 媒体感知 AI 改写: LLM 只产出改写 Markdown 正文, 原帖 PHOTO 媒体经确定性链路
     * (归档 → gate → 微信上传 → sidecar 重读) 以 Markdown image syntax 插入正文.
     *
     * <p>不得折叠成 {@link #REWRITE} 的 flag, 也不允许回退到任一其他模式 (Story 9.1 AD-10).
     */
    REWRITE_WITH_MEDIA
}
