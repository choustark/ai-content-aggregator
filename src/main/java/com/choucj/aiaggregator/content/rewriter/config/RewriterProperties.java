package com.choucj.aiaggregator.content.rewriter.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 内容改写器配置 (Story 2.4).
 *
 * <p>对应 {@link com.choucj.aiaggregator.content.rewriter.SingleModelRewriter} 的运行参数:
 * <ul>
 *   <li>{@link #maxRetries} — LLM 调用失败重试次数, 总尝试 = {@code 1 + maxRetries}</li>
 *   <li>{@link #retryBackoffMs} — 重试退避初始毫秒, 实际等待 = {@code backoffMs * attemptIndex}</li>
 *   <li>{@link #contentMaxCodePoints} — 源推文截断上限 (code points, 防 prompt 过长)</li>
 *   <li>{@link #multiModelDeadlineMs} — 多模型投票单次聚合等待上限</li>
 * </ul>
 *
 * <p>配置示例 ({@code application.yml}):
 * <pre>{@code
 * rewriter:
 *   max-retries: 3
 *   retry-backoff-ms: 1000
 *   content-max-code-points: 2000
 *   multi-model-deadline-ms: 65000
 * }</pre>
 *
 * <p>校验 (W7/W8/N1, Story 2.3b review lessons 复用):
 * <ul>
 *   <li>W7/W8: 三个字段均加 {@link Min} / {@link Max} 校验, 防误配导致系统行为异常</li>
 *   <li>N1: {@code retryBackoffMs} 上限 60s, 防误配大值导致重试间隔过长</li>
 *   <li>W12: {@link com.choucj.aiaggregator.content.rewriter.config.RewriterPropertiesTest}
 *       加 4 个负向校验用例验证注解生效</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "rewriter")
@Validated
@Data
public class RewriterProperties {

    /**
     * LLM 调用失败重试次数. 总尝试次数 = {@code 1 + maxRetries} (默认 4 次).
     * <p>范围 [0, 10] — 下限防不重试直接失败, 上限防卡死调度器 (单实例 60s timeout * 11 = 11min).
     */
    @Min(value = 0, message = "rewriter.max-retries must be >= 0")
    @Max(value = 10, message = "rewriter.max-retries must be <= 10")
    private int maxRetries = 3;

    /**
     * 重试退避毫秒 (初始值). 实际等待 = {@code retryBackoffMs * attemptIndex} (1s, 2s, 3s 递增).
     * <p>范围 [100, 60000] — 下限防抖动风暴, 上限防卡死调度.
     */
    @Min(value = 100, message = "rewriter.retry-backoff-ms must be >= 100")
    @Max(value = 60_000, message = "rewriter.retry-backoff-ms must be <= 60000")
    private long retryBackoffMs = 1000L;

    /**
     * 源推文截断上限 (code points). 防止超长推文撑爆 LLM prompt.
     * <p>范围 [100, 10000] — 下限防过短丢内容, 上限防过长成本失控.
     * <p>注: 按 code point 而非 char 截断 (N2 模式, 防 UTF-16 代理对 emoji 切断).
     */
    @Min(value = 100, message = "rewriter.content-max-code-points must be >= 100")
    @Max(value = 10_000, message = "rewriter.content-max-code-points must be <= 10000")
    private int contentMaxCodePoints = 2000;

    /**
     * 多模型投票单次聚合等待上限. 默认略高于 LlmConfig 中模型 HTTP 60s timeout,
     * 用作 request-level 防线, 防 future 永久挂起.
     */
    @Min(value = 1000, message = "rewriter.multi-model-deadline-ms must be >= 1000")
    @Max(value = 300_000, message = "rewriter.multi-model-deadline-ms must be <= 300000")
    private long multiModelDeadlineMs = 65_000L;
}
