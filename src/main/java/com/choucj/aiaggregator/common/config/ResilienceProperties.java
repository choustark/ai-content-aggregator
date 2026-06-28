package com.choucj.aiaggregator.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 韧性参数配置属性.
 *
 * <p>绑定 {@code resilience.*}, 提供 RetryTemplate / TimeLimiter / 降级策略的参数源.
 * 被 Story 1.4 异常体系 (RetryableException 的重试策略) 与 Story 5.5 Cost Monitor
 * (LLM 超时基准) 读取.
 *
 * <p>所有数值字段使用 {@link Min}(0) 防止漏配负数, 0 表示禁用对应策略.
 * 嵌套字段使用 {@link Valid} 触发级联校验.
 */
@ConfigurationProperties(prefix = "resilience")
@Validated
@Data
public class ResilienceProperties {

    @Valid
    private Retry retry = new Retry();
    @Valid
    private Timeout timeout = new Timeout();
    @Valid
    private Degradation degradation = new Degradation();

    @Data
    public static class Retry {
        @Min(0)
        private int maxAttempts = 3;
        @Min(0)
        private long backoffMillis = 1000L;
    }

    @Data
    public static class Timeout {
        @Min(0)
        private long httpMillis = 10000L;
        @Min(0)
        private long redisMillis = 3000L;
        @Min(0)
        private long llmMillis = 60000L;
    }

    @Data
    public static class Degradation {
        private boolean enabled = true;
        private boolean fallbackToDefault = true;
    }
}
