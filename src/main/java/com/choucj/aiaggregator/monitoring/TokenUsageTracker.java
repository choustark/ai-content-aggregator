package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Story 2.4: LLM Token 用量追踪器.
 *
 * <p>估算每次改写调用的 prompt + response 字符数 / 4 作为 token 近似值
 * (OpenAI 经验值 {@code 4 chars ≈ 1 token}, 中文略偏低但足够观测), 累计写入
 * Redis {@code cost:daily:{date}} 键 (7 天 TTL).
 *
 * <p><b>设计决策 (YAGNI):</b>
 * <ul>
 *   <li>字符估算 vs 精确 tokenizer — 不引入 {@code tiktoken} / OpenAI tokenizer 依赖,
 *       本系统是观测侧路径 (非计费), 4 char ≈ 1 token 足够</li>
 *   <li>不扩展 {@code RedisRepository} 加 INCRBY — 用 {@code get → parseLong → add → set} 模式模拟.
 *       单实例部署无并发, 多实例时 (5.x) 再扩展原子 increment</li>
 *   <li>异常容错 — Redis 写入失败仅 {@code log.warn}, 不中断改写主流程
 *       (Token 追踪是观测侧路径, 非业务关键)</li>
 * </ul>
 *
 * <p>引用源: Story 2.4 (SingleModelRewriter 调用); Story 5.x (成本监控增强时考虑精确计数).
 */
@Slf4j
@Component
public class TokenUsageTracker {

    /** Redis 键 TTL — 7 天保留窗口, 过期自动清理. */
    static final Duration TTL = Duration.ofDays(7);

    /** OpenAI 经验值: 4 个字符约 1 个 token. */
    static final int CHARS_PER_TOKEN = 4;

    private final RedisRepository redisRepository;

    public TokenUsageTracker(RedisRepository redisRepository) {
        this.redisRepository = redisRepository;
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
        String key = RedisKeys.costDaily(date);
        try {
            String current = redisRepository.get(key);
            long accumulated = current == null ? 0L : Long.parseLong(current);
            long total = accumulated + tokens;
            redisRepository.set(key, String.valueOf(total), TTL);
            log.debug("Token 累计: key={}, +{} tokens, total={}", key, tokens, total);
        } catch (NumberFormatException e) {
            // 旧值非数字 (历史脏数据), 重置为当前估算值
            log.warn("Token 追踪 Redis 旧值非数字, 重置: key={}, old={}, new={} tokens",
                    key, truncate(e.getMessage()), tokens);
            try {
                redisRepository.set(key, String.valueOf(tokens), TTL);
            } catch (RuntimeException re) {
                log.warn("Token 追踪 Redis 写入失败 (重置阶段), 跳过: key={}, error={}",
                        key, truncate(re.getMessage()));
            }
        } catch (RuntimeException e) {
            // 含 RedisRepository 抛 Retryable / NonRetryable / 其他 RuntimeException
            log.warn("Token 追踪 Redis 写入失败, 跳过: key={}, error={}",
                    key, truncate(e.getMessage()));
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

    private static String truncate(String msg) {
        if (msg == null) return "<null>";
        return msg.length() <= 200 ? msg : msg.substring(0, 200) + "...";
    }
}
