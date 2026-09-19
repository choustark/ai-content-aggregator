package com.choucj.aiaggregator.common.observability;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 外部依赖慢操作阈值配置。
 *
 * <p>环境变量分别为 {@code OBSERVABILITY_SLOW_OPERATIONS_REDIS_THRESHOLD}、
 * {@code OBSERVABILITY_SLOW_OPERATIONS_HTTP_THRESHOLD} 与
 * {@code OBSERVABILITY_SLOW_OPERATIONS_SDK_THRESHOLD}；零值和负值会阻止应用启动。
 */
@Validated
@ConfigurationProperties(prefix = "observability.slow-operations")
public class SlowOperationProperties {

    /** Redis 实际调用的默认慢操作阈值。 */
    private Duration redisThreshold = Duration.ofMillis(100);

    /** HTTP 实际调用的默认慢操作阈值。 */
    private Duration httpThreshold = Duration.ofSeconds(2);

    /** SDK 实际调用的默认慢操作阈值，避免将正常 LLM 推理误报为 HTTP 慢请求。 */
    private Duration sdkThreshold = Duration.ofSeconds(10);

    public Duration getRedisThreshold() { return redisThreshold; }
    public void setRedisThreshold(Duration redisThreshold) { this.redisThreshold = redisThreshold; }
    public Duration getHttpThreshold() { return httpThreshold; }
    public void setHttpThreshold(Duration httpThreshold) { this.httpThreshold = httpThreshold; }
    public Duration getSdkThreshold() { return sdkThreshold; }
    public void setSdkThreshold(Duration sdkThreshold) { this.sdkThreshold = sdkThreshold; }

    /** 返回全部阈值是否都严格大于零。 */
    @AssertTrue(message = "observability.slow-operations thresholds must be greater than zero")
    public boolean isThresholdsPositive() {
        return isPositive(redisThreshold) && isPositive(httpThreshold) && isPositive(sdkThreshold);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
