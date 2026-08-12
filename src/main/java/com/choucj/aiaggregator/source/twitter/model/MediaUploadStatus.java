package com.choucj.aiaggregator.source.twitter.model;

/**
 * 媒体微信上传状态，追踪每个媒体项上传到微信公众号的生命周期。
 *
 * <p>引用源: Story 7.1(创建) / architecture-x-media-fidelity ARCHITECTURE-SPINE AD-6。
 * Story 8.4(微信正文图片上传) 填值；Story 7.1 仅定义枚举与默认值 PENDING。
 */
public enum MediaUploadStatus {
    /** 待上传（默认值）。 */
    PENDING,
    /** 上传成功，wechatUrl/wechatMediaId 已写入。 */
    UPLOADED,
    /** 上传失败，failureReason 记录截断根因。 */
    FAILED,
    /** 跳过（该媒体类型不进入微信正文图片链路，例如视频/GIF 降级）。 */
    SKIPPED
}
