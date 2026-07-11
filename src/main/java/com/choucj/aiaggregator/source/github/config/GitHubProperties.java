package com.choucj.aiaggregator.source.github.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * GitHub 数据源配置.
 *
 * <p>Story 4.1 引入, 配合 {@link GitHubConfig} 注册 {@code githubRestClient} Bean.
 * 实际 {@code token} 在 {@code api-keys.yml} 维护 (Story 1.2 配置分离原则), 本 properties
 * 通过 {@code spring.config.import} 自动绑定 {@code github.*} 键.
 *
 * <p><b>spike-4.1 决策对齐:</b>
 * <ul>
 *   <li>{@code token} 默认空 — 未配置时降级为未认证调用 (Search 10/min vs 认证 30/min,
 *       spike-4.1 §2.3 实测). 不阻塞启动.</li>
 *   <li>{@code trending.query-template} 默认 {@code created:>{date}+language:{language}}
 *       — 用 Search API + {@code sort=stars} 替代官方无 /trending 端点 (spike-4.1 §2.1).</li>
 *   <li>{@code readme.max-size-kb} 默认 100 — spike-4.1 §2.6 C1 token 估算: 100KB ≈ 60K token,
 *       占 128K 上下文 47%, 留 53% 给 system prompt + LLM 输出.</li>
 * </ul>
 *
 * <p><b>占位符渲染 (B2 模式):</b> {@code queryTemplate} 用 {@link String#replace(CharSequence, CharSequence)}
 * 替换 {@code {date}} / {@code {language}} 占位符 — 禁用 {@link String#format(String, Object...)}
 * (query 含 {@code :} {@code +} 等字符会触发 IllegalFormatException).
 */
@ConfigurationProperties(prefix = "github")
@Validated
@Data
public class GitHubProperties {

    /** GitHubClient 内部开关 (与 features.github.enabled 双层保险). */
    private boolean enabled = true;

    /**
     * GitHub Fine-grained PAT (公开仓库只读, 无 scope 需要, spike-4.1 §2.2).
     * 未配置 (空字符串) 时降级为未认证调用, 不阻塞启动. 实际值在 api-keys.yml 维护.
     */
    private String token = "";

    /** HTTP 调用超时 (秒) — connect + read 均应用. 默认 30s (与 RSSHubClient 一致). */
    @Min(value = 1, message = "github.timeout-seconds must be positive")
    @Max(value = 300, message = "github.timeout-seconds must be <= 300")
    private int timeoutSeconds = 30;

    /** Trending 抓取配置 (Search API 替代 /trending 端点, spike-4.1 §2.1). */
    @Valid
    @NotNull(message = "github.trending must not be null")
    private Trending trending = new Trending();

    /** README 抓取配置 (Story 4.2 实施, 本 story 仅声明配置占位). */
    @Valid
    @NotNull(message = "github.readme must not be null")
    private Readme readme = new Readme();

    /**
     * 价值分析配置 (Story 4.3 引入).
     *
     * <p>{@code GitHubValueAnalyzer} 按 {@code scoreThreshold} 筛选高价值仓库,
     * LLM 失败 / 全批降级时按 {@code starFallbackThreshold} 启用 Star 数降级 (AC-4).
     * {@code readmeMaxCodePoints} 限制送入 LLM prompt 的 README 长度, 防撑爆上下文.
     */
    @Valid
    @NotNull(message = "github.value-analyzer must not be null")
    private ValueAnalyzer valueAnalyzer = new ValueAnalyzer();

    /** Trending 抓取参数. */
    @Data
    public static class Trending {

        /**
         * 查询模板 — 占位符 {@code {date}} (lookback-days 天前 ISO 日期) / {@code {language}}.
         * 用 {@link String#replace(CharSequence, CharSequence)} 渲染 (B2 教训库 §1.5).
         */
        @NotBlank(message = "github.trending.query-template must not be blank")
        private String queryTemplate = "created:>{date}+language:{language}";

        /** 回溯天数 — {@code {date}} = 今天 - lookbackDays. 默认 7 (近一周). */
        @Min(value = 1, message = "github.trending.lookback-days must be >= 1")
        @Max(value = 90, message = "github.trending.lookback-days must be <= 90")
        private int lookbackDays = 7;

        /** 返回前 N 条 — {@code per_page} 参数. GitHub 上限 100. */
        @Min(value = 1, message = "github.trending.top-n must be >= 1")
        @Max(value = 100, message = "github.trending.top-n must be <= 100")
        private int topN = 10;

        /** 主语言 — {@code {language}} 占位符替换值. 默认 java. */
        @NotBlank(message = "github.trending.language must not be blank")
        private String language = "java";
    }

    /** README 截断参数 (Story 4.2 实施). */
    @Data
    public static class Readme {

        /** README 最大字符数 (KB) — spike-4.1 §2.6 决策 100KB (≈60K token / 128K 上下文 47%). */
        @Min(value = 1, message = "github.readme.max-size-kb must be >= 1")
        @Max(value = 1024, message = "github.readme.max-size-kb must be <= 1024")
        private int maxSizeKb = 100;
    }

    /**
     * 价值分析参数 (Story 4.3 实施).
     *
     * <p>三档阈值: {@code scoreThreshold} (LLM 评分门槛) / {@code starFallbackThreshold}
     * (LLM 失败时 Star 数降级门槛) / {@code readmeMaxCodePoints} (送入 prompt 的 README 长度上限).
     */
    @Data
    public static class ValueAnalyzer {

        /**
         * AI 价值评分门槛 (1.0-10.0) — LLM 评分 {@code >= scoreThreshold} 才保留.
         * 默认 7.0 (epics.md Story 4.3 AC-1 阈值). 严格 {@code >=} 比较.
         */
        @DecimalMin(value = "0.0", message = "github.value-analyzer.score-threshold must be >= 0.0")
        @DecimalMax(value = "10.0", message = "github.value-analyzer.score-threshold must be <= 10.0")
        private double scoreThreshold = 7.0;

        /**
         * Star 数降级门槛 — AC-4 LLM 全批失败 / 异常时, 仅保留 {@code stars >= starFallbackThreshold}
         * 的仓库作为降级输出. 默认 100 (spike-4.1 调研结论: 高质量 Java 仓库分水岭).
         * 范围 [0, 1000000].
         */
        @Min(value = 0, message = "github.value-analyzer.star-fallback-threshold must be >= 0")
        @Max(value = 1_000_000, message = "github.value-analyzer.star-fallback-threshold must be <= 1000000")
        private int starFallbackThreshold = 100;

        /**
         * 送入 LLM prompt 的 README 最大 code points — spike-4.1 §2.6 C1 token 估算:
         * 8000 code points ≈ 12K token / DeepSeek 64K 上下文的 19%, 留 81% 给 system prompt + 输出.
         * 范围 [1000, 50000].
         */
        @Min(value = 1000, message = "github.value-analyzer.readme-max-code-points must be >= 1000")
        @Max(value = 50000, message = "github.value-analyzer.readme-max-code-points must be <= 50000")
        private int readmeMaxCodePoints = 8000;
    }
}
