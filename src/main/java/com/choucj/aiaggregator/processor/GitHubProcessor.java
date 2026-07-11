package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.source.github.GitHubSource;
import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.github.service.GitHubValueAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * GitHub 处理流水线编排器 (Story 4.4 — Epic 4 收尾).
 *
 * <p>编排端到端 Pipeline, 将 Epic 4 各子模块串联为完整自动化流程 (镜像 {@link TwitterProcessor}):
 * <pre>
 *   ContentScheduler.processTask("github:run") → githubProcessor.process()
 *
 *   GitHubProcessor.process()
 *     ├─ Stage 1:   githubSource.fetch()                     (L1 fetch 兜底)
 *     ├─ Stage 1.5: githubClient.fetchReadme(owner, repo)    (per-repo try/catch, W1+W2)
 *     ├─ Stage 2:   gitHubValueAnalyzer.analyzeAndFilter()    (GitHubValueAnalyzer 内部已降级)
 *     └─ Stage 3-5: per-article rewrite + publish             (L2 per-article 隔离)
 *         for (repo : analyzed) {
 *           try { markPending → rewrite(repo) → publishers.publish(article) }
 *           catch (Exception) { failure++; log.error }
 *         }
 *     ↓ summary log.info (6 字段)
 * </pre>
 *
 * <p><b>三层故障防御 (复用 TwitterProcessor Patch-1/Patch-2/Patch-5 模式):</b>
 * <ul>
 *   <li><b>L1 fetch 兜底</b> — {@link GitHubSource#fetch()} 内部仅 catch
 *       Retryable/NonRetryable, 其他 RuntimeException 穿透; Pipeline 在 fetch 调用周围
 *       {@code catch(Exception)} 兜底, 视为本批次发现数=0, 不抛到 {@code ContentScheduler}.
 *       AC-4 配额耗尽 ({@code GitHubClientImpl} 已映射为 RetryableException) 由本层吸收</li>
 *   <li><b>L1.5 README 补全</b> — per-repo try/catch 拓宽到 RuntimeException, 单条 README 失败 →
 *       log.warn + continue, 该 repo {@code readmeContent=null} 进入 Stage 2 (LLM 走元数据评分分支)</li>
 *   <li><b>L2 per-article 隔离</b> — 单条 rewrite / publish 抛任意异常 → {@code failure++} +
 *       {@code log.error} + continue, 不影响其他 repo. {@code properties.faultIsolationEnabled=false}
 *       时异常透传到 {@code ContentScheduler} 顶层 (调试用)</li>
 *   <li><b>L3 value analyze 兜底</b> — {@link GitHubValueAnalyzer#analyzeAndFilter} 内部已
 *       per-repo catch + 全批降级, 但本层再加 N4 防御: catch RuntimeException → log.error +
 *       {@code List.of()} (避免分析器内部漏 catch 时中断 Pipeline)</li>
 * </ul>
 *
 * <p><b>Article.id 治理 (B8):</b> {@code "gh-{owner}-{repo}"} (owner/repo 内 {@code /} 替换为 {@code -}).
 * 与 {@code tw-{tweetId}} 同字符集 {@code [A-Za-z0-9_-]+}, 不需扩展 WeChatPublisher/ArticleStatusService
 * ARTICLE_ID_PATTERN 字符集 (但前缀 alternation 需扩展为 {@code (tw|gh)-...}, 见 Task 3).
 *
 * <p><b>D3 nullable 处理:</b> {@code repo.valueScore} (Double) / {@code valueSummary} (String) 可为 null
 * (AC-4 Star fallback 路径返回的 repo 这两字段保持 null). {@link SingleModelRewriter#rewrite(GitHubRepo)}
 * 内部已通过 {@link SingleModelRewriter#convertInnovationScore} 空安全转换, 本编排器不再重复 null-check.
 *
 * <p><b>Bean 注册开关:</b> 本编排器常驻注册, 三开关 {@code features.github.enabled} +
 * {@code feature-flags.github.enabled} + {@code github.enabled} 任一关闭时 {@link #process()} 直接输出
 * 0-summary 并返回. 下游 GitHub Source/Client/Analyzer 仍可按各自条件不注册, 本类通过 {@link Optional}
 * 依赖容错.
 *
 * <p><b>不重复实现已闭环的能力:</b>
 * <ul>
 *   <li>GitHub Trending 缓存 (1h TTL) — {@link GitHubSource}</li>
 *   <li>GitHub API 配额检测 + 异常映射 — {@code GitHubClientImpl}</li>
 *   <li>README 拉取 + base64 解码 + 100KB 截断 — {@link GitHubClient#fetchReadme}</li>
 *   <li>LLM 价值评分 + Star 数降级 — {@link GitHubValueAnalyzer#analyzeAndFilter}</li>
 *   <li>LLM 重试 + token 跟踪 + prompt 构造 — {@link SingleModelRewriter#rewrite(GitHubRepo)} (Task 1 扩展)</li>
 *   <li>幂等查重 (articleId) — {@code MarkdownArchiver.isAlreadyArchived}</li>
 *   <li>状态机 KEEPTTL — {@link ArticleStatusService}</li>
 *   <li>微信草稿创建 + Article→WxMpDraftArticles 转换 — {@code WeChatPublisher} + {@code ArticleToWxArticleConverter}</li>
 *   <li>批量入队 + 软失败降级 — {@code PublishingModeDecider}</li>
 * </ul>
 *
 * <p><b>日志规范 (W11/N4/N2):</b>
 * <ul>
 *   <li>W11 — 所有 {@code log.info}/{@code log.error} 含 repoFullName / 阶段名 + 计数</li>
 *   <li>N4 — 异常 message 不含 README 正文 / LLM 响应正文, 只含 repoFullName + cause 截断 (≤200 code point,
 *       复用 {@link SingleModelRewriter#truncateForLog} + {@link SingleModelRewriter#getRootMessage})</li>
 *   <li>N2 — 标题/message 按代码点截断</li>
 * </ul>
 *
 * <p>引用源: Story 4.4 / Story 2.6 (TwitterProcessor 模板) / Story 4.1 (GitHubSource) /
 * Story 4.2 (fetchReadme) / Story 4.3 (GitHubValueAnalyzer) / spike-4.1 §2.5 (架构对齐).
 */
@Slf4j
@Component
public class GitHubProcessor {

    private final Optional<GitHubSource> githubSource;
    private final Optional<GitHubClient> githubClient;
    private final Optional<GitHubValueAnalyzer> gitHubValueAnalyzer;
    private final ContentRewriter contentRewriter;
    private final List<ContentPublisher> contentPublishers;
    private final ProcessorProperties properties;
    private final ArticleStatusService articleStatusService;
    private final boolean featuresGithubEnabled;
    private final boolean featureFlagsGithubEnabled;
    private final boolean githubEnabled;

    @Autowired
    public GitHubProcessor(Optional<GitHubSource> githubSource,
                           Optional<GitHubClient> githubClient,
                           Optional<GitHubValueAnalyzer> gitHubValueAnalyzer,
                           ContentRewriter contentRewriter,
                           List<ContentPublisher> contentPublishers,
                           ProcessorProperties properties,
                           ArticleStatusService articleStatusService,
                           @Value("${features.github.enabled:true}") boolean featuresGithubEnabled,
                           @Value("${feature-flags.github.enabled:true}") boolean featureFlagsGithubEnabled,
                           @Value("${github.enabled:true}") boolean githubEnabled) {
        this.githubSource = githubSource;
        this.githubClient = githubClient;
        this.gitHubValueAnalyzer = gitHubValueAnalyzer;
        this.contentRewriter = contentRewriter;
        this.contentPublishers = contentPublishers;
        this.properties = properties;
        this.articleStatusService = articleStatusService;
        this.featuresGithubEnabled = featuresGithubEnabled;
        this.featureFlagsGithubEnabled = featureFlagsGithubEnabled;
        this.githubEnabled = githubEnabled;
    }

    GitHubProcessor(GitHubSource githubSource,
                    GitHubClient githubClient,
                    GitHubValueAnalyzer gitHubValueAnalyzer,
                    ContentRewriter contentRewriter,
                    List<ContentPublisher> contentPublishers,
                    ProcessorProperties properties,
                    ArticleStatusService articleStatusService) {
        this(Optional.ofNullable(githubSource), Optional.ofNullable(githubClient), Optional.ofNullable(gitHubValueAnalyzer),
                contentRewriter, contentPublishers, properties, articleStatusService, true, true, true);
    }

    /**
     * 执行一轮完整的 GitHub 处理流水线.
     *
     * <p>调度入口: {@code ContentScheduler.processTask("github:...")} 路由到本方法.
     * 单次执行 fetch → README 补全 → 价值分析 → per-article rewrite + publish 全链路,
     * 不抛异常到调用方 (所有故障由三层防御内部消化, 同 TwitterProcessor).
     *
     * <p>Summary 日志含 6 字段: 发现仓库数 / 价值分析通过数 / 改写成功数 / 归档(发布)成功数 /
     * 失败数 / README 补全失败数.
     */
    public void process() {
        log.info("GitHub Pipeline 启动");
        int discovered = 0;
        int readmeFetchFailure = 0;
        int valuePassed = 0;
        int rewriteSuccess = 0;
        int archiveSuccess = 0;
        int failure = 0;

        if (!isEnabled()) {
            log.info("GitHub Pipeline disabled, 直接返回: features.github.enabled={}, feature-flags.github.enabled={}, github.enabled={}",
                    featuresGithubEnabled, featureFlagsGithubEnabled, githubEnabled);
            logSummary(discovered, readmeFetchFailure, valuePassed, rewriteSuccess, archiveSuccess, failure);
            return;
        }
        if (githubSource.isEmpty() || githubClient.isEmpty() || gitHubValueAnalyzer.isEmpty()) {
            log.warn("GitHub Pipeline 依赖未注册, 视为本批次发现数=0: sourcePresent={}, clientPresent={}, analyzerPresent={}",
                    githubSource.isPresent(), githubClient.isPresent(), gitHubValueAnalyzer.isPresent());
            logSummary(discovered, readmeFetchFailure, valuePassed, rewriteSuccess, archiveSuccess, failure);
            return;
        }

        // Stage 1: fetch (L1 兜底 — catch Exception 吸收 Retryable 配额降级 AC-4 + 其他 RuntimeException)
        List<GitHubRepo> repos;
        try {
            repos = githubSource.get().fetch();
        } catch (Exception e) {
            log.error("GitHubSource.fetch 失败, 本批次跳过 (故障隔离 L1 兜底)", e);
            repos = List.of();
        }
        if (repos == null) {
            log.warn("GitHubSource.fetch 返回 null, 归一化为空列表 (故障隔离 L1 兜底)");
            repos = List.of();
        }
        discovered = repos.size();

        // Stage 1.5: README 补全 (per-repo try/catch W1+W2, 单条失败 readmeContent=null 走元数据评分)
        for (GitHubRepo repo : repos) {
            try {
                String[] parts = splitOwnerRepo(repo.getFullName());
                String readme = githubClient.get().fetchReadme(parts[0], parts[1]);
                repo.setReadmeContent(readme);
            } catch (Exception e) {
                readmeFetchFailure++;
                log.warn("README 补全失败, 走元数据评分分支 (故障隔离 L1.5): repoFullName={}, cause={}",
                        safeFullName(repo),
                        SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200));
                // readmeContent 保持原值 (null / 或 fetch 时已填充); valueAnalyzer/rewriter null-safe
            }
        }

        // Stage 2: value analyze (L3 兜底 — analyzer 内部已 per-repo catch + 全批降级,
        // 本层再加一道 catch RuntimeException 防御 analyzer 内部漏 catch 异常逃逸)
        List<GitHubRepo> analyzed;
        try {
            analyzed = gitHubValueAnalyzer.get().analyzeAndFilter(repos);
        } catch (RuntimeException e) {
            log.error("GitHubValueAnalyzer.analyzeAndFilter 失败, 视为本批次通过数=0 (故障隔离 L3 兜底)", e);
            analyzed = List.of();
        }
        if (analyzed == null) {
            log.warn("GitHubValueAnalyzer.analyzeAndFilter 返回 null, 归一化为空列表 (故障隔离 L3 兜底)");
            analyzed = List.of();
        }
        valuePassed = analyzed.size();

        // Stage 3+4+5: per-article rewrite + publish (L2 per-article 隔离)
        for (GitHubRepo repo : analyzed) {
            try {
                // Story 3.5 AC-1: rewrite 前写 PENDING (articleId 经校验, 不污染 Redis namespace)
                String expectedArticleId = buildDeterministicArticleId(repo);
                articleStatusService.markPending(expectedArticleId);
                Article article = contentRewriter.rewrite(repo);
                validateRewrittenArticleId(article, expectedArticleId);
                rewriteSuccess++;
                for (ContentPublisher publisher : contentPublishers) {
                    publisher.publish(article);
                }
                archiveSuccess++;
            } catch (Exception e) {
                failure++;
                log.error("文章处理失败, 跳过 (故障隔离 L2 per-article): repoFullName={}, cause={}",
                        safeFullName(repo),
                        SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200),
                        e);
                if (!properties.isFaultIsolationEnabled()) {
                    throw e;
                }
            }
        }

        // AC-5 summary (6 字段)
        logSummary(discovered, readmeFetchFailure, valuePassed, rewriteSuccess, archiveSuccess, failure);
    }

    private boolean isEnabled() {
        return featuresGithubEnabled && featureFlagsGithubEnabled && githubEnabled;
    }

    private static void logSummary(int discovered,
                                   int readmeFetchFailure,
                                   int valuePassed,
                                   int rewriteSuccess,
                                   int archiveSuccess,
                                   int failure) {
        log.info("GitHub Pipeline 完成: 发现={}, README 补全失败={}, 价值分析通过={}, 改写成功={}, 归档成功={}, 失败={}",
                discovered, readmeFetchFailure, valuePassed, rewriteSuccess, archiveSuccess, failure);
    }

    private static void validateRewrittenArticleId(Article article, String expectedArticleId) {
        if (article == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "GitHub 改写返回 Article 为 null: expectedArticleId=" + expectedArticleId);
        }
        if (!expectedArticleId.equals(article.getId())) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "GitHub 改写返回 Article.id 不一致: expected=" + expectedArticleId + ", actual=" + article.getId());
        }
    }

    private static String safeFullName(GitHubRepo repo) {
        return repo == null ? null : repo.getFullName();
    }

    /**
     * 拆分 {@code owner/repo} 为 {@code [owner, repo]}, 校验格式合法.
     *
     * <p>用于 Stage 1.5 调用 {@link GitHubClient#fetchReadme} 前. 复用 {@link SingleModelRewriter}
     * 的校验逻辑: 必须含且仅含一个 {@code /}, 两段均非空, 字符集 {@code [A-Za-z0-9._-]+}.
     * 校验失败抛 {@link NonRetryableException}, 由 Stage 1.5 的 per-repo try/catch 兜底.
     */
    private static String[] splitOwnerRepo(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName: " + fullName);
        }
        long slashCount = fullName.chars().filter(c -> c == '/').count();
        if (slashCount != 1) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName (必须 owner/repo): " + fullName);
        }
        String[] parts = fullName.split("/", 2);
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName (必须 owner/repo): " + fullName);
        }
        return parts;
    }

    /**
     * 构造确定性 Article.id: {@code "gh-{owner}-{repo}"} (B8).
     *
     * <p>校验 {@code repo.fullName} 非空 + 单 {@code /} 分隔 + 两段字符集 {@code [A-Za-z0-9_-]+},
     * 失败抛 {@link NonRetryableException}. {@code /} 替换为 {@code -}, 与 {@code tw-{tweetId}}
     * 字符集 {@code [A-Za-z0-9_-]+} 一致 (Story 4.4 Task 3.1 收紧 — 与
     * {@code WeChatPublisher.ARTICLE_ID_PATTERN} / {@code ArticleStatusService.ARTICLE_ID_PATTERN}
     * 同字符集, 简化 Redis key / Article.id 治理).
     */
    static String buildDeterministicArticleId(GitHubRepo repo) {
        if (repo == null || repo.getFullName() == null || repo.getFullName().isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName: " + (repo == null ? null : repo.getFullName()));
        }
        String fullName = repo.getFullName();
        long slashCount = fullName.chars().filter(c -> c == '/').count();
        if (slashCount != 1) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName (必须 owner/repo): " + fullName);
        }
        String[] parts = fullName.split("/", 2);
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "非法 repo.fullName (必须 owner/repo): " + fullName);
        }
        for (String p : parts) {
            if (!p.matches("[A-Za-z0-9_-]+")) {
                throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "非法 repo.fullName 段 (必须 [A-Za-z0-9_-]+): " + fullName);
            }
        }
        return "gh-" + parts[0] + "-" + parts[1];
    }
}
