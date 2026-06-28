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
}
