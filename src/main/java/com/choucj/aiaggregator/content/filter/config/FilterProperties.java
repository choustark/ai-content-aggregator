package com.choucj.aiaggregator.content.filter.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 内容筛选器配置 (Story 2.3b).
 *
 * <p>对应 {@link com.choucj.aiaggregator.content.filter.CommentFilter} 与
 * {@link com.choucj.aiaggregator.content.filter.InnovationFilter} 的阈值参数:
 * <ul>
 *   <li>{@link #commentThreshold} — 评论数下限, {@code replyCount &gt;= 该值} 才进入下一阶段</li>
 *   <li>{@link #innovationThreshold} — LLM 创新度评分下限 (1-10), {@code innovationScore &gt;= 该值} 才保留</li>
 * </ul>
 *
 * <p>配置示例 ({@code application.yml}):
 * <pre>{@code
 * filter:
 *   comment-threshold: 10
 *   innovation-threshold: 6.0
 * }</pre>
 *
 * <p>架构 delta (Story 2.3b): 两级筛选 (评论数 → AI 创新度) 串联以降低 Story 2.4 改写环节的 Token 成本,
 * 默认值 10 / 6.0 来自 PRD FR3 调研结论.
 *
 * <p>校验 (W7/W8/N1 修订, 2026-06-28 review):
 * <ul>
 *   <li>W7: {@code innovationThreshold} 用 {@link DecimalMin} 而非 {@link Min} (Jakarta 规范,
 *       {@code @Min} 仅适用于整型 / {@code BigDecimal})</li>
 *   <li>W8: {@code innovationThreshold} 加 {@link DecimalMax}("10.0") 上限, 防配 {@code 11}
 *       静默剔除全部推文</li>
 *   <li>N1: {@code commentThreshold} 加 {@link Max}(1_000_000) 合理上界, 防配
 *       {@code Integer.MAX_VALUE} 静默剔除全部</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "filter")
@Validated
@Data
public class FilterProperties {

    /**
     * 评论数下限. {@code replyCount &gt;= 该值} 才通过 {@link com.choucj.aiaggregator.content.filter.CommentFilter}.
     * <p>默认 10 (PRD FR3 调研结论). 范围 [0, 1_000_000] —
     * 下限防负数, 上限防误配 {@code Integer.MAX_VALUE} 静默剔除全部推文.
     */
    @Min(value = 0, message = "filter.comment-threshold must be >= 0")
    @Max(value = 1_000_000, message = "filter.comment-threshold must be <= 1000000")
    private int commentThreshold = 10;

    /**
     * AI 创新度评分下限 (1-10 分). {@code innovationScore &gt;= 该值} 才通过
     * {@link com.choucj.aiaggregator.content.filter.InnovationFilter}.
     * <p>默认 6.0 — 仅保留中等及以上创新度的推文. 范围 [0.0, 10.0] —
     * 下限防负数, 上限防误配 {@code 11} 静默剔除全部.
     * <p>注: 评分本身 1-10 由 {@code InnovationFilter#parseScore} 保证, 此处校验阈值配置合理性.
     */
    @DecimalMin(value = "0.0", message = "filter.innovation-threshold must be >= 0.0")
    @DecimalMax(value = "10.0", message = "filter.innovation-threshold must be <= 10.0")
    private double innovationThreshold = 6.0;
}
