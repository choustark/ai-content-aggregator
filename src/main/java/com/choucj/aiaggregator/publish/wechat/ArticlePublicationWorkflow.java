package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 发布工作流: 管理本地发布池、定时发布与人工发布的共同状态流转.
 *
 * <p><b>Story 10.6 跨日/重复投递草稿幂等</b>: 微信 {@code addDraft} 无唯一性约束,
 * 重复投递会产生重复草稿. 本类在调用 {@code publishDraft} 前按 {@code Article.id} 查询
 * 归档快照, 命中 {@code DRAFT_CREATED + 非空 wechatDraftMediaId} 即跳过创建并输出含既有
 * mediaId 的审计日志 + {@code aiaggregator.task.idempotent{kind=draft}} 指标; 快照读取失败
 * 抛 {@link NonRetryableException}(fail-closed, 由上游 L2.5 故障隔离捕获), 绝不在幂等状态
 * 未知时冒险创建草稿. 第二道守卫({@link #publishArticle} 内)实际覆盖实时与批量两入口;
 * 人工路径({@code publishNow})在到达该守卫前即由 {@code canPublish} 既有防重直接拒绝
 * DRAFT_CREATED(抛 NonRetryableException, 不计幂等指标), 此行为保持不变.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class ArticlePublicationWorkflow {

    private final WeChatPublisher weChatPublisher;
    private final ArticleArchiveRepository archiveRepository;
    private final ArticleStatusService articleStatusService;
    private final PublishingProperties publishingProperties;
    private final Optional<TaskMetrics> taskMetricsOptional;
    private final Clock clock;
    private final Optional<ArticleMediaReadinessGate> mediaReadinessGate;

    @Autowired
    public ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                                      ArticleArchiveRepository archiveRepository,
                                      ArticleStatusService articleStatusService,
                                      PublishingProperties publishingProperties,
                                      Optional<TaskMetrics> taskMetricsOptional,
                                      Optional<ArticleMediaReadinessGate> mediaReadinessGate) {
        this(weChatPublisher, archiveRepository, articleStatusService, publishingProperties,
                taskMetricsOptional, mediaReadinessGate, Clock.systemDefaultZone());
    }

    ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                               ArticleArchiveRepository archiveRepository,
                               ArticleStatusService articleStatusService,
                               PublishingProperties publishingProperties,
                               Optional<TaskMetrics> taskMetricsOptional,
                               Clock clock) {
        this(weChatPublisher, archiveRepository, articleStatusService, publishingProperties,
                taskMetricsOptional, Optional.empty(), clock);
    }

    ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                               ArticleArchiveRepository archiveRepository,
                               ArticleStatusService articleStatusService,
                               PublishingProperties publishingProperties,
                               Optional<TaskMetrics> taskMetricsOptional,
                               Optional<ArticleMediaReadinessGate> mediaReadinessGate,
                               Clock clock) {
        this.weChatPublisher = weChatPublisher;
        this.archiveRepository = archiveRepository;
        this.articleStatusService = articleStatusService;
        this.publishingProperties = publishingProperties;
        this.taskMetricsOptional = taskMetricsOptional;
        this.clock = clock;
        this.mediaReadinessGate = mediaReadinessGate;
    }

    public void queueForNextPublishWindow(Article article) {
        requireMediaReady(article);
        LocalDateTime scheduledAt = nextPublishWindow();
        archiveRepository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH, scheduledAt, null);
        articleStatusService.markPendingPublish(article.getId());
        log.info("文章进入待发布池: articleId={}, scheduledPublishAt={}", article.getId(), scheduledAt);
    }

    public String publishRealtime(Article article) {
        requireMediaReady(article);
        // Story 10.6 幂等守卫在 saveSnapshot 之前: 重复投递不再触碰快照(不改写 scheduledPublishAt),
        // 直接返回既有 mediaId. 快照读取失败抛 NonRetryableException(fail-closed).
        Optional<String> existingDraftMediaId = findExistingDraftMediaId(article);
        if (existingDraftMediaId.isPresent()) {
            auditIdempotentDraftSkip(article.getId(), existingDraftMediaId.get());
            return existingDraftMediaId.get();
        }
        archiveRepository.saveSnapshot(article, ArticleStatus.PENDING_PUBLISH, LocalDateTime.now(clock), null);
        return publishArticle(article);
    }

    public String publishNow(String articleId) {
        ArchivedArticle snapshot = archiveRepository.findByArticleId(articleId)
                .orElseThrow(() -> new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "文章归档快照不存在: articleId=" + articleId));
        if (!canPublish(snapshot.getStatus())) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "当前状态不允许发布: articleId=" + articleId + ", status=" + snapshot.getStatus());
        }
        requireMediaReady(snapshot.getArticle());
        Article article = snapshot.getArticle();
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "文章归档快照缺少 Article: articleId=" + articleId);
        }
        return publishArticle(article);
    }

    public PublishDueResult publishDueArticles() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ArchivedArticle> due = archiveRepository.findDueForPublish(now);
        long success = 0;
        long failure = 0;
        for (ArchivedArticle snapshot : due) {
            try {
                publishArticle(snapshot.getArticle());
                success++;
            } catch (Exception e) {
                failure++;
                try {
                    archiveRepository.markStatus(snapshot.getArticleId(), ArticleStatus.PENDING_PUBLISH);
                    articleStatusService.markPendingPublish(snapshot.getArticleId());
                } catch (Exception statusError) {
                    log.warn("发布失败后状态恢复失败: articleId={}", snapshot.getArticleId(), statusError);
                }
                log.error("待发布文章创建草稿失败, 已保留待发布状态: articleId={}",
                        snapshot.getArticleId(), e);
            }
        }
        return new PublishDueResult(due.size(), success, failure);
    }

    /**
     * 发布单篇文章(批量/人工入口共用) — Story 10.6 起在进入 PROCESSING 之前先查归档快照:
     * {@code DRAFT_CREATED + 非空 wechatDraftMediaId} 视为草稿已创建, 跳过 {@code addDraft}
     * 并审计既有 mediaId(微信端无唯一性约束, 重复调用会产生重复草稿).
     *
     * <p>fail-closed: 快照读取失败抛 {@link NonRetryableException} 由调用方按既有错误映射处理,
     * 不在幂等状态未知时冒险创建草稿.
     */
    private String publishArticle(Article article) {
        if (article == null || article.getId() == null || article.getId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "待发布 Article 非法");
        }
        requireMediaReady(article);
        Optional<String> existingDraftMediaId = findExistingDraftMediaId(article);
        if (existingDraftMediaId.isPresent()) {
            auditIdempotentDraftSkip(article.getId(), existingDraftMediaId.get());
            return existingDraftMediaId.get();
        }
        archiveRepository.markStatus(article.getId(), ArticleStatus.PROCESSING);
        articleStatusService.markProcessing(article.getId());
        String mediaId = weChatPublisher.publishDraft(article);
        try {
            archiveRepository.markDraftCreated(article.getId(), mediaId, LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            throw new DraftCreatedPersistenceException(article.getId(), mediaId, e);
        }
        articleStatusService.markDraftCreated(article.getId());
        return mediaId;
    }

    /** 微信草稿已创建，但本地权威快照尚未持久化；调用方必须只做本地对账，禁止再次调用微信。 */
    public static final class DraftCreatedPersistenceException extends RetryableException {
        private final String articleId;
        private final String mediaId;

        public DraftCreatedPersistenceException(String articleId, String mediaId, Throwable cause) {
            super("微信草稿已创建但本地快照写入失败: articleId=" + articleId, cause);
            this.articleId = articleId;
            this.mediaId = mediaId;
        }

        public String articleId() {
            return articleId;
        }

        public String mediaId() {
            return mediaId;
        }
    }

    /**
     * Story 10.6 草稿幂等判定 — 按 {@code Article.id} 读归档快照, 复用
     * {@link ArticleArchiveRepository#findByArticleId} 单一权威存储(不新增第二判定来源):
     * 命中条件 = {@code status == DRAFT_CREATED} 且 {@code wechatDraftMediaId} 非空白.
     * 状态非 DRAFT_CREATED 或 mediaId 缺失(如外部成功但快照回写中断的角落场景)不拦截,
     * 走既有创建路径由 {@code markDraftCreated} 补全.
     */
    private Optional<String> findExistingDraftMediaId(Article article) {
        ArchivedArticle snapshot = archiveRepository.findByArticleId(article.getId()).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        String existingMediaId = snapshot.getWechatDraftMediaId();
        boolean draftAlreadyCreated = snapshot.getStatus() == ArticleStatus.DRAFT_CREATED
                && existingMediaId != null && !existingMediaId.isBlank();
        return draftAlreadyCreated ? Optional.of(existingMediaId) : Optional.empty();
    }

    /** 幂等命中审计日志(含 articleId 与既有 mediaId; correlationId 由 MDC 自动携带). */
    private void auditIdempotentDraftSkip(String articleId, String existingDraftMediaId) {
        log.warn("草稿已创建, 跳过重复 addDraft(快照 DRAFT_CREATED 幂等命中): articleId={}, existingMediaId={}",
                articleId, existingDraftMediaId);
        recordIdempotentSkipSafely(articleId);
    }

    /**
     * 幂等 skip 指标隔离记录(Story 10.6, F-R3 safe-wrapper 模式) —
     * {@code aiaggregator.task.idempotent{kind=draft}}; 指标组件故障只降级告警,
     * 不改变"跳过创建、返回既有 mediaId"的幂等结果.
     */
    private void recordIdempotentSkipSafely(String articleId) {
        if (taskMetricsOptional.isEmpty()) {
            return;
        }
        try {
            taskMetricsOptional.get().recordIdempotent(TaskMetrics.IdempotentKind.DRAFT);
        } catch (RuntimeException e) {
            log.warn("幂等跳过指标记录失败(已降级, 不影响发布结果): articleId={}, reason={}",
                    articleId, e.getMessage());
        }
    }

    private LocalDateTime nextPublishWindow() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime todayWindow = now.toLocalDate().atTime(publishingProperties.getDailyPublishHour(), 0);
        if (now.isBefore(todayWindow)) {
            return todayWindow;
        }
        return todayWindow.plusDays(1);
    }

    private static boolean canPublish(ArticleStatus status) {
        return status == ArticleStatus.CREATED || status == ArticleStatus.PENDING_PUBLISH;
    }

    private void requireMediaReady(Article article) {
        ArchivedArticle snapshot = article == null || article.getId() == null
                ? null : archiveRepository.findByArticleId(article.getId()).orElse(null);
        if (snapshot != null && snapshot.getStatus() == ArticleStatus.DELIVERY_FAILED) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "媒体门禁拒绝发布: articleId=" + article.getId() + ", reason=ARTICLE_DELIVERY_FAILED");
        }
        if (mediaReadinessGate.isEmpty()) {
            return;
        }
        ArticleMediaReadinessGate.GateResult result = mediaReadinessGate.get().evaluate(article);
        if (result != ArticleMediaReadinessGate.GateResult.READY) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "媒体门禁拒绝发布: articleId=" + article.getId() + ", reason=" + result);
        }
    }

    public record PublishDueResult(long total, long success, long failure) {
    }
}
