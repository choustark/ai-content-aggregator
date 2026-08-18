package com.choucj.aiaggregator.source.twitter.media.model;

import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Objects;

/**
 * 单媒体可发布性判定决策（AC1/AC4/T1.2）
 *
 * <p>包含媒体 ID、类型（nullable）、状态、原因（截断）和是否可重试标识。
 * 不含 URL 字段（N4 编译期保证，防止日志/序列化泄露完整媒体地址）。
 *
 * @author Story 7.5
 * @since 7.5
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaPublishabilityDecision {

    /** 媒体 ID（effectiveMediaId，provider id 优先缺失时兜底 id 形如 tweetId:index:type） */
    private final String mediaId;

    /**
     * 媒体类型（nullable，Redis 反序列化防御，7.4 已处理）
     */
    private final TweetMediaType type;

    /** 媒体级可发布性状态（UNKNOWN/PUBLISHABLE/DEGRADED/BLOCKED） */
    private final PublishabilityStatus status;

    /**
     * 降级/阻止原因（reviewer-readable 中文，不含完整 URL/响应体，截断 ≤200 code points）
     *
     * <p>PUBLISHABLE 状态时为 null，其他状态携带可读原因供 UX 渲染（Story 8.5）和归档（Story 8.6）消费。
     */
    private final String reason;

    /**
     * 是否可重试（从媒体状态透传，FAILED 状态且 retryable=true 时 reason 追加"（可重试）"后缀）
     */
    private final boolean retryable;

    /** 获取媒体 ID */
    public String getMediaId() {
        return mediaId;
    }

    /** 获取媒体类型 */
    public TweetMediaType getType() {
        return type;
    }

    /** 获取状态 */
    public PublishabilityStatus getStatus() {
        return status;
    }

    /** 获取原因 */
    public String getReason() {
        return reason;
    }

    /** 获取是否可重试 */
    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaPublishabilityDecision that = (MediaPublishabilityDecision) o;
        return Objects.equals(mediaId, that.mediaId) &&
                type == that.type &&
                status == that.status &&
                Objects.equals(reason, that.reason) &&
                retryable == that.retryable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mediaId, type, status, reason, retryable);
    }

    @Override
    public String toString() {
        return "MediaPublishabilityDecision{" +
                "mediaId='" + mediaId + '\'' +
                ", type=" + type +
                ", status=" + status +
                ", reason='" + reason + '\'' +
                ", retryable=" + retryable +
                '}';
    }
}
