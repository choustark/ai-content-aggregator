package com.choucj.aiaggregator.publish.status;

/**
 * Story 3.5 — 文章发布状态机 (项目首个状态机).
 *
 * <p>记录单篇文章在自动流水线中的生命周期位置,服务于人工审核支持 (Story 3.5):
 * <ul>
 *   <li>用户通过 GET {@code /api/articles/{id}/status} 查询状态</li>
 *   <li>状态存储在 Redis, key = {@code article:{articleId}:status}, TTL = 30 天</li>
 *   <li>由 {@link com.choucj.aiaggregator.publish.status.ArticleStatusService} 写入,
 *       TwitterProcessor / WeChatPublisher 在各阶段调用对应 mark 方法</li>
 * </ul>
 *
 * <p><b>状态流转 (单向不可逆):</b>
 * <pre>
 *   PENDING  ──→  PROCESSING  ──→  DRAFT_CREATED
 *      │             │
 *      │             └── (TwitterProcessor.publish 失败 → 状态停留 PROCESSING)
 *      └── (ArticleStatusService 调用前抛异常 → 不写入, 状态缺失)
 * </pre>
 *
 * <table border="1">
 *   <caption>状态入口 / 出口</caption>
 *   <tr><th>状态</th><th>入口</th><th>出口</th></tr>
 *   <tr>
 *     <td>{@link #PENDING}</td>
 *     <td>TwitterProcessor Stage 3 循环开始 ({@code markPending})</td>
 *     <td>同一文章调 {@code markProcessing}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #PROCESSING}</td>
 *     <td>WeChatPublisher.publish 调用前 ({@code markProcessing})</td>
 *     <td>WeChatPublisher 成功 ({@code markDraftCreated}) 或异常</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #DRAFT_CREATED}</td>
 *     <td>WeChatPublisher 微信草稿创建成功 ({@code markDraftCreated})</td>
 *     <td>(终态, 等待人工审核 / 自动发布, Story 4.x 范围)</td>
 *   </tr>
 * </table>
 *
 * <p><b>序列化:</b> 通过 {@link #name()} 写入 Redis (ASCII 字符串, 与 Redis 命名规范一致),
 * 反序列化用 {@link #valueOf(String)}.
 *
 * <p>引用源: Story 3.5 创建.
 */
public enum ArticleStatus {

    /**
     * 待处理 — TwitterProcessor Stage 3 已开始处理该文章 (filter 通过, 即将进入 rewrite).
     */
    PENDING,

    /**
     * 处理中 — 文章已传给 WeChatPublisher.publish, 微信 addDraft 调用进行中.
     */
    PROCESSING,

    /**
     * 草稿已创建 — 微信草稿创建成功 (addDraft 返回 media_id), 等待人工审核或定时发布.
     */
    DRAFT_CREATED
}
