package com.choucj.aiaggregator.source.twitter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存视频/GIF 的一个候选播放或下载 variant，避免把 URL 塞进 LLM prompt。
 *
 * <p>引用源: Story 6.3(创建) / Spike 6.1 video_info.variants / Spike 6.2 FxTwitter variants。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TweetMediaVariant {

    /** variant URL；可能为 HLS m3u8 或 mp4，日志不得完整输出。 */
    private String url;

    /** MIME 类型或 provider content_type；provider 未返回时为 null。 */
    private String contentType;

    /** 码率；HLS 或未知码率 variant 可能为 null。 */
    private Long bitrate;

    /** 宽度；provider 未返回时为 null。 */
    private Integer width;

    /** 高度；provider 未返回时为 null。 */
    private Integer height;
}
