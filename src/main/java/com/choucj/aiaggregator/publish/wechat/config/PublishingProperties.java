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
 *       batch-cron: "0 0 20 * * ?"
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
 *   <li>{@link #batchCron} — 默认 {@code 0 0 20 * * ?} 每晚 20:00 触发批量发布 (Story 3.4 AC-5).</li>
 *   <li>{@link #batchEnabled} — 默认 {@code true} ({@code matchIfMissing=true}), 关闭时低于阈值的
 *       Article 由 PublishingModeDecider 降级走实时路径, 避免写入无人消费的队列.</li>
 *   <li>{@link #queueTtlDays} — Redis 队列 TTL, 默认 7 天 (一个工作周 + 周末给运维充足补跑时间).</li>
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
     * 批量发布 cron 表达式 — BatchPublishingScheduler 触发时机 (Story 3.4 AC-5).
     *
     * <p>默认 {@code 0 0 20 * * ?} 每晚 20:00 (Spring CronExpression 格式: 秒 分 时 日 月 周).
     * 示例: {@code 0 0 20 * * ?} 每天 20:00 / {@code 0 0 22 * * ?} 每天 22:00 / {@code 0 0 8,20 * * ?} 每天 8:00+20:00.
     * 非法格式由 Spring {@code CronExpression.parse} 在 @Scheduled 解析时抛 IllegalArgumentException.
     */
    @NotBlank(message = "wechat.mp.publishing.batch-cron 不能为空")
    private String batchCron = "0 0 20 * * ?";

    /**
     * 批量调度器总开关 — 关闭时 BatchPublishingScheduler Bean 不注册
     * (@ConditionalOnProperty matchIfMissing=true, 默认开).
     *
     * <p>关闭后 PublishingModeDecider 仍注册, 低阈值 Article 降级走实时发布, 不写入无人消费的 Redis 队列.
     * 正常运行保持 true.
     */
    private boolean batchEnabled = true;

    /**
     * Redis 队列 TTL (天) — {@code publish:pending:{date}} 键过期时间 (Story 3.4 AC-7).
     *
     * <p>默认 7 天: 太短 (1 天) 跨日残留立即丢失, 太长 (30 天) 积压无限增长, 7 天给运维充足补跑时间.
     * 范围 [1, 365].
     */
    @Min(value = 1, message = "wechat.mp.publishing.queue-ttl-days 必须 >= 1")
    @Max(value = 365, message = "wechat.mp.publishing.queue-ttl-days 必须 <= 365")
    private int queueTtlDays = 7;
}
