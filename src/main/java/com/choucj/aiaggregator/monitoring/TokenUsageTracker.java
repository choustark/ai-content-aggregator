package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Story 2.4: LLM Token 用量追踪器.
 *
 * <p>估算每次改写调用的 prompt + response 字符数 / 4 作为 token 近似值
 * (OpenAI 经验值 {@code 4 chars ≈ 1 token}, 中文略偏低但足够观测), 累计写入
     * Redis {@code cost:daily:{date}} 键 (45 天 TTL).
 *
 * <p><b>设计决策 (YAGNI):</b>
 * <ul>
 *   <li>字符估算 vs 精确 tokenizer — 不引入 {@code tiktoken} / OpenAI tokenizer 依赖,
 *       本系统是观测侧路径 (非计费), 4 char ≈ 1 token 足够</li>
 *   <li>通过 {@code RedisRepository.incrementBy} 使用 Redis 原子递增，避免多实例并发丢失累计值</li>
 *   <li>异常容错 — Redis 写入失败仅 {@code log.warn}, 不中断改写主流程
 *       (Token 追踪是观测侧路径, 非业务关键)</li>
 * </ul>
 *
 * <p>引用源: Story 2.4 (SingleModelRewriter 调用); Story 5.x (成本监控增强时考虑精确计数).
 */
@Slf4j
@Component
public class TokenUsageTracker {

    /** Redis 键 TTL — 覆盖完整自然月和月末延迟汇总窗口，过期自动清理. */
    static final Duration TTL = Duration.ofDays(45);

    /** OpenAI 经验值: 4 个字符约 1 个 token. */
    static final int CHARS_PER_TOKEN = 4;

    private final RedisRepository redisRepository;
    private final CostPricingProperties costPricingProperties;

    /**
     * 兼容旧测试/手工构造路径的构造器.
     *
     * @param redisRepository Redis 访问入口
     */
    public TokenUsageTracker(RedisRepository redisRepository) {
        this(redisRepository, new CostPricingProperties());
    }

    /**
     * 构造 Token 用量追踪器，并注入模型单价用于 Story 5.5 成本估算.
     *
     * @param redisRepository Redis 访问入口
     * @param costPricingProperties 模型单价配置
     */
    @Autowired
    public TokenUsageTracker(RedisRepository redisRepository, CostPricingProperties costPricingProperties) {
        this.redisRepository = redisRepository;
        this.costPricingProperties = costPricingProperties;
    }

    /**
     * 估算 prompt + response 的 token 数, 累加到 Redis {@code cost:daily:{date}} 键.
     *
     * <p>Redis 读 / 写失败 (RetryableException / NonRetryableException) 或旧值非数字
     * ({@code NumberFormatException}) 均仅 {@code log.warn}, 不抛异常 — 观测侧路径不应
     * 中断改写主流程 (W1+W2 异常隔离模式, Story 2.3b review lesson).
     *
     * @param promptText   prompt 文本 (system + user), null 视为 0 字符
     * @param responseText LLM 响应文本, null 视为 0 字符
     * @param date         累计归属日期 (一般为 {@code LocalDate.now()})
     */
    public void track(String promptText, String responseText, LocalDate date) {
        int tokens = estimateTokens(promptText, responseText);
        if (tokens <= 0) {
            return;
        }
        accumulate(RedisKeys.costDaily(date), tokens, "Token 累计");
    }

    /**
     * 按真实模型记录 prompt/response token 与估算成本.
     *
     * <p>先保留旧 {@code cost:daily:{date}} total scalar，再写模型维度键。模型维度或价格缺失失败
     * 只记 warn，不影响主改写流程。
     *
     * @param model 模型名，如 {@code deepseek} / {@code glm}
     * @param promptText prompt 文本
     * @param responseText LLM 响应文本
     * @param date 归属日期
     */
    public void track(String model, String promptText, String responseText, LocalDate date) {
        track(promptText, responseText, date);
        int inputTokens = estimateTokens(promptText, "");
        int outputTokens = estimateTokens("", responseText);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return;
        }
        try {
            if (inputTokens > 0) {
                accumulate(RedisKeys.costDailyModelInput(date, model), inputTokens, "模型输入 Token 累计");
            }
            if (outputTokens > 0) {
                accumulate(RedisKeys.costDailyModelOutput(date, model), outputTokens, "模型输出 Token 累计");
            }
            long estimatedMicroCents = estimateCostMicroCents(model, inputTokens, outputTokens);
            if (estimatedMicroCents > 0) {
                accumulate(RedisKeys.costDailyModelEstimatedMicroCents(date, model),
                        estimatedMicroCents, "模型估算成本累计");
            }
        } catch (IllegalArgumentException e) {
            log.warn("模型 Token 追踪配置缺失或模型名非法, 跳过模型维度: model={}, errorType={}",
                    model, e.getClass().getSimpleName());
        }
    }

    /**
     * 累计 RAG prompt 输入增量 token，因为 Story 5.3 需要观测 RAG 注入成本但不能重复计入总成本.
     *
     * @param extraPromptTokens RAG prompt 相对旧 prompt 的输入 token 增量，非正数时 no-op
     * @param date 累计归属日期
     */
    public void trackRagPromptDelta(int extraPromptTokens, LocalDate date) {
        if (extraPromptTokens <= 0) {
            return;
        }
        accumulate(RedisKeys.costDailyRagExtra(date), extraPromptTokens, "RAG prompt 增量累计");
    }

    private void accumulate(String key, int tokens, String label) {
        accumulate(key, (long) tokens, label);
    }

    private void accumulate(String key, long tokens, String label) {
        try {
            long total = redisRepository.incrementBy(key, tokens, TTL);
            log.debug("{}: key={}, +{} tokens, total={}", label, key, tokens, total);
        } catch (RuntimeException e) {
            // 含 RedisRepository 抛 Retryable / NonRetryable / 其他 RuntimeException
            log.warn("{} Redis 原子递增失败, 跳过: key={}, errorType={}",
                    label, key, e.getClass().getSimpleName());
        }
    }

    /**
     * 静态估算 token 数: {@code (promptChars + responseChars) / 4}.
     *
     * <p>OpenAI 经验值 (4 char ≈ 1 token), 中文略偏低 (中文字符多映射为 1-2 token),
     * 但本系统是观测侧 (非计费), 估算足够. 精确计数留待 Story 5.x 引入 tokenizer.
     *
     * @param promptText   prompt 文本, null 视为 0 字符
     * @param responseText LLM 响应文本, null 视为 0 字符
     * @return 估算 token 数 (≥ 0)
     */
    public static int estimateTokens(String promptText, String responseText) {
        int promptChars = promptText == null ? 0 : promptText.length();
        int responseChars = responseText == null ? 0 : responseText.length();
        return (promptChars + responseChars) / CHARS_PER_TOKEN;
    }

    private long estimateCostMicroCents(String model, int inputTokens, int outputTokens) {
        CostPricingProperties.ModelPricing pricing = costPricingProperties.requireModel(model);
        return inputTokens * pricing.getInputCentsPerMillion()
                + outputTokens * pricing.getOutputCentsPerMillion();
    }

}
