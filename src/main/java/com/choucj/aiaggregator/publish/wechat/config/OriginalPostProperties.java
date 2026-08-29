package com.choucj.aiaggregator.publish.wechat.config;

import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 原帖复现模式配置属性.
 *
 * <p>绑定 {@code wechat.mp.original-post.*}, 负责声明 Epic 8 的原帖复现总开关与默认模式.
 * 当前默认保持关闭 + {@link ContentGenerationMode#REWRITE}, 以保证既有改写链路零回归.
 *
 * <p>引用源: Story 8.3 / project-context 配置拆分规则.
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
     * <p>仅在 {@code enabled=true} 时生效. 当前默认必须保持
     * {@link ContentGenerationMode#REWRITE}, 避免对现有批量抓取 + 改写 + 发布链路
     * 产生静默回归. 未来可在显式配置下切换到其他模式.
     */
    private ContentGenerationMode defaultMode = ContentGenerationMode.REWRITE;
}
