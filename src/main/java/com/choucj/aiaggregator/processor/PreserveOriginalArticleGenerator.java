package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.converter.OriginalPostRenderResult;
import com.choucj.aiaggregator.publish.wechat.converter.OriginalPostRenderer;
import com.choucj.aiaggregator.publish.wechat.media.MediaPreparationResult;
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
import java.util.regex.Pattern;

/**
 * Story 8.6 D-C: 原帖复现 (PRESERVE_ORIGINAL) 真 gateway 实现.
 *
 * <p>编排顺序固定 (AC1, 单方法内串行):
 * <ol>
 *   <li>{@code publishedAt} 非 null fail-fast (D3, 防跨午夜目录错位, 8.4 defer#3)</li>
 *   <li>推文有媒体时: {@link TweetMediaArchiver#archiveMedia} 媒体下载归档 (缺失 → NonRetryable
 *       配置不一致, AC9)</li>
 *   <li>{@link TweetPublishabilityGate#evaluate} 推文级 BLOCKED → NonRetryable (gate 缺失时跳过)</li>
 *   <li>推文有媒体时: {@link WeChatMediaPreparer#prepareMedia} 微信正文图片上传</li>
 *   <li>推文有媒体时: {@link TweetMediaArchiveWriter#readSidecar} 重读权威媒体状态
 *       (缺失 → NonRetryable 配置不一致; sidecar 文件缺失 → 空列表降级渲染)</li>
 *   <li>{@link OriginalPostRenderer#render} + {@link OriginalPostRenderer#toArticle}
 *       (同方法内局部配对, 同源契约由调用方结构保证, 回应 8.5 defer#1/defer#6)</li>
 *   <li>html 长度预算防御 &gt; 19500 → NonRetryable (fail-fast 优于截断破坏保真, 8.5 defer#2)</li>
 *   <li>{@code article.setGenerationMode(PRESERVE_ORIGINAL)} + 媒体审计 markdown (D-E)</li>
 * </ol>
 *
 * <p><b>并发约束 (D1 设计决策):</b> 调用方必须保证同 tweetId 串行 — 现状由
 * ContentScheduler 单线程 + TwitterProcessor 串行循环结构保证; 跨轮次幂等由 sidecar
 * (wechatUrl 非空跳过上传 + 已下载跳过下载) 兜底。分布式锁 defer 到 sidecar hardening。
 *
 * <p><b>lessons-learned 模式:</b>
 * <ul>
 *   <li><b>W1+W2</b> — 外部调用 (archiver/gate/preparer/writer) 统一经
 *       {@link #callExternal} 包装: 先放行 Retryable/NonRetryable, 再把意外
 *       RuntimeException 包装为 {@link RetryableException} (message 只含 tweetId + step +
 *       截断根因, N4)</li>
 *   <li><b>N4</b> — 全部异常 message 只含 tweetId + 长度/原因标识, 不含 HTML 正文/
 *       sidecar JSON/微信响应体/完整 URL</li>
 *   <li><b>W11</b> — 完成日志含 tweetId/articleId/模式/媒体计数/htmlLength/elapsedMs</li>
 *   <li><b>N2/R3-1</b> — 审计表 failureReason 截断用 {@link TextTruncateUtil#truncateForLog}
 *       (codepoint 安全, "..." 占预算)</li>
 * </ul>
 *
 * <p>引用源: Story 8.6 scope_decision D-C/D-D/D-E; Story 8.4 幂等契约 (wechatUrl 非空判据);
 * Story 8.5 renderer 契约 (不设 generationMode, 由本类补设)。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class PreserveOriginalArticleGenerator implements OriginalPostGenerationGateway {

    /** 微信正文 HTML 长度预算 (AC7): 超过即 fail-fast, 防微信 2 万上限产生破损草稿。 */
    private static final int HTML_LENGTH_BUDGET = 19500;

    /** 审计表 failureReason 单元格截断上限 (AC4 ≤120, R3-1)。 */
    private static final int AUDIT_REASON_MAX_CODEPOINTS = 120;

    /** 意外异常根因 message 截断上限 (W1+W2, N4)。 */
    private static final int ERROR_MSG_MAX_CODEPOINTS = 200;

    /** N4: 审计 failureReason 中的完整 URL 脱敏为占位符。 */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final Optional<TweetMediaArchiver> tweetMediaArchiver;

    private final Optional<TweetPublishabilityGate> tweetPublishabilityGate;

    private final WeChatMediaPreparer weChatMediaPreparer;

    private final Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter;

    private final OriginalPostRenderer originalPostRenderer;

    @Autowired
    public PreserveOriginalArticleGenerator(Optional<TweetMediaArchiver> tweetMediaArchiver,
                                            Optional<TweetPublishabilityGate> tweetPublishabilityGate,
                                            WeChatMediaPreparer weChatMediaPreparer,
                                            Optional<TweetMediaArchiveWriter> tweetMediaArchiveWriter,
                                            OriginalPostRenderer originalPostRenderer) {
        this.tweetMediaArchiver = tweetMediaArchiver;
        this.tweetPublishabilityGate = tweetPublishabilityGate;
        this.weChatMediaPreparer = weChatMediaPreparer;
        this.tweetMediaArchiveWriter = tweetMediaArchiveWriter;
        this.originalPostRenderer = originalPostRenderer;
    }

    /**
     * 生成原帖复现 Article (AC1 编排全链)。
     *
     * <p>只抛 {@link RetryableException} / {@link NonRetryableException} 之一 (异常体系约束);
     * 意外 RuntimeException 由 {@link #callExternal} 包装为 RetryableException。
     *
     * @param tweet 原帖 (publishedAt null / 推文级 BLOCKED / html 超预算 → NonRetryable)
     * @return generationMode=PRESERVE_ORIGINAL 的 Article (id=tw-{tweetId}, aiGenerated=false)
     */
    @Override
    public Article generate(Tweet tweet) {
        long startNanos = System.nanoTime();
        if (tweet == null) {
            throw new NonRetryableException("原帖生成失败: tweet=null");
        }
        String tweetId = tweet.getId();
        LocalDateTime publishedAt = tweet.getPublishedAt();
        if (publishedAt == null) {
            // D3 fail-fast: 不许 null publishedAt 传入 archiver/preparer (防跨午夜目录错位, 8.4 defer#3)
            throw new NonRetryableException("原帖生成失败: tweetId=" + tweetId + ", reason=publishedAt null");
        }

        List<TweetMedia> media = tweet.getMedia() == null ? List.of() : tweet.getMedia();
        boolean hasMedia = !media.isEmpty();

        // AC1 步骤 2: 媒体下载归档 (有媒体时; archiver 缺失 = twitter.media.enabled=false → 配置不一致)
        final TweetMediaArchiver.ArchiveResult archiveResult;
        if (hasMedia) {
            TweetMediaArchiver archiver = requireBean(
                    tweetMediaArchiver, "TweetMediaArchiver", "twitter.media.enabled", tweetId);
            archiveResult = callExternal(tweetId, "archiveMedia", () ->
                    archiver.archiveMedia(tweetId, publishedAt, media));
        } else {
            archiveResult = null;
        }

        // AC1 步骤 3: publishability 推文级评估 (gate 缺失时跳过, 推文级 BLOCKED → fail-fast)
        tweetPublishabilityGate.ifPresent(gate -> {
            var result = callExternal(tweetId, "publishabilityGate", () -> gate.evaluate(tweet));
            // D3: gate 结果 null 视为 UNKNOWN (不阻塞), 真实 gate 契约恒返回非 null
            if (result != null && result.getTweetStatus() == PublishabilityStatus.BLOCKED) {
                throw new NonRetryableException("原帖生成失败: tweetId=" + tweetId
                        + ", reason=tweet publishability BLOCKED (gate 评估阻止发布)");
            }
        });

        final List<TweetMedia> sidecarMedia;
        MediaPreparationResult preparation = MediaPreparationResult.empty();
        if (hasMedia) {
            // 配置预检: writer 缺失 = twitter.media.enabled=false → 配置不一致,
            // 在产生上传副作用前 fail-fast (AC9)
            TweetMediaArchiveWriter archiveWriter = requireBean(
                    tweetMediaArchiveWriter, "TweetMediaArchiveWriter", "twitter.media.enabled", tweetId);
            // AC1 步骤 4: 微信正文图片上传 (幂等: wechatUrl 非空的媒体由 preparer 内部跳过)
            preparation = callExternal(tweetId, "prepareMedia", () ->
                    weChatMediaPreparer.prepareMedia(tweetId, publishedAt, media));
            // AC1 步骤 5: 从 sidecar 重读权威媒体状态。prepareMedia 后仍读不到 sidecar
            // 说明权威审计状态缺失，按可重试失败处理，避免静默生成缺图片/缺审计的草稿。
            MediaArchiveRecord sidecar = callExternal(tweetId, "readSidecar", () ->
                            archiveWriter.readSidecar(tweetId, publishedAt))
                    .orElseThrow(() -> new RetryableException("原帖生成失败: tweetId=" + tweetId
                            + ", reason=media sidecar missing after prepareMedia"));
            sidecarMedia = sidecar.getMedia() == null ? List.of() : sidecar.getMedia();
        } else {
            sidecarMedia = List.of();
        }

        // AC1 步骤 6: render + toArticle 同方法内局部配对 (同源契约)
        OriginalPostRenderResult renderResult =
                callExternal(tweetId, "render", () -> originalPostRenderer.render(tweet, sidecarMedia));
        Article article =
                callExternal(tweetId, "toArticle", () -> originalPostRenderer.toArticle(tweet, renderResult));

        // AC1 步骤 7: html 长度预算防御 (fail-fast 优于截断破坏保真)
        if (article.getContent() == null || article.getContent().length() > HTML_LENGTH_BUDGET) {
            throw new NonRetryableException("原帖生成失败: tweetId=" + tweetId
                    + ", htmlLength=" + (article.getContent() == null ? 0 : article.getContent().length())
                    + ", reason=exceeds html budget " + HTML_LENGTH_BUDGET);
        }

        // AC1 步骤 8: 模式标记 + 媒体审计摘要 (D-E; 8.5 renderer 契约: toArticle 不设 generationMode)
        article.setGenerationMode(ContentGenerationMode.PRESERVE_ORIGINAL);
        article.setMediaAuditMarkdown(hasMedia ? buildMediaAuditMarkdown(sidecarMedia, tweet.getUrl()) : null);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // W11 + N4: tweetId/articleId + 模式 + 媒体计数 (下载/上传真实值, 无媒体时全 0) + htmlLength + elapsedMs
        log.info("原帖复现 Article 生成完成: tweetId={}, articleId={}, mode={}, mediaCount={}, "
                        + "downloaded={}, downloadSkipped={}, downloadFailed={}, "
                        + "uploaded={}, uploadFailed={}, uploadSkipped={}, embedded={}, degraded={}, "
                        + "htmlLength={}, 耗时={}ms",
                tweetId, article.getId(), article.getGenerationMode(), media.size(),
                archiveResult == null ? 0 : archiveResult.successCount(),
                archiveResult == null ? 0 : archiveResult.skipCount(),
                archiveResult == null ? 0 : archiveResult.failCount(),
                preparation.successCount(), preparation.failCount(), preparation.skipCount(),
                renderResult.embeddedImageCount(), renderResult.degradedMediaCount(),
                article.getContent().length(), elapsedMs);
        return article;
    }

    /** 有媒体但依赖 Bean 缺失 → 配置不一致 fail-fast (AC9, message 含 tweetId + 缺失开关名)。 */
    private static <T> T requireBean(Optional<T> bean, String beanName, String requiredSwitch, String tweetId) {
        return bean.orElseThrow(() -> new NonRetryableException("原帖生成失败: tweetId=" + tweetId
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
            throw new RetryableException("原帖生成外部调用失败: tweetId=" + tweetId
                    + ", step=" + step + ", root=" + rootMessage, e);
        }
    }

    /**
     * 从 sidecar 权威状态生成媒体审计 markdown 表 (D-E, AC4)。
     *
     * <p>每媒体一行: 类型 / 下载状态 / 上传状态 / 可发布性 / wechatUrl (有则记) /
     * 本地相对路径 / failureReason (截断 ≤120, 上游已含 {@code <url>} 脱敏) / 原帖 URL。
     * 脱敏边界 (spike-8.1 §8): 不含 access token/AppSecret/完整响应体/本地绝对路径 —
     * sidecar 的 localPath 本身即相对路径, wechatUrl 为微信正文图片公开 URL。
     *
     * <p>Story 9.1: 从 {@code private} 提升为 package-private, 供同包
     * {@link MediaAwareRewriteArticleGenerator} 复用 (同包可见性提升模式, 行为零变化),
     * 避免审计表格式双实现 stale 风险。
     */
    static String buildMediaAuditMarkdown(List<TweetMedia> sidecarMedia, String originalUrl) {
        if (sidecarMedia == null || sidecarMedia.isEmpty()) {
            return null;
        }
        StringBuilder table = new StringBuilder();
        table.append("#### 媒体审计 (media.json sidecar 权威状态)\n\n");
        table.append("| # | 类型 | 下载状态 | 上传状态 | 可发布性 | 微信 URL | 本地路径 | 失败原因 | 原帖 URL |\n");
        table.append("|---|---|---|---|---|---|---|---|---|\n");
        int index = 0;
        for (TweetMedia media : sidecarMedia) {
            if (media == null) {
                continue;
            }
            index++;
            String reason = auditReason(media.getFailureReason());
            table.append("| ").append(index)
                    .append(" | ").append(auditCell(media.getType()))
                    .append(" | ").append(auditCell(media.getDownloadStatus()))
                    .append(" | ").append(auditCell(media.getUploadStatus()))
                    .append(" | ").append(auditCell(media.getPublishability()))
                    .append(" | ").append(auditCell(media.getWechatUrl()))
                    .append(" | ").append(auditCell(redactAbsolutePath(media.getLocalPath())))
                    .append(" | ").append(reason)
                    .append(" | ").append(auditCell(originalUrl))
                    .append(" |\n");
        }
        return table.toString();
    }

    /** 媒体审计 Markdown 表格单元格安全化: 单行 + 转义竖线, 防表格列错位。 */
    private static String auditCell(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "-";
        }
        return String.valueOf(value)
                .replaceAll("[\\r\\n\\t]", " ")
                .replace("|", "\\|");
    }

    /** failureReason 专用清洗: URL 脱敏 + 单行化 + codepoint 截断 + 表格单元格转义。 */
    private static String auditReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "-";
        }
        String singleLine = reason.replaceAll("[\\r\\n\\t]", " ");
        String redacted = URL_PATTERN.matcher(singleLine).replaceAll("<url>");
        return auditCell(TextTruncateUtil.truncateForLog(redacted, AUDIT_REASON_MAX_CODEPOINTS));
    }

    /** AC4 脱敏边界: 若 sidecar 意外写入绝对路径，归档审计只暴露文件名。 */
    private static String redactAbsolutePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path parsed = java.nio.file.Path.of(path);
            if (parsed.isAbsolute()) {
                java.nio.file.Path fileName = parsed.getFileName();
                return fileName == null ? "<absolute-path-redacted>" : "<absolute-path-redacted>/" + fileName;
            }
        } catch (RuntimeException e) {
            return "<invalid-path>";
        }
        return path;
    }
}
