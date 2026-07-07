package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Story 3.4: 混合发布模式决策器.
 *
 * <p>实现 {@link ContentPublisher}, 由 Spring 自动收集到 {@code List<ContentPublisher>} 注入到
 * TwitterProcessor (与 MarkdownArchiver 同伦). 按 {@link Article#getInnovationScore()} 阈值分流:
 *
 * <ul>
 *   <li><b>实时路径 (innovationScore {@code >=} {@code realtime-threshold}, 默认 8):</b>
 *       立即调用 {@link WeChatPublisher#publish(Article)} 走 Story 3.3 实时创建草稿路径.</li>
 *   <li><b>批量路径 (innovationScore {@code <} threshold):</b>
 *       序列化 Article JSON 并 rPush 到 Redis List {@code publish:pending:{yyyy-MM-dd}},
 *       由 {@link BatchPublishingScheduler} 在每晚 20:00 cron 批量消费.</li>
 * </ul>
 *
 * <p><b>软失败降级 (决策 4, 关键设计):</b> 入队 Redis 失败 (Retryable / NonRetryable / RuntimeException) 时,
 * log.warn 后降级走实时路径调 {@code weChatPublisher.publish(article)} 兜底. 不让单条创新分低的 Article
 * 因 Redis 抖动被丢弃 — Article 已花费 LLM token 改写, 丢失成本高.
 *
 * <p><b>不依赖顺序:</b> 与 MarkdownArchiver 互不读取对方结果, Spring Bean 注册顺序任意.
 *
 * <p><b>lessons-learned 模式引用:</b>
 * <ul>
 *   <li>W1+W2 — Redis 操作周围 catch Retryable | NonRetryable | RuntimeException (兜底)</li>
 *   <li>W11 — log.info 含 articleId + innovationScore + decision (+ queueDate + queueSize if batch)</li>
 *   <li>N4 — 异常 message 不含 Article.content / 序列化 JSON 正文, 只含 articleId + 截断 cause</li>
 *   <li>N2+R3-1 — 标题/cause 截断复用 {@link SingleModelRewriter#truncateForLog} (修复版 ≤ max codepoint)</li>
 *   <li>D3 — Article.innovationScore 是 primitive int (Story 2.4 已闭环), 无空安全风险</li>
 *   <li>跨包可见性 — 跨包调用 {@code SingleModelRewriter.truncateForLog/getRootMessage} + RedisRepository</li>
 *   <li>B2 — 序列化失败 message 用拼接式 (articleId+date), 不用 String.format(JSON 正文)</li>
 * </ul>
 *
 * <p>引用源: Story 3.4 (本 story) / Story 3.3 (WeChatPublisher 实时路径) /
 * Story 1.5a (RedisRepository List 操作扩展) / Story 2.4 (Article.innovationScore primitive).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class PublishingModeDecider implements ContentPublisher {

    /** 日志/异常中 articleId/title 截断长度 (codepoint, R3-1 ≤ max). */
    private static final int LOG_TITLE_MAX_CODEPOINTS = 50;

    /** 日志/异常中 cause message 截断长度 (codepoint, R3-1 ≤ max, N4 防泄漏). */
    private static final int LOG_CAUSE_MAX_CODEPOINTS = 200;

    private final WeChatPublisher weChatPublisher;
    private final RedisRepository redisRepository;
    private final PublishingProperties publishingProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(Article article) {
        // D3: Article.innovationScore 是 primitive int, 无空安全风险. Article null 入口校验防 NPE.
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article 为 null");
        }
        String articleId = requireArticleId(article);
        int score = article.getInnovationScore();
        int threshold = publishingProperties.getRealtimeThreshold();

        if (score >= threshold || !publishingProperties.isBatchEnabled()) {
            // AC-2 实时路径
            log.info("微信发布决策: articleId={}, innovationScore={}, threshold={}, decision=realtime, reason={}",
                    articleId, score, threshold, score >= threshold ? "scoreAboveThreshold" : "batchDisabled");
            // WeChatPublisher 内部异常 (Retryable/NonRetryable) 透传到 TwitterProcessor per-article catch (L2 隔离)
            weChatPublisher.publish(article);
            return;
        }

        // AC-3 批量路径: 序列化 + 入队 + 设置 TTL
        LocalDate queueDate = article.getCreatedAt() != null
                ? article.getCreatedAt().toLocalDate()
                : LocalDate.now();
        String queueKey = RedisKeys.publishPending(queueDate);
        String json;
        try {
            json = objectMapper.writeValueAsString(article);
        } catch (JsonProcessingException e) {
            // 序列化失败 — NonRetryable (REDIS_DATA_ERROR), 软失败降级走实时路径
            // N4: 异常 message 不含 article 正文 (json 未生成), 只含 articleId + cause 截断
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.warn("Article 序列化失败, 降级走实时路径: articleId={}, queueDate={}, rootMessage={}",
                    articleId, queueDate, truncatedCause, e);
            weChatPublisher.publish(article);
            return;
        }

        try {
            redisRepository.rPush(queueKey, json);
        } catch (RetryableException | NonRetryableException e) {
            // 软失败: Redis 故障, 降级走实时路径 (AC-3 / 决策 4)
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.warn("Redis 入队失败, 降级走实时路径: articleId={}, queueDate={}, rootMessage={}",
                    articleId, queueDate, truncatedCause, e);
            weChatPublisher.publish(article);
        } catch (RuntimeException e) {
            // W1+W2 兜底: Spring 框架异常 / 其他未知 RuntimeException 也走软失败降级
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.warn("Redis 入队未预期异常, 降级走实时路径: articleId={}, queueDate={}, rootMessage={}",
                    articleId, queueDate, truncatedCause, e);
            weChatPublisher.publish(article);
            return;
        }

        long queueSize = -1L;
        try {
            // 每次入队都刷新 TTL — 防 7 天 TTL 在高频入队下过期导致丢消息 (rPush 不会续 TTL, 需显式 expire)
            // 注: rPush 已成功后 Article 已进入队列, 后续观测/TTL 失败只告警, 不再 fallback 实时以免重复草稿.
            redisRepository.expire(queueKey, Duration.ofDays(publishingProperties.getQueueTtlDays()));
            queueSize = redisRepository.listLength(queueKey);
        } catch (RuntimeException e) {
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.warn("Redis 入队后维护操作失败, 保持批量路径不降级: articleId={}, queueDate={}, rootMessage={}",
                    articleId, queueDate, truncatedCause, e);
        }

        // W11: 含 articleId + innovationScore + decision + queueDate + queueSize (-1 表示观测失败)
        log.info("微信发布决策: articleId={}, innovationScore={}, threshold={}, decision=batch, "
                        + "queueDate={}, queueSize={}",
                articleId, score, threshold, queueDate, queueSize);
    }

    /** Article.id 是批量队列可追踪性的根 ID, 缺失时 fail-fast. */
    private static String requireArticleId(Article article) {
        String articleId = article.getId();
        if (articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "Article.id 不能为空");
        }
        return articleId;
    }
}
