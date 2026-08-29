package com.choucj.aiaggregator.common.model;

/**
 * 内容生成模式枚举.
 *
 * <p>用于在编排层显式区分 AI 改写链路与原帖复现链路, 避免通过隐式 if/else
 * 或注释约定表达生成模式. 当前只交付 {@link #REWRITE} 与 {@link #PRESERVE_ORIGINAL},
 * 未来可扩展 {@code SUMMARY}、{@code EDITOR_NOTE} 等模式, 但本 Story 禁止预实现其行为.
 *
 * <p>引用源: Story 8.3 / Epic 8 AD-9.
 */
public enum ContentGenerationMode {

    /** 继续走现有 {@code ContentRewriter} 的 AI 改写路径. */
    REWRITE,

    /** 走独立原帖复现边界, 不允许回退到 {@code ContentRewriter}. */
    PRESERVE_ORIGINAL
}
