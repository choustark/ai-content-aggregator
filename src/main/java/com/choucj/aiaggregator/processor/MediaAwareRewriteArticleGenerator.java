package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.ArticleDeliveryRetryableException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.status.DeliveryFailureCoordinator;
import com.choucj.aiaggregator.publish.wechat.converter.MarkdownMediaInserter;
import com.choucj.aiaggregator.publish.wechat.media.MediaPreparationResult;
import com.choucj.aiaggregator.publish.wechat.media.WeChatMediaPreparer;
import com.choucj.aiaggregator.source.twitter.media.TweetMediaArchiver;
import com.choucj.aiaggregator.source.twitter.media.TweetPublishabilityGate;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.task.queue.RetryPolicyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Story 9.1 Task 2: REWRITE_WITH_MEDIA 真 gateway 实现 — AI 改写 Markdown + 原帖媒体嵌入.
 *
 * <p>编排顺序固定 (AC 3 / AD-2, 单方法内串行):
 * <ol>
 *   <li>{@code publishedAt} 非 null fail-fast (D3, 镜像 PreserveOriginalArticleGenerator)</li>
 *   <li>{@link ContentRewriter#rewrite} — LLM 只做文字改写 (AC 4: 输入仅 Tweet 本体,
 *       tweet.media 为 provider 原始元数据, 不含 wechatUrl/localPath/sidecar/上传状态;
 *       改写组件不感知任何媒体状态)</li>
 *   <li>推文有媒体时: {@link TweetMediaArchiver#archiveMedia} 媒体下载归档
 *       (缺失 → NonRetryable 配置不一致, AC 9)</li>
 *   <li>{@link TweetPublishabilityGate#evaluate} 推文级 BLOCKED →
 *       {@link TweetPublishabilityBlockedException} 阻断整篇草稿 (AC 8);
 *       推文有媒体时 gate 缺失视为配置不一致 fail-fast</li>
 *   <li>推文有媒体时: {@link WeChatMediaPreparer#prepareMedia} 微信正文图片上传
 *       (BLOCKED/幂等拦截由 preparer 内部承担, Task 3)</li>
 *   <li>推文有媒体时: {@link TweetMediaArchiveWriter#readSidecar} 重读权威媒体状态
 *       (缺失 → Retryable fail-fast; 正文图片 URL 只来自这次重读的 sidecar 快照,
 *       不从 preparer 返回值拼装, AD-12)</li>
 *   <li>{@link MarkdownMediaInserter#insert} 追加 Markdown image syntax
 *       (LLM 正文之后、converter footer 之前)</li>
 *   <li>{@code generationMode=REWRITE_WITH_MEDIA} + 媒体审计 markdown
 *       (复用 PreserveOriginalArticleGenerator.buildMediaAuditMarkdown, 同包可见性)</li>
 * </ol>
 *
 * <p><b>与 PRESERVE gateway 的分工:</b> 渲染器/renderer/HTML passthrough 均不参与 —
 * 正文恒为 Markdown (AD-3), 由 converter 现有 Markdown path 渲染并追加 footer (AC 7)。
 * 无媒体推文仍生成 REWRITE_WITH_MEDIA 纯文字草稿 (AC 9), archiver/preparer/writer 零交互。
 *
 * <p><b>lessons-learned 模式 (镜像 PreserveOriginalArticleGenerator):</b>
 * W1+W2 ({@link #callExternal} 包装)、N4 (异常 message 只含 tweetId + 根因截断)、
 * W11 (完成日志含 tweetId/articleId/mode/媒体计数/嵌入数/降级数/耗时)、
 * requireBean (依赖缺失 fail-fast 含 tweetId + 开关名, AC 9)。
 *
 * <p>引用源: Story 9.1 AC 3/4/8/9 / ARCHITECTURE-SPINE AD-2~AD-4 + AD-11 + AD-12。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class MediaAwareRewriteArticleGenerator implements MediaAwareRewriteGenerationGateway {

    /** 意外异常根因 message 截断上限 (W1+W2, N4)。 */
    private static final int ERROR_MSG_MAX_CODEPOINTS = 200;

    private final ContentRewriter contentRewriter;

    private final Optional<TweetMediaArchiver> tweetMediaArchiver;

    private final Optional<TweetPublishabilityGate> tweetPublishabilityGate;

    private final WeChatMediaPreparer weChatMediaPreparer;

    private final Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter;

    private final MarkdownMediaInserter markdownMediaInserter;
    private final Optional<DeliveryFailureCoordinator> deliveryFailureCoordinator;
    /** Story 10.8: task.retry.* — RETRY_SCHEDULED 证据退避与重试上限唯一来源. */
    private final RetryPolicyProperties retryPolicy;

    /** Story 10.8 起的 Spring 装构造器 (新增 {@link RetryPolicyProperties} 注入). */
    @Autowired
    public MediaAwareRewriteArticleGenerator(ContentRewriter contentRewriter,
                                             Optional<TweetMediaArchiver> tweetMediaArchiver,
                                             Optional<TweetPublishabilityGate> tweetPublishabilityGate,
                                             WeChatMediaPreparer weChatMediaPreparer,
                                             Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter,
                                             MarkdownMediaInserter markdownMediaInserter,
                                             Optional<DeliveryFailureCoordinator> deliveryFailureCoordinator,
                                             RetryPolicyProperties retryPolicy) {
        this.contentRewriter = contentRewriter;
        this.tweetMediaArchiver = tweetMediaArchiver;
        this.tweetPublishabilityGate = tweetPublishabilityGate;
        this.weChatMediaPreparer = weChatMediaPreparer;
        this.tweetMediaArchiveWriter = tweetMediaArchiveWriter;
        this.markdownMediaInserter = markdownMediaInserter;
        this.deliveryFailureCoordinator = deliveryFailureCoordinator;
        this.retryPolicy = retryPolicy;
    }

    /** Story 10.8 前签名 (兼容): coordinator 缺失 + 默认 task.retry.*. */
    public MediaAwareRewriteArticleGenerator(ContentRewriter contentRewriter,
                                             Optional<TweetMediaArchiver> tweetMediaArchiver,
                                             Optional<TweetPublishabilityGate> tweetPublishabilityGate,
                                             WeChatMediaPreparer weChatMediaPreparer,
                                             Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter,
                                             MarkdownMediaInserter markdownMediaInserter,
                                             Optional<DeliveryFailureCoordinator> deliveryFailureCoordinator) {
        this(contentRewriter, tweetMediaArchiver, tweetPublishabilityGate, weChatMediaPreparer,
                tweetMediaArchiveWriter, markdownMediaInserter, deliveryFailureCoordinator,
                new RetryPolicyProperties());
    }

    /** Story 10.8 前签名 (兼容): coordinator 与 retryPolicy 均取默认. */
    public MediaAwareRewriteArticleGenerator(ContentRewriter contentRewriter,
                                             Optional<TweetMediaArchiver> tweetMediaArchiver,
                                             Optional<TweetPublishabilityGate> tweetPublishabilityGate,
                                             WeChatMediaPreparer weChatMediaPreparer,
                                             Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter,
                                             MarkdownMediaInserter markdownMediaInserter) {
        this(contentRewriter, tweetMediaArchiver, tweetPublishabilityGate, weChatMediaPreparer,
                tweetMediaArchiveWriter, markdownMediaInserter, Optional.empty());
    }

    @Override
    public MediaAwareRewriteGeneration generate(Tweet tweet) {
        long startNanos = System.nanoTime();
        if (tweet == null) {
            throw new NonRetryableException("媒体感知改写生成失败: tweet=null");
        }
        String tweetId = tweet.getId();
        LocalDateTime publishedAt = tweet.getPublishedAt();
        if (publishedAt == null) {
            throw new NonRetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                    + ", reason=publishedAt null");
        }

        // 步骤 1: LLM 只做文字改写 (AC 4 — 改写组件全程不接触 sidecar/wechatUrl/上传状态)
        Article rewritten = callExternal(tweetId, "rewrite", () -> contentRewriter.rewrite(tweet));
        if (rewritten == null) {
            throw new RetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                    + ", reason=rewrite 返回 null");
        }

        List<TweetMedia> media = tweet.getMedia() == null ? List.of() : tweet.getMedia();
        boolean hasMedia = !media.isEmpty();
        media.stream()
                .filter(item -> item != null && item.getType() == TweetMediaType.GIF)
                .filter(item -> item.getOriginalPostUrl() == null || item.getOriginalPostUrl().isBlank())
                .forEach(item -> item.setOriginalPostUrl(tweet.getUrl()));

        // 步骤 2: 媒体下载归档 (archiver 缺失 = twitter.media.enabled=false → 配置不一致, AC 9)
        final TweetMediaArchiver.ArchiveResult archiveResult;
        if (hasMedia) {
            TweetMediaArchiver archiver = requireBean(
                    tweetMediaArchiver, "TweetMediaArchiver", "twitter.media.enabled", tweetId);
            archiveResult = callExternal(tweetId, "archiveMedia", () ->
                    archiver.archiveMedia(tweetId, publishedAt, media));
        } else {
            archiveResult = null;
        }

        if (archiveResult != null && archiveResult.failCount() > 0) {
            TweetMediaArchiveWriter archiveWriter = requireBean(
                    tweetMediaArchiveWriter, "TweetMediaArchiveWriter", "twitter.media.enabled", tweetId);
            List<TweetMedia> failedMedia = archiveWriter.readCanonicalSidecar(tweetId)
                    .map(MediaArchiveRecord::getMedia).orElse(List.of());
            Article failedArticle = Article.builder()
                    .id(rewritten.getId()).title(rewritten.getTitle()).content(rewritten.getContent())
                    .digest(rewritten.getDigest()).source(rewritten.getSource()).aiGenerated(true)
                    .createdAt(rewritten.getCreatedAt()).innovationScore(rewritten.getInnovationScore())
                    .originalUrl(rewritten.getOriginalUrl())
                    .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                    .mediaAuditMarkdown(PreserveOriginalArticleGenerator.buildMediaAuditMarkdown(
                            failedMedia, tweet.getUrl())).build();
            DeliveryFailureCoordinator coordinator = requireBean(deliveryFailureCoordinator,
                    "DeliveryFailureCoordinator", "archive.enabled", tweetId);
            DeliveryFailureCoordinator.ConvergenceResult convergence = coordinator.converge(
                    failedArticle, "MEDIA_DOWNLOAD", "MEDIA_DOWNLOAD_FAILED", "必需媒体下载失败");
            if (!convergence.converged()) {
                throw new RetryableException("媒体交付失败状态未收敛: tweetId=" + tweetId
                        + ", layer=" + convergence.incompleteLayer());
            }
            return new MediaAwareRewriteGeneration(failedArticle, 0, archiveResult.failCount());
        }

        // 步骤 3: publishability 推文级评估 (有媒体时 gate 缺失 = 配置不一致 fail-fast;
        // 无媒体保留纯文字 REWRITE_WITH_MEDIA 兼容路径, AC 9)
        if (hasMedia) {
            TweetPublishabilityGate gate = requireBean(
                    tweetPublishabilityGate, "TweetPublishabilityGate", "twitter.media.enabled", tweetId);
            var result = callExternal(tweetId, "publishabilityGate", () -> gate.evaluate(tweet));
            if (result != null && result.getTweetStatus() == PublishabilityStatus.BLOCKED) {
                // tweetReason 必须进异常: 区分 T1 (源文本不可用) / T2 (结构化访问受限),
                // 否则 "媒体全 PUBLISHABLE 但推文 BLOCKED" 的矛盾日志无法定位 (2026-08-30 排障教训)
                String tweetReason = result.getTweetReason();
                throw new TweetPublishabilityBlockedException("媒体感知改写生成失败: tweetId=" + tweetId
                        + ", reason=tweet publishability BLOCKED (gate 评估阻止发布)"
                        + (tweetReason != null ? ", tweetReason=" + tweetReason : ""));
            }
        }

        // 步骤 4+5: 微信上传 + 重读 sidecar 权威状态 (正文 URL 只来自重读快照, AD-12)
        final List<TweetMedia> sidecarMedia;
        if (hasMedia) {
            TweetMediaArchiveWriter archiveWriter = requireBean(
                    tweetMediaArchiveWriter, "TweetMediaArchiveWriter", "twitter.media.enabled", tweetId);
            // Story 10.8 分流 (镜像 PreserveOriginalArticleGenerator): 终态失败(NonRetryable,
            // 含 40164) → preparer 已写终态证据, 立即四层收敛; 可重试失败(Retryable, 含 45009) →
            // 写 RETRY_SCHEDULED 阶段证据, 文章快照保持 MEDIA_PROCESSING, 不立即收敛 —
            // 走任务级延迟重试 (AC4), 耗尽由调度器钩子按 state.articleId 收敛。
            MediaPreparationResult preparation;
            try {
                preparation = callExternal(tweetId, "prepareMedia", () ->
                        weChatMediaPreparer.prepareMedia(tweetId, publishedAt, media,
                                rewritten.getTitle(), rewritten.getDigest()));
            } catch (NonRetryableException failure) {
                Article failed = failedRewriteArticle(rewritten, List.of());
                convergeFailure(tweetId, failed, "WECHAT_PREPARE", "MEDIA_PREPARE_FAILED", "媒体微信准备失败");
                throw failure;
            } catch (RuntimeException failure) {
                tweetMediaArchiveWriter.ifPresent(writer ->
                        WeChatMediaPreparer.markSidecarPrepareRetryScheduled(writer, tweetId, publishedAt,
                                retryPolicy, errorCodeOf(failure), failure.getMessage()));
                // Story 10.8: 重抛必须携带 articleId (rewritten.getId()) — 否则调度器 instanceof
                // 判否, state.articleId 永不写入, RETRY_SCHEDULED 证据在耗尽后无收敛。
                throw new ArticleDeliveryRetryableException(rewritten.getId(),
                        "媒体微信准备失败(可重试): tweetId=" + tweetId + ", root="
                                + failure.getMessage(),
                        failure);
            }
            // Story 10.8: prepare 结果升级 — 空状态/未分类不升级 (保持 sidecar 重读既有语义)。
            if (preparation.failCount() > 0 && preparation.hasTerminalFailure()) {
                // 终态分支前把混合失败中遗留的 RETRY_SCHEDULED 媒体一并终态化,
                // 避免陈旧 nextRetryAt 证据误导 Runbook 阅读
                tweetMediaArchiveWriter.ifPresent(writer ->
                        writer.markWechatPrepareExhausted(rewritten.getId(),
                                "MEDIA_PREPARE_FAILED", "媒体微信准备失败"));
                Article failed = failedRewriteArticle(rewritten, List.of());
                convergeFailure(tweetId, failed, "WECHAT_PREPARE", "MEDIA_PREPARE_FAILED", "媒体微信准备失败");
                throw new NonRetryableException("媒体微信准备失败(终态): tweetId=" + tweetId);
            }
            if (preparation.failCount() > 0 && preparation.hasRetryableFailure()) {
                throw new ArticleDeliveryRetryableException(rewritten.getId(),
                        ErrorCode.WECHAT_RATE_LIMITED,
                        "媒体微信准备失败(可重试, wechatPrepare=RETRY_SCHEDULED): tweetId=" + tweetId
                                + ", failCount=" + preparation.failCount());
            }
            // sidecar 重读失败保持既有收敛语义 (WECHAT_PREPARE/MEDIA_PREPARE_FAILED)
            try {
                MediaArchiveRecord sidecar = callExternal(tweetId, "readSidecar", () ->
                                archiveWriter.readSidecar(tweetId, publishedAt))
                        .orElseThrow(() -> new RetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                                + ", reason=media sidecar missing after prepareMedia"));
                if (sidecar.getMedia() == null || sidecar.getMedia().isEmpty()) {
                    throw new RetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                            + ", reason=media sidecar empty after prepareMedia");
                }
                sidecarMedia = sidecar.getMedia();
            } catch (RuntimeException failure) {
                Article failed = failedRewriteArticle(rewritten, List.of());
                convergeFailure(tweetId, failed, "WECHAT_PREPARE", "MEDIA_PREPARE_FAILED", "媒体微信准备失败");
                throw failure;
            }
        } else {
            sidecarMedia = List.of();
        }

        // 步骤 6: Markdown 图片插入 (纯文本处理, LLM 正文之后)
        MarkdownMediaInserter.MarkdownMediaInsertionResult insertion =
                callExternal(tweetId, "insertMedia", () ->
                        markdownMediaInserter.insert(rewritten.getContent(), sidecarMedia));
        if (hasMedia) {
            boolean referenced = callExternal(tweetId, "markArticleReference", () ->
                    tweetMediaArchiveWriter.orElseThrow().markArticleReferencesSucceeded(tweetId, insertion.content()));
            if (!referenced) {
                Article failed = failedRewriteArticle(rewritten, sidecarMedia);
                convergeFailure(tweetId, failed, "ARTICLE_REFERENCE", "ARTICLE_REFERENCE_FAILED", "媒体正文引用失败");
                throw new RetryableException("媒体正文引用状态回写失败: tweetId=" + tweetId);
            }
        }

        // 步骤 7: 模式标记 + 审计装配 — 不复用 rewritten 实例, 防携带其 REWRITE 默认模式
        Article article = Article.builder()
                .id(rewritten.getId())
                .title(rewritten.getTitle())
                .content(insertion.content())
                .digest(rewritten.getDigest())
                .source(rewritten.getSource())
                .aiGenerated(true)
                .createdAt(rewritten.getCreatedAt())
                .innovationScore(rewritten.getInnovationScore())
                .originalUrl(rewritten.getOriginalUrl())
                .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .mediaAuditMarkdown(hasMedia
                        ? PreserveOriginalArticleGenerator.buildMediaAuditMarkdown(sidecarMedia, tweet.getUrl())
                        : null)
                .build();

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // W11 + N4: tweetId/articleId/mode/媒体计数/嵌入数/降级数/耗时, 无完整 URL/sidecar JSON
        log.info("媒体感知改写 Article 生成完成: tweetId={}, articleId={}, mode={}, mediaCount={}, "
                        + "archived={}, archivedSkipped={}, archivedFailed={}, embedded={}, degraded={}, 耗时={}ms",
                tweetId, article.getId(), article.getGenerationMode(), media.size(),
                archiveResult == null ? 0 : archiveResult.successCount(),
                archiveResult == null ? 0 : archiveResult.skipCount(),
                archiveResult == null ? 0 : archiveResult.failCount(),
                insertion.embeddedCount(), insertion.degradedCount(), elapsedMs);
        return new MediaAwareRewriteGeneration(article, insertion.embeddedCount(), insertion.degradedCount());
    }

    private Article failedRewriteArticle(Article rewritten, List<TweetMedia> media) {
        return Article.builder().id(rewritten.getId()).title(rewritten.getTitle()).content(rewritten.getContent())
                .digest(rewritten.getDigest()).source(rewritten.getSource()).aiGenerated(true)
                .createdAt(rewritten.getCreatedAt()).innovationScore(rewritten.getInnovationScore())
                .originalUrl(rewritten.getOriginalUrl()).generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .mediaAuditMarkdown(PreserveOriginalArticleGenerator.buildMediaAuditMarkdown(media, rewritten.getOriginalUrl())).build();
    }

    private void convergeFailure(String tweetId, Article article, String stage, String code, String summary) {
        DeliveryFailureCoordinator coordinator = requireBean(deliveryFailureCoordinator,
                "DeliveryFailureCoordinator", "archive.enabled", tweetId);
        var result = coordinator.converge(article, stage, code, summary);
        if (!result.converged()) throw new RetryableException("媒体交付失败状态未收敛: tweetId=" + tweetId);
    }

    /** Story 10.8: 阶段证据 errorCode 来源 — AggregatorException 取枚举名, 其余取异常类简名 (N4). */
    private static String errorCodeOf(Throwable failure) {
        if (failure instanceof AggregatorException aggregator && aggregator.getErrorCode() != null) {
            return aggregator.getErrorCode().name();
        }
        return failure.getClass().getSimpleName();
    }

    /** 有媒体但依赖 Bean 缺失 → 配置不一致 fail-fast (AC 9, message 含 tweetId + 缺失开关名)。 */
    private static <T> T requireBean(Optional<T> bean, String beanName, String requiredSwitch, String tweetId) {
        return bean.orElseThrow(() -> new NonRetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                + ", reason=" + beanName + " 未注册, 请检查 " + requiredSwitch + "=true"));
    }

    /**
     * W1+W2 包装: 外部调用意外 RuntimeException → {@link RetryableException} (可重试);
     * Retryable/NonRetryable 原样放行。message 只含 tweetId + step + 截断根因 (N4)。
     */
    private static <T> T callExternal(String tweetId, String step, Supplier<T> action) {
        try {
            return action.get();
        } catch (RetryableException | NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            String rootMessage = TextTruncateUtil.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), ERROR_MSG_MAX_CODEPOINTS);
            throw new RetryableException("媒体感知改写生成外部调用失败: tweetId=" + tweetId
                    + ", step=" + step + ", root=" + rootMessage, e);
        }
    }
}
