package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.converter.MarkdownMediaInserter;
import com.choucj.aiaggregator.publish.wechat.media.WeChatMediaPreparer;
import com.choucj.aiaggregator.source.twitter.media.TweetMediaArchiver;
import com.choucj.aiaggregator.source.twitter.media.TweetPublishabilityGate;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
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

    @Autowired
    public MediaAwareRewriteArticleGenerator(ContentRewriter contentRewriter,
                                             Optional<TweetMediaArchiver> tweetMediaArchiver,
                                             Optional<TweetPublishabilityGate> tweetPublishabilityGate,
                                             WeChatMediaPreparer weChatMediaPreparer,
                                             Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter,
                                             MarkdownMediaInserter markdownMediaInserter) {
        this.contentRewriter = contentRewriter;
        this.tweetMediaArchiver = tweetMediaArchiver;
        this.tweetPublishabilityGate = tweetPublishabilityGate;
        this.weChatMediaPreparer = weChatMediaPreparer;
        this.tweetMediaArchiveWriter = tweetMediaArchiveWriter;
        this.markdownMediaInserter = markdownMediaInserter;
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

        // 步骤 3: publishability 推文级评估 (有媒体时 gate 缺失 = 配置不一致 fail-fast;
        // 无媒体保留纯文字 REWRITE_WITH_MEDIA 兼容路径, AC 9)
        if (hasMedia) {
            TweetPublishabilityGate gate = requireBean(
                    tweetPublishabilityGate, "TweetPublishabilityGate", "twitter.media.enabled", tweetId);
            var result = callExternal(tweetId, "publishabilityGate", () -> gate.evaluate(tweet));
            if (result != null && result.getTweetStatus() == PublishabilityStatus.BLOCKED) {
                throw new TweetPublishabilityBlockedException("媒体感知改写生成失败: tweetId=" + tweetId
                        + ", reason=tweet publishability BLOCKED (gate 评估阻止发布)");
            }
        }

        // 步骤 4+5: 微信上传 + 重读 sidecar 权威状态 (正文 URL 只来自重读快照, AD-12)
        final List<TweetMedia> sidecarMedia;
        if (hasMedia) {
            TweetMediaArchiveWriter archiveWriter = requireBean(
                    tweetMediaArchiveWriter, "TweetMediaArchiveWriter", "twitter.media.enabled", tweetId);
            callExternal(tweetId, "prepareMedia", () ->
                    weChatMediaPreparer.prepareMedia(tweetId, publishedAt, media));
            MediaArchiveRecord sidecar = callExternal(tweetId, "readSidecar", () ->
                            archiveWriter.readSidecar(tweetId, publishedAt))
                    .orElseThrow(() -> new RetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                            + ", reason=media sidecar missing after prepareMedia"));
            if (sidecar.getMedia() == null || sidecar.getMedia().isEmpty()) {
                throw new RetryableException("媒体感知改写生成失败: tweetId=" + tweetId
                        + ", reason=media sidecar empty after prepareMedia");
            }
            sidecarMedia = sidecar.getMedia();
        } else {
            sidecarMedia = List.of();
        }

        // 步骤 6: Markdown 图片插入 (纯文本处理, LLM 正文之后)
        MarkdownMediaInserter.MarkdownMediaInsertionResult insertion =
                callExternal(tweetId, "insertMedia", () ->
                        markdownMediaInserter.insert(rewritten.getContent(), sidecarMedia));

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
