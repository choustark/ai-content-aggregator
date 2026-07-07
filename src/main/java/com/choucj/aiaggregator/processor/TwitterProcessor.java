package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.filter.ContentFilter;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.source.twitter.TwitterSource;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Twitter 处理流水线编排器 (Story 2.6 — Epic 2 收尾).
 *
 * <p>编排端到端 Pipeline, 将 Epic 2 各子模块串联为完整自动化流程:
 * <pre>
 *   ContentScheduler (cron 每小时)
 *     ↓ poll("twitter:run") → processTask(taskId) → startsWith("twitter:")
 *     ↓ twitterProcessor.process()
 *
 *   TwitterProcessor.process()
 *     ├─ Stage 1: twitterSource.fetch()                       (故障隔离 L1 — catch Exception 兜底)
 *     ├─ Stage 2: contentFilters[].filter() (责任链)          (故障隔离 L3 — 阶段级降级)
 *     └─ Stage 3-5: per-article rewrite + publish             (故障隔离 L2 — 单条失败不阻塞)
 *         for (tweet : filtered) {
 *           try { rewrite(tweet) → publish(article) }
 *           catch (Exception) { failure++; log.error }        (AC-3 per-article 隔离)
 *         }
 *     ↓ summary log.info("Pipeline 完成: 发现=..., 失败=...")
 * </pre>
 *
 * <p><b>故障隔离三层防御 (forward-looking 闭环清单):</b>
 * <ul>
 *   <li><b>L1 fetch 兜底</b> — {@link TwitterSource#fetch()} 内部仅 catch
 *       {@code RetryableException | NonRetryableException} (Story 2.2a L93),
 *       其他 {@link RuntimeException} (NPE / {@link IllegalStateException}) 会穿透.
 *       Pipeline 在 fetch 调用周围 {@code catch(Exception)} 兜底 (Story 2.2a N2 闭环),
 *       视为本批次发现数=0, summary 正常输出, 不抛到 {@code ContentScheduler}</li>
 *   <li><b>L2 per-article 隔离</b> — 单条 rewrite / publish 抛出任意异常 →
 *       {@code failure++} + {@code log.error} + continue, 不影响其他文章
 *       (复用 Story 2.3b W2 + Story 2.5 forward-looking 模式).
 *       {@code properties.faultIsolationEnabled=false} 时 (调试用) 不吞异常, 直接透传到
 *       {@code ContentScheduler} 顶层 catch (Patch-2 修复 — 配置承诺与运行时一致)</li>
 *   <li><b>L3 per-stage 降级</b> — 责任链单级 filter 抛异常 → 透传上一阶段 input 到下一级,
 *       继续处理 (复用 Story 2.3b InnovationFilter 降级语义)</li>
 * </ul>
 *
 * <p><b>TaskQueue 集成 (Patch-1 修复):</b>
 * 不在 per-article 级别 push/complete — ContentScheduler 已在 {@code twitter:run} 级别
 * (外层任务) 做 push/poll/complete 跟踪, 重启时由 {@code TaskRecoveryRunner} 恢复.
 * 早期实现曾尝试 {@code push("twitter:tweet:{id}")} 跟踪单条, 但这些 taskId 从未经过
 * {@code poll()}, 不会进入 {@code task:processing} 集合, {@code complete()} 对它们是 no-op,
 * 结果残留在 {@code task:queue} List 中, 后续被 {@code ContentScheduler} 重新 poll 出来
 * 触发整批重跑 (违反 AC-2 断点恢复意图).
 *
 * <p><b>Spring 责任链顺序保证:</b>
 * {@code List<ContentFilter<Tweet>>} 注入顺序由 Spring Bean 注册时机决定 (不稳定),
 * 必须依赖 Story 2.6 Task 3 加的 {@code @Order(100)} ({@code CommentFilter}) +
 * {@code @Order(200)} ({@code InnovationFilter}) 保证顺序.
 *
 * <p><b>计数策略:</b>
 * {@code commentPassed}/{@code innovationPassed} 用
 * {@code filter.getClass().getSimpleName().contains("Comment"|"Innovation")} 匹配.
 * 比 {@code instanceof} 宽容 (允许子类), 比 index 健壮 (不依赖 @Order 排序位置).
 *
 * <p><b>不重复实现已闭环的能力:</b>
 * <ul>
 *   <li>LLM 重试 (3 次退避) — {@link SingleModelRewriter#callWithRetry} 内部</li>
 *   <li>LLM 超时 (60s) — {@code LlmConfig.ChatModel} Bean (Story 2.3a)</li>
 *   <li>Token 跟踪 — {@link SingleModelRewriter} + {@code TokenUsageTracker}</li>
 *   <li>Tweet 缓存 — {@link TwitterSource#enrichTweet} + {@code RedisRepository}</li>
 *   <li>幂等查重 (articleId) — {@code MarkdownArchiver.isAlreadyArchived}</li>
 *   <li>{@code Tweet.innovationScore} 空安全 — {@link SingleModelRewriter#convertInnovationScore}</li>
 *   <li>状态追踪 (Story 3.5) — {@link ArticleStatusService} 在 Stage 3 入口写 PENDING, publish 前写 PROCESSING</li>
 * </ul>
 *
 * <p><b>日志规范 (强制 W11/N4/Patch-5/N2):</b>
 * <ul>
 *   <li>W11 — 所有 {@code log.info}/{@code log.error} 含 tweetId/taskId/阶段名+计数</li>
 *   <li>N4 — 异常 message 不含 {@code tweet.content}/{@code article.content}/LLM 响应正文,
 *       只含 tweetId + cause 截断 (≤200 code point)</li>
 *   <li>Patch-5 — {@code log.error} 最后参数传 exception, SLF4J 自动展开堆栈</li>
 *   <li>N2 — 标题/message 按代码点截断 (复用 {@link SingleModelRewriter#truncateForLog})</li>
 * </ul>
 *
 * <p>引用源: Story 2.6 (Pipeline 实现) / Story 1.6 (TaskQueue) /
 * Story 2.2a N2 (fetch 兜底) / Story 2.3b W2 (per-article 隔离) /
 * Story 2.4 (SingleModelRewriter 工具复用) / Story 2.5 (ContentPublisher 模式).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwitterProcessor {

    private final TwitterSource twitterSource;
    private final List<ContentFilter<Tweet>> contentFilters;
    private final ContentRewriter contentRewriter;
    private final List<ContentPublisher> contentPublishers;
    private final ProcessorProperties properties;
    private final ArticleStatusService articleStatusService;

    private static final Pattern TWEET_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * 执行一轮完整的 Twitter 处理流水线.
     *
     * <p>调度入口: {@code ContentScheduler.processTask("twitter:...")} 路由到本方法.
     * 单次执行 fetch → filter chain → per-article rewrite + publish 全链路,
     * 不抛异常到调用方 (所有故障由三层防御内部消化).
     *
     * <p>Summary 日志含 6 字段: 发现数 / 评论筛选通过数 / 创新筛选通过数 /
     * 改写成功数 / 归档成功数 / 失败数.
     */
    public void process() {
        log.info("Twitter Pipeline 启动");
        int discovered = 0;
        int commentPassed = 0;
        int innovationPassed = 0;
        int rewriteSuccess = 0;
        int archiveSuccess = 0;
        int failure = 0;

        // Stage 1: fetch (AC-1 + AC-4 catch Exception 兜底, Story 2.2a N2 闭环)
        List<Tweet> tweets;
        try {
            tweets = twitterSource.fetch();
        } catch (Exception e) {
            // L1 兜底: TwitterSource.fetch L93 仅 catch Retryable|NonRetryable,
            // 其他 RuntimeException (NPE/IllegalStateException) 会穿透 — Pipeline 必须兜底.
            // Patch-5: e 末参数自动展开堆栈; format 无 error={} 占位符 (N4: 不输出 e.getMessage() 含正文风险)
            log.error("TwitterSource.fetch 失败, 本批次跳过 (故障隔离 L1 兜底)", e);
            tweets = List.of();
        }
        discovered = tweets.size();

        // Stage 2: filter chain (ContentFilter 责任链, @Order 保证顺序)
        List<Tweet> filtered = new ArrayList<>(tweets);
        for (ContentFilter<Tweet> filter : contentFilters) {
            int before = filtered.size();
            boolean degraded = false;
            try {
                List<Tweet> stageResult = filter.filter(filtered);
                if (stageResult == null) {
                    degraded = true;
                    log.error("筛选阶段返回 null, 透传上一阶段结果 (故障隔离 L3 阶段降级): filter={}",
                            filter.getClass().getSimpleName(),
                            new IllegalStateException("ContentFilter 返回 null"));
                } else {
                    filtered = stageResult;
                }
            } catch (Exception e) {
                degraded = true;
                // L3 阶段级降级: 该级抛异常 → 透传上一阶段 input, 继续下一级
                log.error("筛选阶段失败, 透传上一阶段结果 (故障隔离 L3 阶段降级): filter={}",
                        filter.getClass().getSimpleName(), e);
            }
            int after = filtered.size();
            String filterName = filter.getClass().getSimpleName();
            if (filterName.contains("Comment")) {
                commentPassed = degraded ? 0 : after;
                logFilterStage(filterName, degraded, before, after);
            } else if (filterName.contains("Innovation")) {
                innovationPassed = degraded ? 0 : after;
                logFilterStage(filterName, degraded, before, after);
            } else {
                if (degraded) {
                    log.info("筛选阶段降级: filter={}, 输入={}, 透传={}", filterName, before, after);
                } else {
                    log.info("筛选阶段完成: filter={}, 输入={}, 输出={}", filterName, before, after);
                }
            }
        }

        // Stage 3+4+5: per-article rewrite + publish (AC-3)
        // Patch-1 修复: 不在 per-article 级别 push/complete TaskQueue — ContentScheduler 已在
        // twitter:run 级别跟踪外层任务; per-article push 会让 twitter:tweet:* 残留 task:queue
        // 触发后续 poll 重新路由到 process() 整批重跑.
        // Patch-2 修复: faultIsolationEnabled=false 时 per-article 异常透传到 ContentScheduler
        // 顶层 (调试用, 由调度器按 Retryable/NonRetryable 分类处理).
        for (Tweet tweet : filtered) {
            try {
                // Story 3.5 AC-1/P5: rewrite 前写 PENDING, 但必须先校验 tweetId,
                // 避免生成 tw-null / malformed orphan status.
                articleStatusService.markPending(buildDeterministicArticleId(tweet));
                Article article = contentRewriter.rewrite(tweet);
                rewriteSuccess++;
                // Story 3.4: 多 ContentPublisher 遍历 (MarkdownArchiver + PublishingModeDecider + ...)
                // 单 publisher 抛异常 → L2 per-article 隔离 catch 捕获, 后续 publisher 本条不再调
                // (与单条失败语义一致, 不调"半成功" — 任何 publisher 失败视本条失败)
                for (ContentPublisher publisher : contentPublishers) {
                    publisher.publish(article);
                }
                archiveSuccess++;
            } catch (Exception e) {
                failure++;
                // L2 per-article 隔离: 该条失败 → log.error + continue, 不影响其他文章
                // N4: cause 截断不含正文 (truncateForLog 复用 SingleModelRewriter R3-1 修复版)
                // Patch-5: e 末参数自动展开堆栈
                log.error("文章处理失败, 跳过 (故障隔离 L2 per-article): tweetId={}, cause={}",
                        tweet.getId(),
                        SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200),
                        e);
                if (!properties.isFaultIsolationEnabled()) {
                    // 调试模式: 异常透传到 process() 顶层, 由 ContentScheduler 接管分类处理
                    throw e;
                }
            }
        }

        // AC-5 summary (6 字段)
        log.info("Pipeline 完成: 发现={}, 评论筛选通过={}, 创新筛选通过={}, 改写成功={}, 归档成功={}, 失败={}",
                discovered, commentPassed, innovationPassed, rewriteSuccess, archiveSuccess, failure);
    }

    private void logFilterStage(String filterName, boolean degraded, int before, int after) {
        if (filterName.contains("Comment")) {
            if (degraded) {
                log.info("评论筛选降级: 输入={}, 透传={}", before, after);
            } else {
                log.info("评论筛选完成: 输入={}, 通过={}", before, after);
            }
            return;
        }
        if (degraded) {
            log.info("创新筛选降级: 输入={}, 透传={}", before, after);
        } else {
            log.info("创新筛选完成: 输入={}, 通过={}", before, after);
        }
    }

    private static String buildDeterministicArticleId(Tweet tweet) {
        if (tweet == null || tweet.getId() == null || tweet.getId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 tweetId: " + (tweet == null ? null : tweet.getId()));
        }
        if (!TWEET_ID_PATTERN.matcher(tweet.getId()).matches()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 tweetId (必须匹配 [A-Za-z0-9_-]+): " + tweet.getId());
        }
        return "tw-" + tweet.getId();
    }
}
