package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Story 3.5 — 文章状态机服务 (项目首个状态机).
 *
 * <p>封装 {@link RedisRepository} 写入/读取文章状态,服务于 {@link ArticleStatusController} REST 查询
 * 与 TwitterProcessor / WeChatPublisher 在 Pipeline 各阶段的同步写入.
 *
 * <p><b>4 个公开方法 + 1 个入口校验:</b>
 * <ul>
 *   <li>{@link #markPending(String)} — Stage 3 入口 (Tweet 已通过筛选, 即将 rewrite)</li>
 *   <li>{@link #markProcessing(String)} — publish 调用前 (Article 已生成, 即将发到微信)</li>
 *   <li>{@link #markDraftCreated(String)} — 微信草稿创建成功后</li>
 *   <li>{@link #getStatus(String)} — Controller 查询入口</li>
 *   <li>{@link #validateArticleId(String)} — 入口校验 (D3 + B2)</li>
 * </ul>
 *
 * <p><b>写入软失败决策 (Story 3.5 §5.2):</b> {@code markPending/markProcessing/markDraftCreated}
 * 内部 try-catch Retryable/NonRetryable, log.warn 吞掉 — 状态追踪是辅助功能, 不应阻塞核心 Pipeline
 * (参考 ContentScheduler 失败容错哲学).
 *
 * <p><b>查询不软失败:</b> {@link #getStatus} 把 Retryable/NonRetryable 透传给 Controller,
 * 让用户看到 503/400 而非误导性 404 (查询失败和未找到是两件事).
 *
 * <p><b>TTL 决策 (Story 3.5 §5.5):</b> {@link #markPending} 写 30 天 TTL; {@link #markProcessing}
 * 和 {@link #markDraftCreated} 用 KEEPTTL 写入, 保留首次 PENDING 写入的 TTL, 避免无限延长.
 *
 * <p><b>引用源:</b> Story 3.5 创建.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleStatusService {

    /** Story 3.5 §5.5: PENDING 状态 TTL = 30 天, 覆盖出差 + 周末审核场景. */
    private static final Duration PENDING_TTL = Duration.ofDays(30);

    /** B8: Article.id 由 Twitter tweetId 确定性生成, 形如 tw-{tweetId}. */
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("tw-[A-Za-z0-9_-]+");

    private final RedisRepository redisRepository;

    /**
     * Story 3.5 AC-1 — Stage 3 入口写 PENDING.
     *
     * <p>TTL 30 天, 由首次 PENDING 写入设定, 后续 markProcessing/markDraftCreated 不重设.
     */
    public void markPending(String articleId) {
        validateArticleId(articleId);
        writeSoftFail("markPending", articleId, ArticleStatus.PENDING, true);
    }

    /**
     * Story 3.5 AC-2 — publish 调用前写 PROCESSING.
     *
     * <p>不重设 TTL — 用 {@link RedisRepository#setKeepingTtl(String, String)}
     * 保留首次 PENDING 写入的 TTL.
     */
    public void markProcessing(String articleId) {
        validateArticleId(articleId);
        writeSoftFail("markProcessing", articleId, ArticleStatus.PROCESSING, false);
    }

    /**
     * Story 3.5 AC-3 — WeChatPublisher.publish 成功后写 DRAFT_CREATED.
     *
     * <p>不重设 TTL, 保留首次 PENDING 写入的 TTL.
     */
    public void markDraftCreated(String articleId) {
        validateArticleId(articleId);
        writeSoftFail("markDraftCreated", articleId, ArticleStatus.DRAFT_CREATED, false);
    }

    /**
     * Story 3.5 AC-5 / AC-6 — 状态查询.
     *
     * <p>Redis 不存在该 key → 返 {@link Optional#empty()} (Controller 决定 404).
     * Redis 抛异常 → 透传 Retryable/NonRetryable, Controller 不软失败.
     *
     * <p>Redis 返回非法字符串 (非枚举 name) → 抛 NonRetryable(REDIS_DATA_ERROR) fail-fast,
     * 提示数据被外部破坏.
     */
    public Optional<ArticleStatus> getStatus(String articleId) {
        validateArticleId(articleId);
        String key = RedisKeys.articleStatus(articleId);
        String raw;
        long start = System.nanoTime();
        try {
            raw = redisRepository.get(key);
        } catch (RetryableException | NonRetryableException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.warn("状态查询失败 (异常透传给 Controller): articleId={}, operation=getStatus, 耗时={}ms",
                    articleId, elapsedMs, e);
            throw e;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        log.info("状态查询完成: articleId={}, status={}, operation=getStatus, 耗时={}ms",
                articleId, raw == null ? "NOT_FOUND" : raw, elapsedMs);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ArticleStatus.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "状态查询失败(Redis 数据非法): articleId=" + articleId + ", value=" + raw, e);
        }
    }

    /**
     * Story 3.5 AC-7 — 入口校验 (D3 + B2).
     *
     * <p>articleId 必须匹配 {@code tw-[A-Za-z0-9_-]+}, 避免污染 Redis key namespace.
     *
     * @param articleId 待校验文章 ID
     * @throws NonRetryableException 当 articleId 不合规
     */
    void validateArticleId(String articleId) {
        if (articleId == null || articleId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 articleId: " + articleId);
        }
        if (!ARTICLE_ID_PATTERN.matcher(articleId).matches()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 articleId (必须匹配 tw-[A-Za-z0-9_-]+): " + articleId);
        }
    }

    /**
     * 内部: 写入 + 软失败包装.
     *
     * @param operation 操作名 (markPending / markProcessing / markDraftCreated) — 用于日志
     * @param articleId 文章 ID (已校验)
     * @param status    要写入的状态
     * @param withTtl   true 用 set(key, value, ttl) 重载 (markPending); false 用 KEEPTTL (其他)
     */
    private void writeSoftFail(String operation, String articleId, ArticleStatus status, boolean withTtl) {
        String key = RedisKeys.articleStatus(articleId);
        long start = System.nanoTime();
        try {
            String oldStatus = redisRepository.get(key);
            if (withTtl) {
                redisRepository.set(key, status.name(), PENDING_TTL);
            } else {
                redisRepository.setKeepingTtl(key, status.name());
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("状态写入成功: articleId={}, transition={}→{}, operation={}, 耗时={}ms",
                    articleId, oldStatus == null ? "NONE" : oldStatus, status, operation, elapsedMs);
        } catch (RetryableException | NonRetryableException e) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.warn("状态写入失败, 跳过 (不阻塞流水线): articleId={}, operation={}, 耗时={}ms, cause={}",
                    articleId, operation, elapsedMs, e.getMessage());
        }
    }
}
