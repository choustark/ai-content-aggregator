package com.choucj.aiaggregator.source.twitter.model;

/**
 * 标识 X/Twitter 媒体类型，供原帖复现和后续媒体归档用同一套枚举语义。
 *
 * <p>引用源: Story 6.3(创建) / Spike 6.2 字段映射。
 */
public enum TweetMediaType {
    /** 静态图片，可作为 {@link Tweet#getImageUrls()} 的向后兼容投影来源。 */
    PHOTO,
    /** 视频媒体，下载源通常来自 provider variants。 */
    VIDEO,
    /** X animated_gif，provider 通常按 video variants 形态返回。 */
    GIF,
    /** Provider 返回了媒体但类型未知，后续必须降级处理。 */
    UNKNOWN
}
