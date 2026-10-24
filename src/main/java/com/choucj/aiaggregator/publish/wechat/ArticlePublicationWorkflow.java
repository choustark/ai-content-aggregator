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
 *
 * <p><b>Story 10.16 跨实例原子认领 + 耐久收据</b>: 「快照先查后写」守卫不具备跨实例原子性,
 * 本类在守卫之后、addDraft 之前插入 {@link DraftPublishClaimService} 同槽 Lua 原子认领
 * (稳定业务键 = articleId, 租约 TTL 防崩溃残留) — 四类入口(实时/批量/恢复/人工补跑)经单点
 * 天然覆盖, 只有 {@code ACQUIRED} 分支可触达远端。addDraft 成功后写耐久收据(归档快照为权威源、
 * Redis 对账收据为镜像); 快照写回失败统一为「持久化对账状态({@code RECONCILING}) + 留存
 * mediaId」, 实时/批量不再把 {@link DraftCreatedPersistenceException} 重投为再次远端调用 —
 * 后续任何触发经 claim 收据命中只补本地写回(复用 10.6 补跑对账 fail-closed 先例), 绝不重发远端。
 * 10.6 幂等判定语义保持不变, claim 是其前置加固。
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
    private final DraftPublishClaimService draftClaimService;

    @Autowired
    public ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                                      ArticleArchiveRepository archiveRepository,
                                      ArticleStatusService articleStatusService,
                                      PublishingProperties publishingProperties,
                                      Optional<TaskMetrics> taskMetricsOptional,
                                      Optional<ArticleMediaReadinessGate> mediaReadinessGate,
                                      DraftPublishClaimService draftClaimService) {
        this(weChatPublisher, archiveRepository, articleStatusService, publishingProperties,
                taskMetricsOptional, mediaReadinessGate, draftClaimService,
                Clock.systemDefaultZone());
    }

    ArticlePublicationWorkflow(WeChatPublisher weChatPublisher,
                               ArticleArchiveRepository archiveRepository,
                               ArticleStatusService articleStatusService,
                               PublishingProperties publishingProperties,
                               Optional<TaskMetrics> taskMetricsOptional,
                               Optional<ArticleMediaReadinessGate> mediaReadinessGate,
                               DraftPublishClaimService draftClaimService,
                               Clock clock) {
        this.weChatPublisher = weChatPublisher;
        this.archiveRepository = archiveRepository;
        this.articleStatusService = articleStatusService;
        this.publishingProperties = publishingProperties;
        this.taskMetricsOptional = taskMetricsOptional;
        this.clock = clock;
        this.mediaReadinessGate = mediaReadinessGate;
        this.draftClaimService = draftClaimService;
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
                if (isParkedReconciling(snapshot.getArticleId())) {
                    // Story 10.16: 有意停候的 RECONCILING 只能经本地对账离开(裁决 1A),
                    // 常规失败恢复(PENDING_PUBLISH)会改写对账状态且误导告警, 必须跳过.
                    log.warn("文章停候 RECONCILING 对账中, 跳过失败恢复(状态恢复只会由本地对账完成): "
                            + "articleId={}", snapshot.getArticleId());
                    continue;
                }
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
     * 发布单篇文章(实时/批量/人工入口唯一汇聚点) — Story 10.16 加固后的完整流程:
     * <ol>
     *   <li>Story 10.6 快照幂等守卫(语义不变): {@code DRAFT_CREATED + 非空 wechatDraftMediaId}
     *       即跳过 {@code addDraft} 并审计既有 mediaId.</li>
     *   <li>Story 10.16 跨实例原子认领: 同槽 Lua SETNX+TTL 租约, 只有 {@code ACQUIRED} 可触达
     *       远端; {@code SKIP_HELD}(并发在途)按幂等跳过处理并记指标; {@code SKIP_EXISTING_RECEIPT}
     *       (对账收据命中)只补本地写回.</li>
     *   <li>崩溃残留检查: 认领成功但快照处于 {@code PROCESSING}/{@code RECONCILING} 中间态
     *       (上一实例可能已触达远端后崩溃)时, 有收据即对账、无收据停候 RECONCILING 告警 —
     *       绝不盲发远端.</li>
     *   <li>远端成功后的快照写回失败统一为「持久化对账状态 + 留存 mediaId」并抛
     *       {@link DraftCreatedPersistenceException} — 后续触发经收据只补本地, 绝不重投远端.</li>
     * </ol>
     *
     * <p>fail-closed: 快照读取失败抛 {@link NonRetryableException} 由调用方按既有错误映射处理,
     * 不在幂等状态未知时冒险创建草稿.
     */
    private String publishArticle(Article article) {
        if (article == null || article.getId() == null || article.getId().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "待发布 Article 非法");
        }
        requireMediaReady(article);
        ArchivedArticle snapshot = archiveRepository.findByArticleId(article.getId()).orElse(null);
        Optional<String> existingDraftMediaId = existingDraftMediaId(snapshot);
        if (existingDraftMediaId.isPresent()) {
            auditIdempotentDraftSkip(article.getId(), existingDraftMediaId.get());
            return existingDraftMediaId.get();
        }
        // Story 10.16: 原子认领在守卫之后、addDraft 之前 — gate 失败属内容问题, 不占用租约
        DraftPublishClaimService.ClaimOutcome claimOutcome = draftClaimService.acquire(article.getId());
        return switch (claimOutcome) {
            case ACQUIRED -> {
                recordDraftClaimMetricSafely(article.getId(), TaskMetrics.DraftClaimOutcome.ACQUIRED);
                // 认领成功后必须重读最新快照再判定: 守卫读取与本方 acquire 之间, 另一实例可能
                // 已完成发布并释放租约 — 用守卫时的陈旧快照判定会把 DRAFT_CREATED 误判为可发,
                // 进而在 markStatus(PROCESSING) 中覆盖既有收据并重发 addDraft(重复草稿).
                ArchivedArticle latestSnapshot =
                        archiveRepository.findByArticleId(article.getId()).orElse(null);
                yield publishWithClaim(article, latestSnapshot);
            }
            case SKIP_HELD -> {
                recordDraftClaimMetricSafely(article.getId(), TaskMetrics.DraftClaimOutcome.SKIP_HELD);
                throw new RetryableException("草稿发布租约被其他实例持有(并发在途), 本方未触达远端,"
                        + "稍后凭收据幂等跳过: articleId=" + article.getId());
            }
            case SKIP_EXISTING_RECEIPT -> reconcileFromReceipt(article.getId());
        };
    }

    /**
     * ACQUIRED 分支: 持有租约执行远端发布; 判定一律基于认领后重读的最新快照 —
     * 另一实例在本方认领前后完成发布即幂等跳过, 崩溃残留中间态先行拦截, 绝不盲发远端.
     */
    private String publishWithClaim(Article article, ArchivedArticle latestSnapshot) {
        Optional<String> racedDraftMediaId = existingDraftMediaId(latestSnapshot);
        if (racedDraftMediaId.isPresent()) {
            // 认领前后窗口内另一实例已完成发布并释放租约: 幂等返回既有收据, 绝不重发 addDraft
            log.warn("认领成功但最新快照已命中 DRAFT_CREATED(并发实例先行完成), 幂等跳过远端: "
                    + "articleId={}, mediaIdPrefix={}", article.getId(),
                    DraftPublishClaimService.maskMediaId(racedDraftMediaId.get()));
            releaseClaimSafely(article.getId());
            auditIdempotentDraftSkip(article.getId(), racedDraftMediaId.get());
            return racedDraftMediaId.get();
        }
        if (isIntermediateRemoteWindow(latestSnapshot)) {
            // 上一实例(或上一轮)崩溃于远端调用窗口: addDraft 可能已发生 — 先释放本方用不上的租约
            releaseClaimSafely(article.getId());
            return handleCrashResidue(article.getId(), latestSnapshot);
        }
        try {
            archiveRepository.markStatus(article.getId(), ArticleStatus.PROCESSING);
        } catch (RuntimeException e) {
            releaseClaimSafely(article.getId());
            throw e;
        }
        articleStatusService.markProcessing(article.getId());
        String mediaId;
        try {
            mediaId = weChatPublisher.publishDraft(article);
        } catch (RuntimeException e) {
            // 远端调用失败(未确认成功): 释放租约, 交由既有失败路径处理(至少一次语义不变)
            releaseClaimSafely(article.getId());
            throw e;
        }
        try {
            archiveRepository.markDraftCreated(article.getId(), mediaId, LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            enterReconciliation(article.getId(), mediaId);
            throw new DraftCreatedPersistenceException(article.getId(), mediaId, e);
        }
        articleStatusService.markDraftCreated(article.getId());
        // 三处收据落位(快照/Redis 镜像/任务收据由各自入口负责)后释放租约
        releaseClaimSafely(article.getId());
        return mediaId;
    }

    /**
     * 崩溃残留处置: 有收据(快照 reconcileMediaId)即只补本地写回; 无任何收据则持久化
     * RECONCILING 停候并告警 — 绝不直接重发远端(10.16 I/O 矩阵「崩溃残留 claim」行).
     */
    private String handleCrashResidue(String articleId, ArchivedArticle residueSnapshot) {
        String residueMediaId = residueSnapshot == null ? null : residueSnapshot.getReconcileMediaId();
        if (residueMediaId != null && !residueMediaId.isBlank()) {
            log.warn("认领成功但快照处于远端调用窗口中间态, 收据命中, 只补本地写回: "
                    + "articleId={}, status={}, mediaIdPrefix={}",
                    articleId, residueSnapshot.getStatus(), DraftPublishClaimService.maskMediaId(residueMediaId));
            recordDraftClaimMetricSafely(articleId, TaskMetrics.DraftClaimOutcome.SKIP_EXISTING);
            return reconcileDraftLocally(articleId, residueMediaId);
        }
        log.error("认领成功但快照处于远端调用窗口中间态且无任何收据(addDraft 是否发生未知), "
                + "停候 RECONCILING 并告警, 未触达远端, 需人工核查远端草稿: articleId={}, status={}",
                articleId, residueSnapshot == null ? null : residueSnapshot.getStatus());
        markReconcilingSafely(articleId, null);
        articleStatusService.markReconciling(articleId);
        recordDraftClaimMetricSafely(articleId, TaskMetrics.DraftClaimOutcome.RECONCILE_PARKED);
        throw new RetryableException("检测到发布崩溃残留且无收据, 已停候 RECONCILING(未触达远端),"
                + "需人工核查微信草稿箱: articleId=" + articleId);
    }

    /** claim 收据命中(SKIP_EXISTING_RECEIPT)分支: 读对账收据, 只补本地写回. */
    private String reconcileFromReceipt(String articleId) {
        DraftPublishClaimService.ReconcileReceipt receipt =
                draftClaimService.readReconcileReceipt(articleId).orElse(null);
        if (receipt == null || receipt.mediaId() == null || receipt.mediaId().isBlank()) {
            // 认领判定与读取之间收据被并发对账清除: 收据曾存在, 实为幂等命中(非他人持锁),
            // 交由既有重试重新走守卫/认领
            recordDraftClaimMetricSafely(articleId, TaskMetrics.DraftClaimOutcome.SKIP_EXISTING);
            throw new RetryableException("claim 收据命中后读取为空(并发对账竞态), 稍后重试:"
                    + "articleId=" + articleId);
        }
        log.warn("claim 收据命中, 跳过重复 addDraft, 只补本地写回: articleId={}, mediaIdPrefix={}",
                articleId, DraftPublishClaimService.maskMediaId(receipt.mediaId()));
        recordDraftClaimMetricSafely(articleId, TaskMetrics.DraftClaimOutcome.SKIP_EXISTING);
        return reconcileDraftLocally(articleId, receipt.mediaId());
    }

    /**
     * 本地对账补全(裁决 2A, 自动对账) — 复用 10.6 补跑 fail-closed 先例
     * {@code reconcileDraftCreatedFromReplay}: 成功则三处收据对齐(快照权威 + Redis 镜像 +
     * 清除对账收据)并转 DRAFT_CREATED; 失败停留 RECONCILING 并告警, 全程无远端调用。
     */
    private String reconcileDraftLocally(String articleId, String mediaId) {
        try {
            archiveRepository.reconcileDraftCreatedFromReplay(articleId, mediaId,
                    LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            log.error("草稿本地对账失败, 停留 RECONCILING(禁止远端调用): articleId={}, "
                    + "mediaIdPrefix={}", articleId, DraftPublishClaimService.maskMediaId(mediaId), e);
            throw new RetryableException("草稿收据本地对账失败(停留 RECONCILING, 未触达远端):"
                    + "articleId=" + articleId, e);
        }
        articleStatusService.markDraftCreated(articleId);
        clearReconcileReceiptSafely(articleId);
        // 与 10.6 幂等跳过口径一致: 收据命中跳过远端即幂等命中
        auditIdempotentDraftSkip(articleId, mediaId);
        return mediaId;
    }

    /**
     * 「远端成功、本地快照写回失败」的持久化对账(10.16 统一替换实时/批量重投行为) —
     * 尽力持久化三处: Redis 对账收据(同槽 Lua, 原子留存 mediaId 并释放租约) → 快照
     * RECONCILING + reconcileMediaId(软失败降级) → Redis 状态镜像(服务内软失败)。
     * 任一处失败只告警, mediaId 仍随 {@link DraftCreatedPersistenceException} 向上留存。
     */
    private void enterReconciliation(String articleId, String mediaId) {
        try {
            draftClaimService.recordReconcileReceipt(articleId, mediaId);
        } catch (RuntimeException e) {
            log.error("草稿对账收据 Redis 写入失败(软失败, 快照备注降级): articleId={}, "
                    + "mediaIdPrefix={}", articleId, DraftPublishClaimService.maskMediaId(mediaId), e);
            releaseClaimSafely(articleId);
        }
        markReconcilingSafely(articleId, mediaId);
        articleStatusService.markReconciling(articleId);
    }

    /** 快照进入 RECONCILING 的软失败包装 — 失败只告警, 不掩盖原始异常. */
    private void markReconcilingSafely(String articleId, String reconcileMediaId) {
        try {
            archiveRepository.markReconciling(articleId, reconcileMediaId);
        } catch (RuntimeException e) {
            log.error("对账状态快照写入失败(软失败降级, 对账上下文仍留存于异常与 Redis 收据): "
                    + "articleId={}, mediaIdPrefix={}", articleId,
                    DraftPublishClaimService.maskMediaId(reconcileMediaId), e);
        }
    }

    private void releaseClaimSafely(String articleId) {
        try {
            draftClaimService.release(articleId);
        } catch (RuntimeException e) {
            log.warn("草稿发布租约释放失败(租约 TTL 到期自愈, 不影响发布结果): articleId={}, reason={}",
                    articleId, e.getMessage());
        }
    }

    private void clearReconcileReceiptSafely(String articleId) {
        try {
            draftClaimService.clearReconcileReceipt(articleId);
        } catch (RuntimeException e) {
            log.warn("对账收据清除失败(残留收据不影响幂等: 后续 claim 收据命中仍走本地对账): "
                    + "articleId={}, reason={}", articleId, e.getMessage());
        }
    }

    /**
     * claim 结果指标隔离记录(Story 10.16, F-R3 safe-wrapper 模式) —
     * {@code aiaggregator.task.draft.claim{outcome=...}}; 指标故障只降级告警, 不改变发布结果.
     */
    private void recordDraftClaimMetricSafely(String articleId, TaskMetrics.DraftClaimOutcome outcome) {
        if (taskMetricsOptional.isEmpty()) {
            return;
        }
        try {
            taskMetricsOptional.get().recordDraftClaim(outcome);
        } catch (RuntimeException e) {
            log.warn("草稿认领指标记录失败(已降级, 不影响发布结果): articleId={}, outcome={}, reason={}",
                    articleId, outcome.tagValue(), e.getMessage());
        }
    }

    /** 远端调用窗口中间态判定: PROCESSING/RECONCILING 快照意味着上一轮可能已触达 addDraft. */
    private static boolean isIntermediateRemoteWindow(ArchivedArticle snapshot) {
        return snapshot != null && (snapshot.getStatus() == ArticleStatus.PROCESSING
                || snapshot.getStatus() == ArticleStatus.RECONCILING);
    }

    /** 到期循环失败恢复前置判断: 快照当前为有意停候的 RECONCILING 时跳过常规状态恢复. */
    private boolean isParkedReconciling(String articleId) {
        try {
            return archiveRepository.findByArticleId(articleId)
                    .map(snapshot -> snapshot.getStatus() == ArticleStatus.RECONCILING)
                    .orElse(false);
        } catch (RuntimeException e) {
            // 快照读取失败时交由常规恢复路径处理(其内部自会捕获并告警), 不得令 catch 路径二次抛出
            return false;
        }
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
     * Story 10.6 草稿幂等判定 — 权威快照单一来源:
     * 命中条件 = {@code status == DRAFT_CREATED} 且 {@code wechatDraftMediaId} 非空白.
     * 状态非 DRAFT_CREATED 或 mediaId 缺失不在此拦截 — 后者由 Story 10.16 认领后的
     * 崩溃残留检查/对账收据兜底, 绝不在幂等状态未知时重复远端调用.
     */
    private Optional<String> findExistingDraftMediaId(Article article) {
        return existingDraftMediaId(
                archiveRepository.findByArticleId(article.getId()).orElse(null));
    }

    private static Optional<String> existingDraftMediaId(ArchivedArticle snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }
        String existingMediaId = snapshot.getWechatDraftMediaId();
        boolean draftAlreadyCreated = snapshot.getStatus() == ArticleStatus.DRAFT_CREATED
                && existingMediaId != null && !existingMediaId.isBlank();
        return draftAlreadyCreated ? Optional.of(existingMediaId) : Optional.empty();
    }

    /** 幂等命中审计日志(含 articleId; mediaId 按 Story 10.16 脱敏只留前缀; correlationId 由 MDC 自动携带). */
    private void auditIdempotentDraftSkip(String articleId, String existingDraftMediaId) {
        log.warn("草稿已创建, 跳过重复 addDraft(快照 DRAFT_CREATED 幂等命中): articleId={}, mediaIdPrefix={}",
                articleId, DraftPublishClaimService.maskMediaId(existingDraftMediaId));
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

    /**
     * 人工发布白名单 — Story 10.16 纳入 {@code RECONCILING}(裁决 1A): 对账中的文章允许
     * 被人工触碰以触发自动对账, {@link #publishArticle} 内 claim 收据命中只补本地写回,
     * 绝不触达远端; DELIVERY_FAILED 保护终态语义不变。
     */
    private static boolean canPublish(ArticleStatus status) {
        return status == ArticleStatus.CREATED || status == ArticleStatus.PENDING_PUBLISH
                || status == ArticleStatus.RECONCILING;
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
