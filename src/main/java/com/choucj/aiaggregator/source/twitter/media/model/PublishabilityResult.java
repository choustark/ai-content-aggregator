package com.choucj.aiaggregator.source.twitter.media.model;

import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 推文级可发布性判定结果（AC1/AC4）
 *
 * <p>包含推文级状态、原因、媒体级决策列表及评估时间戳。
* 返回值结构供调用方（Epic 8 Pipeline）消费，gate 本身不执行发布动作。
*
 * @author Story 7.5
 * @since 7.5
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishabilityResult {

    /** 推文 ID（容错路径可能为 blank） */
    private final String tweetId;

    /** 推文级可发布性状态（UNKNOWN/PUBLISHABLE/DEGRADED/BLOCKED） */
    private final PublishabilityStatus tweetStatus;

    /** 推文级判定原因（reviewer-readable 中文，不含完整 URL/响应体，截断 ≤200 code points） */
    private final String tweetReason;

    /** 媒体级决策列表（与 Tweet.media 顺序一致，可能为空） */
    private final List<MediaPublishabilityDecision> mediaDecisions;

    /** 评估时间戳 */
    private final LocalDateTime evaluatedAt;

    /**
     * 创建 UNKNOWN 状态的容错结果（T1.3 静态工厂）
     *
     * <p>用于 tweetId 为 blank 或媒体状态查询失败时的容错路径。
     *
     * @param tweetId 推文 ID（可能为 blank）
     * @return UNKNOWN 状态的判定结果
     */
    public static PublishabilityResult unknown(String tweetId) {
        return PublishabilityResult.builder()
                .tweetId(tweetId)
                .tweetStatus(PublishabilityStatus.UNKNOWN)
                .tweetReason("推文 ID 为空或媒体状态查询失败，无法评估")
                .mediaDecisions(List.of())
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    /** 获取推文 ID */
    public String getTweetId() {
        return tweetId;
    }

    /** 获取推文级状态 */
    public PublishabilityStatus getTweetStatus() {
        return tweetStatus;
    }

    /** 获取推文级原因 */
    public String getTweetReason() {
        return tweetReason;
    }

    /** 获取媒体级决策列表 */
    public List<MediaPublishabilityDecision> getMediaDecisions() {
        return mediaDecisions;
    }

    /** 获取评估时间戳 */
    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PublishabilityResult that = (PublishabilityResult) o;
        return Objects.equals(tweetId, that.tweetId) &&
                tweetStatus == that.tweetStatus &&
                Objects.equals(tweetReason, that.tweetReason) &&
                Objects.equals(mediaDecisions, that.mediaDecisions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tweetId, tweetStatus, tweetReason, mediaDecisions);
    }

    @Override
    public String toString() {
        return "PublishabilityResult{" +
                "tweetId='" + tweetId + '\'' +
                ", tweetStatus=" + tweetStatus +
                ", tweetReason='" + tweetReason + '\'' +
                ", mediaDecisions=" + mediaDecisions +
                ", evaluatedAt=" + evaluatedAt +
                '}';
    }
}
