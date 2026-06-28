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
 *   <li>Story 4.3 — 价值分析(stars 是降级筛选依据)</li>
 *   <li>Story 4.4 — Pipeline 流转</li>
 * </ul>
 */
@Data
@Builder
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
}
