package com.choucj.aiaggregator.source.twitter.model;

/**
 * 媒体下载状态，追踪每个媒体项的本地归档下载生命周期。
 *
 * <p>引用源: Story 7.1(创建) / architecture-x-media-fidelity ARCHITECTURE-SPINE AD-6。
 * Story 7.2(图片)/7.3(视频 GIF) 填值；Story 7.1 仅定义枚举与默认值 PENDING。
 */
public enum MediaDownloadStatus {
    /** 待下载（默认值，尚未尝试或重试队列中）。 */
    PENDING,
    /** 下载成功，localPath 已写入。 */
    DOWNLOADED,
    /** 下载失败（网络/HTTP/IO 错误），failureReason 记录截断根因。 */
    FAILED,
    /** 跳过（不可下载或不应下载，例如视频/GIF 暂不下载）。 */
    SKIPPED
}
