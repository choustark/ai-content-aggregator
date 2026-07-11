package com.choucj.aiaggregator.source.github.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub 仓库领域模型.
 *
 * <p>引用源:
 * <ul>
 *   <li>Story 4.1 — {@code GitHubClient} 返回(基础字段: id / fullName / name / description /
 *       language / stars / forks / readmeUrl / url)</li>
 *   <li>Story 4.2 — 补全 {@code readmeContent}(通过 README API 拉取)</li>
 *   <li>Story 4.3 — 价值分析({@code valueScore} / {@code valueSummary} 字段扩展, {@code stars} 是降级筛选依据)</li>
 *   <li>Story 4.4 — Pipeline 流转</li>
 * </ul>
 *
 * <p><b>D3 警示 (Story 4.3):</b> {@code valueScore} / {@code valueSummary} 是 nullable 字段,
 * 调用方 (Story 4.4 GitHubProcessor) 使用前必须 null-check, 不可直接拆箱为 primitive 或直接拼接字符串.
 * {@code null} 语义: 未评分 / LLM 失败 / 全批降级 (AC-4 Star 数降级) 三种场景共用 null 标识.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepo {

    /** 仓库 ID(GitHub API 数字 ID 的字符串形式). */
    private String id;

    /** {@code owner/repo} 格式全名. */
    private String fullName;

    /** 仓库名(不含 owner 前缀). */
    private String name;

    /** 仓库描述. */
    private String description;

    /** 主语言(如 {@code Java} / {@code Python}). */
    private String language;

    /** Star 数(Story 4.3 降级筛选依据). */
    private int stars;

    /** Fork 数. */
    private int forks;

    /** README API URL(Story 4.2 通过此 URL 拉取 README). */
    private String readmeUrl;

    /** README 文本(Story 4.2 补全). */
    private String readmeContent;

    /** 仓库 HTML URL. */
    private String url;

    /**
     * AI 价值评分 (1.0-10.0, Story 4.3 写入).
     *
     * <p>未评分 / LLM 失败 / AC-4 Star 数降级时为 {@code null}.
     * <b>D3 警示:</b> 调用方 (Story 4.4 GitHubProcessor) null-check 后再赋值给 primitive, 不可直接拆箱.
     */
    @Builder.Default
    private Double valueScore = null;

    /**
     * AI 价值摘要 (1-2 句中文, ≤80 code point, Story 4.3 写入).
     *
     * <p>未生成 / LLM 失败 / AC-4 Star 数降级时为 {@code null}.
     * <b>D3 警示:</b> 调用方 (Story 4.4 改写 prompt + 微信草稿 content) null-check 后使用, 缺失时降级为 "无摘要".
     */
    @Builder.Default
    private String valueSummary = null;
}
