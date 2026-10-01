package com.choucj.aiaggregator.task.queue;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 重试调度策略配置(Story 10.5) — 可重试失败的退避节奏与重投扫描间隔.
 *
 * <p><b>配置前缀 {@code task.retry.*}(与 {@code task.migration.*} 平级):</b>
 * <ul>
 *   <li>{@code max-attempts} — 单任务最大尝试次数(含首次), 达到即落死信终态, 默认 3</li>
 *   <li>{@code backoff-initial-ms} — 首次失败的重试延迟, 默认 60s</li>
 *   <li>{@code backoff-max-ms} — 退避上限(AC1 "退避时间受配置的上限约束"), 默认 10min</li>
 *   <li>{@code dispatch-interval-ms} — 到期重投扫描间隔({@code fixedDelay}), 默认 60s</li>
 *   <li>{@code enabled} — 重试调度总开关(关闭时 RetryDispatchScheduler 不注册), 默认 true</li>
 * </ul>
 *
 * <p><b>退避公式:</b> {@code min(backoff-initial-ms * 2^(attempt-1), backoff-max-ms)} —
 * 指数退避且封顶; {@code long} 溢出用位移前检查防护(attempt 极大时直接取上限).
 *
 * <p><b>注册方式:</b> {@code @ConfigurationPropertiesScan} 只扫 {@code common} 包,
 * 本类经 {@link TaskQueueConfig} 的 {@code @EnableConfigurationProperties} 显式注册
 * (先例: {@code ProcessorConfig} → {@code ProcessorProperties}).
 * JSR-303 校验经 {@code @Validated} 激活(10.5 review: 缺失时约束注解形同虚设,
 * 非法配置如 {@code backoff-max-ms=0} 会静默进入运行时)。
 *
 * <p>引用源: Story 10.5 创建(2026-09-22)。
 */
@ConfigurationProperties(prefix = "task.retry")
@Validated
public class RetryPolicyProperties {

    /** 单任务最大尝试次数(含首次); 第 maxAttempts 次失败即落死信. */
    @Min(value = 1, message = "task.retry.max-attempts 必须 >= 1")
    private int maxAttempts = 3;

    /** 首次失败的重试延迟(毫秒). */
    @Positive(message = "task.retry.backoff-initial-ms 必须 > 0")
    private long backoffInitialMs = 60_000L;

    /** 退避上限(毫秒) — AC1 约束, 必须 >= backoff-initial-ms. */
    @Positive(message = "task.retry.backoff-max-ms 必须 > 0")
    private long backoffMaxMs = 600_000L;

    /** 到期重投扫描间隔(毫秒, fixedDelay 语义 — 上轮结束后再计时). */
    @Positive(message = "task.retry.dispatch-interval-ms 必须 > 0")
    private long dispatchIntervalMs = 60_000L;

    /** 重试调度总开关. */
    private boolean enabled = true;

    /**
     * 计算第 {@code attempt} 次失败后的重试延迟(毫秒).
     *
     * <p>{@code attempt} 从 1 起(首次失败传 1) — 延迟 = {@code initial * 2^(attempt-1)}, 封顶
     * {@code backoffMaxMs}。指数部分先做溢出防护: 左移前若已达上限直接返回上限,
     * 避免极大 attempt 时 {@code long} 溢出翻负.
     *
     * @param attempt 即将进行的重试序号(从 1 起), 须 >= 1
     * @return 重试延迟毫秒数, 恒在 {@code [backoffInitialMs, backoffMaxMs]} 区间
     */
    public long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须 >= 1: " + attempt);
        }
        long delay = backoffInitialMs;
        for (int i = 1; i < attempt; i++) {
            if (delay >= backoffMaxMs || delay > Long.MAX_VALUE / 2) {
                return backoffMaxMs;
            }
            delay <<= 1;
        }
        return Math.min(delay, backoffMaxMs);
    }

    /** 跨字段约束: 退避上限不得小于初始延迟. */
    @AssertTrue(message = "task.retry.backoff-max-ms 必须 >= task.retry.backoff-initial-ms")
    public boolean isBackoffBoundsValid() {
        return backoffMaxMs >= backoffInitialMs;
    }

    /**
     * 读取单任务最大尝试次数(含首次).
     *
     * @return 最大尝试次数
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 设置单任务最大尝试次数(含首次).
     *
     * @param maxAttempts 最大尝试次数
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * 读取首次失败的重试延迟.
     *
     * @return 初始退避毫秒数
     */
    public long getBackoffInitialMs() {
        return backoffInitialMs;
    }

    /**
     * 设置首次失败的重试延迟.
     *
     * @param backoffInitialMs 初始退避毫秒数
     */
    public void setBackoffInitialMs(long backoffInitialMs) {
        this.backoffInitialMs = backoffInitialMs;
    }

    /**
     * 读取退避延迟上限.
     *
     * @return 最大退避毫秒数
     */
    public long getBackoffMaxMs() {
        return backoffMaxMs;
    }

    /**
     * 设置退避延迟上限.
     *
     * @param backoffMaxMs 最大退避毫秒数
     */
    public void setBackoffMaxMs(long backoffMaxMs) {
        this.backoffMaxMs = backoffMaxMs;
    }

    /**
     * 读取到期重投扫描间隔.
     *
     * @return 调度间隔毫秒数
     */
    public long getDispatchIntervalMs() {
        return dispatchIntervalMs;
    }

    /**
     * 设置到期重投扫描间隔.
     *
     * @param dispatchIntervalMs 调度间隔毫秒数
     */
    public void setDispatchIntervalMs(long dispatchIntervalMs) {
        this.dispatchIntervalMs = dispatchIntervalMs;
    }

    /**
     * 查询重试调度开关.
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置重试调度开关.
     *
     * @param enabled true 表示启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
