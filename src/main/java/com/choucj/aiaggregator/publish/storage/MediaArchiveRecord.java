package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * media.json sidecar 的序列化 wrapper (Story 7.1).
 *
 * <p>每条推文的媒体归档目录下写入一个 {@code media.json}, 结构:
 * <pre>{@code
 * {
 *   "tweetId": "2083615699260313955",
 *   "generatedAt": "2026-08-02T22:30:00",
 *   "media": [ { ...TweetMedia... } ]
 * }
 * }</pre>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 跳过 null 字段, 保持 sidecar 紧凑且避免反序列化后
 * 破坏 {@code TweetMedia} 的 {@code @Builder.Default} 默认值 (D3 警示).
 *
 * <p>引用源: Story 7.1 / ARCHITECTURE-SPINE AD-6 + epics Story 7.1 AC3 字段清单.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaArchiveRecord {

    /**
     * 所属推文 ID (与目录名 {tweetId} 一致)。
     *
     * <p>Story 7.1 review patch-7: 不用 {@code @Data} — wrapper 含 mutable {@code List<TweetMedia>} 字段,
     * 自动生成的 equals/hashCode 会深比较列表且随 generatedAt 变化, 对一个序列化 wrapper 既无意义
     * (从不做值相等判断) 又有性能/语义风险。仅暴露 getter/setter。
     */
    private String tweetId;

    /** sidecar 生成/最后更新时间。 */
    private LocalDateTime generatedAt;

    /** 该推文的全部媒体项。 */
    @JsonProperty("media")
    @Builder.Default
    private List<TweetMedia> media = new ArrayList<>();
}
