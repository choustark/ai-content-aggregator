package com.choucj.aiaggregator.publish.wechat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 混合发布模式配置 (Story 3.4).
 *
 * <p>对应 {@code wechat.mp.publishing.*} 前缀, 控制 PublishingModeDecider (实时/批量决策器) 与
 * BatchPublishingScheduler (批量调度器) 的运行参数. 仅在 {@code wechat.mp.enabled=true} 时生效.
 *
 * <p>配置示例 ({@code application.yml}):
 * <pre>{@code
 * wechat:
 *   mp:
 *     enabled: true
 *     publishing:
 *       realtime-threshold: 8
 *       batch-cron: "0 *&#47;30 * * * ?"
 *       daily-publish-hour: 8
 *       batch-enabled: true
 *       queue-ttl-days: 7
 * }</pre>
 *
 * <p>校验 (W7/W8/W12 模式, 复用 Story 2.5 ArchiverProperties 模式):
 * <ul>
 *   <li>W7/W8: 字段加 {@link Min} / {@link Max} / {@link NotBlank}, 防误配空值或越界值导致决策逻辑异常</li>
 *   <li>W12: 对应 PublishingPropertiesTest 加负向校验用例验证注解生效</li>
 * </ul>
 *
 * <p><b>设计决策:</b>
 * <ul>
 *   <li>{@link #realtimeThreshold} — Article.innovationScore {@code >=} 此阈值时实时发布 (Story 3.4 AC-2),
 *       严格 {@code >=} 与 epics.md "重要内容 (innovationScore {@code >=} 8)" 文案对齐 (D3 决策).</li>
 *   <li>{@link #batchCron} — 默认 {@code 0 *&#47;30 * * * ?} 每 30 分钟轮询到期稿件批量发布
 *       (定点单次 cron 在机器睡眠/应用未启动时会整批漏发且无补跑, 高频轮询靠到期制扫描幂等补发).</li>
 *   <li>{@link #batchEnabled} — 默认 {@code true} ({@code matchIfMissing=true}), 关闭时低于阈值的
 *       Article 由 PublishingModeDecider 降级走实时路径, 避免写入无人消费的队列.</li>
 *   <li>{@link #queueTtlDays} — 旧 Redis 批量队列兼容项, 新发布池不再依赖它.</li>
 * </ul>
 *
 * <p>引用源: Story 3.4 (本 story, 配置层) / Story 2.5 (ArchiverProperties 模式参考) /
 * Story 1.5a (Redis TTL 模式参考).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "wechat.mp.publishing")
public class PublishingProperties {

    /**
     * 实时发布阈值 — Article.innovationScore {@code >=} 此值时实时创建草稿 (Story 3.4 AC-2).
     *
     * <p>严格 {@code >=} 比较 (而非 {@code >}), 与 epics.md "{@code innovationScore >= 8}" 文案对齐 (D3).
     * 默认 8 (PRD FR3 调研结论: 重要内容门槛), 范围 [1, 10] — 1 表示全部实时, 10 表示全部批量.
     */
    @Min(value = 1, message = "wechat.mp.publishing.realtime-threshold 必须 >= 1")
    @Max(value = 10, message = "wechat.mp.publishing.realtime-threshold 必须 <= 10")
    private int realtimeThreshold = 8;

    /**
     * 批量发布 cron 表达式 — BatchPublishingScheduler 触发时机.
     *
     * <p>默认 {@code 0 *&#47;30 * * * ?} 每 30 分钟轮询 (Spring CronExpression 格式: 秒 分 时 日 月 周).
     * 发布扫描是到期制 ({@code scheduledPublishAt <= now}), 高频轮询幂等 — 机器睡眠/应用未启动
     * 造成的积压在任意下次触发时一次性补发. 非法格式由 Spring {@code CronExpression.parse}
     * 在 @Scheduled 解析时抛 IllegalArgumentException.
     */
    @NotBlank(message = "wechat.mp.publishing.batch-cron 不能为空")
    private String batchCron = "0 */30 * * * ?";

    /**
     * 每日计划发布小时 — 低于实时阈值的文章默认进入下一次该小时的发布窗口.
     */
    @Min(value = 0, message = "wechat.mp.publishing.daily-publish-hour 必须 >= 0")
    @Max(value = 23, message = "wechat.mp.publishing.daily-publish-hour 必须 <= 23")
    private int dailyPublishHour = 8;

    /**
     * 批量调度器总开关 — 关闭时 BatchPublishingScheduler Bean 不注册
     * (@ConditionalOnProperty matchIfMissing=true, 默认开).
     *
     * <p>关闭后 PublishingModeDecider 仍注册, 低阈值 Article 降级走实时发布, 不写入无人消费的 Redis 队列.
     * 正常运行保持 true.
     */
    private boolean batchEnabled = true;

    /**
     * 旧 Redis 队列 TTL 兼容项.
     *
     * <p>新发布池基于 {@code archive/articles/{articleId}.json} 扫描到期稿件, 不再依赖按日 Redis list.
     */
    @Min(value = 1, message = "wechat.mp.publishing.queue-ttl-days 必须 >= 1")
    @Max(value = 365, message = "wechat.mp.publishing.queue-ttl-days 必须 <= 365")
    private int queueTtlDays = 7;
}
