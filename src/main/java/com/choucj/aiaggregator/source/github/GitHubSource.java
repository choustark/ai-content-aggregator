package com.choucj.aiaggregator.source.github;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.source.DataSource;
import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.config.GitHubProperties;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * GitHub 数据源编排器 — 实现 {@link DataSource} 通用接口 (Story 4.1).
 *
 * <p><b>spike-4.1 §2.5 架构对齐:</b> 本类是真正的 {@code DataSource<GitHubRepo>} 实现,
 * 编排底层 HTTP helper {@link GitHubClient} + Redis 缓存层. GitHubClient 本身不实现 DataSource,
 * 保持单一职责 (spike-4.1 §2.5).
 *
 * <p><b>fetch() 编排流程 (Story 4.1):</b>
 * <ol>
 *   <li>读 {@code github:trending:{language}:{lookback-days}} Redis 缓存 (1h TTL)
 *       → 命中直接返回, 跳过 GitHub API 调用, 节省 Search API 配额</li>
 *   <li>未命中调 {@link GitHubClient#fetchTrending()} (Search API + sort=stars)</li>
 *   <li>成功结果写回缓存 (1h TTL); 失败不写缓存 (避免 1h 毒化)</li>
 *   <li>缓存读取/写入异常不阻塞主流程, 视为缓存未命中 (软失败)</li>
 * </ol>
 *
 * <p><b>Bean 注册开关:</b> {@code features.github.enabled} / {@code feature-flags.github.enabled}
 * 任一显式为 false 时关闭本数据源. 默认开启 (matchIfMissing=true), 与 Story 4.4 Pipeline 集成对齐.
 *
 * <p><b>缓存 TTL 决策:</b> 1 小时 — Trending 抓取 cron 每小时 1 次 (Story 4.4 实施),
 * 1h TTL 保证开发期间多次重启不触发实际 GitHub API 调用, 节省 Search API 配额
 * (30/min 认证 / 10/min 未认证, spike-4.1 §2.3 实测).
 *
 * <p><b>异常上抛语义 (与 TwitterSource 不同):</b>
 * TwitterSource 单账号失败跳过 (面向最终用户, 部分内容总比全无强); GitHubSource 单次失败
 * 抛出 RetryableException/NonRetryableException, 由上层调度器 (Story 4.4) 决策重试或跳过本轮.
 * 原因: GitHub Trending 是一次性查询, 无 fallback 路径, 异常透明更利于排查.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = {"features.github.enabled", "feature-flags.github.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class GitHubSource implements DataSource<GitHubRepo> {

    /** github:trending:{lang}:{days} 缓存 TTL. 1h 平衡新鲜度与 API 配额. */
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final GitHubClient gitHubClient;
    private final RedisRepository redisRepository;
    private final GitHubProperties gitHubProperties;

    @Override
    public List<GitHubRepo> fetch() {
        if (!gitHubProperties.isEnabled()) {
            log.info("GitHubSource 内部开关 disabled, 返回空列表");
            return List.of();
        }

        String language = gitHubProperties.getTrending().getLanguage();
        int lookbackDays = gitHubProperties.getTrending().getLookbackDays();
        String cacheKey = RedisKeys.githubTrending(language, lookbackDays);

        GitHubRepo[] cached = readCacheOrNull(cacheKey);
        if (cached != null) {
            log.info("GitHub Trending 缓存命中: language={}, lookbackDays={}, 条数={}",
                    language, lookbackDays, cached.length);
            return Arrays.asList(cached);
        }

        List<GitHubRepo> repos = gitHubClient.fetchTrending();
        writeCache(cacheKey, repos.toArray(new GitHubRepo[0]));
        log.info("GitHub Trending 抓取完成并写回缓存: language={}, lookbackDays={}, 条数={}",
                language, lookbackDays, repos.size());
        return repos;
    }

    private GitHubRepo[] readCacheOrNull(String key) {
        try {
            return redisRepository.getObject(key, GitHubRepo[].class);
        } catch (RetryableException | NonRetryableException e) {
            log.warn("Redis 缓存读取失败, 视为未命中: key={}, reason={}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, GitHubRepo[] repos) {
        try {
            redisRepository.setObject(key, repos, CACHE_TTL);
        } catch (RetryableException | NonRetryableException e) {
            log.warn("Redis 缓存写入失败, 忽略(下次重新抓取): key={}, reason={}", key, e.getMessage());
        }
    }
}
