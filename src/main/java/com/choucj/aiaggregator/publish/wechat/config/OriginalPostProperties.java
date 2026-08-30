package com.choucj.aiaggregator.publish.wechat.config;

import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 原帖复现/媒体感知发布模式配置属性.
 *
 * <p>绑定 {@code wechat.mp.original-post.*}, 负责声明生成模式路由总开关与默认模式.
 * 命名沿用 Epic 8 既有 namespace (Story 9.1 AD-5 假设: 暂不做 {@code content.generation.*}
 * 迁移, 避免重命名 churn), 但语义已扩展为覆盖三种生成模式 — 包括 Story 9.1 新增的
 * {@link ContentGenerationMode#REWRITE_WITH_MEDIA}.
 *
 * <p>代码默认保持关闭 + {@link ContentGenerationMode#REWRITE}, 防御性兜底;
 * 部署目标默认流程由 {@code application.yml} 的 {@code default-mode: REWRITE_WITH_MEDIA}
 * 体现 (Story 9.1 AC11), 且仅在 {@code enabled=true} 时生效.
 *
 * <p>引用源: Story 8.3 / Story 9.1 AD-5 / project-context 配置拆分规则.
 */
@ConfigurationProperties(prefix = "wechat.mp.original-post")
@Validated
@Getter
@Setter
public class OriginalPostProperties {

    /**
     * 原帖复现总开关.
     *
     * <p>默认 {@code false}. 关闭时模式解析器对任何配置组合都返回 {@code REWRITE},
     * {@code default-mode} 不生效 (CR Round 1 归约语义).
     */
    private boolean enabled = false;

    /**
     * 默认生成模式.
     *
     * <p>仅在 {@code enabled=true} 时生效; target urls 命中场景同样返回本值
     * (Story 9.1 AD-6). 代码默认保持 {@link ContentGenerationMode#REWRITE} 防御性兜底;
     * 批量抓取默认目标流程 (AI 改写 + 媒体草稿) 由 {@code application.yml} 显式配置
     * {@code default-mode: REWRITE_WITH_MEDIA} 体现 (Story 9.1 AC11 / AD-5)。
     * 显式配置 {@code REWRITE} / {@code PRESERVE_ORIGINAL} 继续可用且有零回归测试。
     */
    private ContentGenerationMode defaultMode = ContentGenerationMode.REWRITE;
}
