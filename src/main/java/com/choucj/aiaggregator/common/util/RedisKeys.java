package com.choucj.aiaggregator.common.util;

/**
 * Redis 键命名工具 — 集中管理键前缀,保证合规.
 *
 * <p><b>合规要求(架构 L1299-1312):</b>
 * <ul>
 *   <li>规则: 冒号分隔的小写命名(Redis 标准惯例)</li>
 *   <li>正确: {@code "task:queue"} / {@code "article:123"} / {@code "cache:rsshub:url"}</li>
 *   <li>错误: {@code "taskQueue"} / {@code "TASK_QUEUE"}</li>
 * </ul>
 *
 * <p><b>不可实例化:</b> {@code final class} + {@code private} 构造器,纯静态方法工具类.
 *
 * <p>引用源: Story 1.5a 创建;消费方所有写 Redis 的业务代码.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /**
     * 通用缓存键.
     *
     * @param namespace 业务命名空间(如 {@code "rsshub"} / {@code "github"})
     * @param key       缓存键(如 URL / ID)
     * @return {@code "cache:{namespace}:{key}"}
     */
    public static String cache(String namespace, String key) {
        return "cache:" + namespace + ":" + key;
    }

    /**
     * 任务队列键.
     *
     * @param queue 队列名(如 {@code "default"} / {@code "twitter-fetch"})
     * @return {@code "task:{queue}"}
     */
    public static String task(String queue) {
        return "task:" + queue;
    }

    /**
     * Story 1.6: 默认任务队列键(Redis List).
     *
     * <p>FIFO 队列,业务侧 {@code push} 入队右端, {@code poll} 从左端取出.
     *
     * @return {@code "task:queue"}
     */
    public static String taskQueue() {
        return "task:queue";
    }

    /**
     * Story 1.6: 正在处理的任务集合键(Redis Set).
     *
     * <p>断点恢复依据: 进程崩溃后启动时 {@link com.choucj.aiaggregator.task.queue.TaskRecoveryRunner}
     * 检查此集合,把任务重新入队到 {@link #taskQueue()}.
     *
     * @return {@code "task:processing"}
     */
    public static String taskProcessing() {
        return "task:processing";
    }

    /**
     * 分布式锁键.
     *
     * @param resource 资源标识(如 {@code "rss:url1"} / {@code "wx:publish"})
     * @return {@code "lock:{resource}"}
     */
    public static String lock(String resource) {
        return "lock:" + resource;
    }

    /**
     * Story 2.2a: 单条 Tweet 详情缓存键.
     *
     * <p>FxTwitter 补全结果以 tweet ID 为粒度缓存 24h,命中即跳过 FxTwitter 调用,
     * 避免 429/限流并加速 TwitterSource.fetch().
     *
     * @param tweetId 推文 ID(纯数字)
     * @return {@code "tweet:{tweetId}"}
     */
    public static String tweet(String tweetId) {
        return "tweet:" + tweetId;
    }

    /**
     * Story 2.4: 每日 LLM 成本累计键 (token 数).
     *
     * <p>键格式: {@code "cost:daily:{yyyy-MM-dd}"} (e.g., {@code "cost:daily:2026-06-28"}).
     * 由 {@link com.choucj.aiaggregator.monitoring.TokenUsageTracker} 写入,
     * 累计当日 prompt + response 的 token 估算值 ({@code chars / 4}, OpenAI 经验值).
     * 7 天 TTL — 单实例部署无并发, 用 {@code get → parseLong → add → set} 模式模拟 INCRBY.
     *
     * @param date 日期 (LocalDate, 不为 null)
     * @return {@code "cost:daily:{date}"} 键字符串
     */
    public static String costDaily(java.time.LocalDate date) {
        return "cost:daily:" + date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Story 3.4: 批量发布待办队列键 (Redis List).
     *
     * <p>键格式: {@code "publish:pending:{yyyy-MM-dd}"} (e.g., {@code "publish:pending:2026-07-06"}).
     * 由 {@link com.choucj.aiaggregator.publish.wechat.PublishingModeDecider} 入队 (rPush Article JSON),
     * 由 {@link com.choucj.aiaggregator.publish.wechat.BatchPublishingScheduler} 在每晚 20:00 cron 触发消费
     * (lPop + 反序列化 + WeChatPublisher.publish).
     *
     * <p><b>键命名空间分离 (与 Story 3.5 协同 M1 风险):</b>
     * 本键是 List ({@code publish:pending:{date}}), Story 3.5 ArticleStatus 用 String
     * ({@code article:{id}:status}) — 不同 namespace 不冲突.
     *
     * <p>跨日场景: Article.createdAt 决定写入哪天的队列, BatchPublishingScheduler 在 20:00 cron 时只读
     * {@code LocalDate.now()} 当日队列; 跨日残留因 TTL 7 天仍在 Redis, 但不自动补跑 (YAGNI, Story 5.x 范围).
     *
     * @param date 日期 (LocalDate, 不为 null; 由 Article.createdAt.toLocalDate() 或 LocalDate.now() 提供)
     * @return {@code "publish:pending:{date}"} 键字符串
     */
    public static String publishPending(java.time.LocalDate date) {
        return "publish:pending:" + date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Story 3.5: 单篇文章状态机键 (Redis String).
     *
     * <p>键格式: {@code "article:{articleId}:status"} (e.g., {@code "article:tw-1234567890:status"}),
     * 由 {@link com.choucj.aiaggregator.publish.status.ArticleStatusService} 写入,
     * 值为 {@link com.choucj.aiaggregator.publish.status.ArticleStatus} 枚举 name() 字符串,
     * TTL 30 天.
     *
     * <p><b>键命名空间分离 (与 Story 3.4 协同 M1 风险):</b>
     * 本键是 String ({@code article:{id}:status}), Story 3.4 publishPending 用 List
     * ({@code publish:pending:{date}}) — 不同 namespace 不冲突.
     *
     * <p><b>键命名稳定性:</b> articleId 由 {@link com.choucj.aiaggregator.common.model.Article#getId()}
     * 提供, B8 规则要求确定性 ID (Twitter: {@code tw-{tweetId}}), 不含换行 / 空格,
     * 不存在 Redis key 注入风险 (但 ArticleStatusService 仍会校验防御深度).
     *
     * @param articleId 文章 ID (如 {@code "tw-1234567890"}), 不为 null/blank
     * @return {@code "article:{articleId}:status"} 键字符串
     */
    public static String articleStatus(String articleId) {
        return "article:" + articleId + ":status";
    }

    /**
     * Story 4.1: GitHub Trending 仓库列表缓存键 (Redis JSON, List&lt;GitHubRepo&gt;).
     *
     * <p>键格式: {@code "github:trending:{language}:{lookback-days}"}
     * (e.g., {@code "github:trending:java:7"}).
     * 由 {@link com.choucj.aiaggregator.source.github.GitHubSource#fetch()} 写入,
     * 值为 {@code List<GitHubRepo>} 的 JSON 序列化, TTL 1 小时.
     *
     * <p><b>键命名空间分离 (spike-4.1 §2.4):</b>
     * 与 twitter:* (Story 2.2a) / article:* (Story 3.5) / publish:* (Story 3.4) /
     * cost:* (Story 2.4) / task:* (Story 1.6) 命名空间不冲突.
     *
     * <p><b>TTL 决策:</b> 1 小时 — Trending 抓取 cron 每小时 1 次 (Story 4.4 实施),
     * 1h TTL 保证开发期间多次重启不触发实际 GitHub API 调用, 节省 Search API 配额
     * (30/min 认证, spike-4.1 §2.3 实测).
     *
     * @param language     主语言 (如 {@code "java"} / {@code "python"}, 不为 null/blank)
     * @param lookbackDays 回溯天数 (1-90, spike-4.1 §2.1 默认 7)
     * @return {@code "github:trending:{language}:{lookback-days}"} 键字符串
     */
    public static String githubTrending(String language, int lookbackDays) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("lookbackDays must be >= 1");
        }
        return "github:trending:" + language + ":" + lookbackDays;
    }
}
