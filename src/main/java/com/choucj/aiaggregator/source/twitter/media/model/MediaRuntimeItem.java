package com.choucj.aiaggregator.source.twitter.media.model;

import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Story 7.4: 单媒体运行时状态摘要 — Redis 快照的最小单元.
 *
 * <p>只承载断点恢复所需的「状态摘要」字段 (mediaId/type/downloadStatus/localPath/failureReason/retryable),
 * <b>绝不包含 variant URL / sourceUrl / previewImageUrl</b> (N4: Redis 快照不存 URL,
 * 编译期保证 — 字段不存在即无法误存). 完整媒体元数据以 media.json sidecar 为权威源 (AD-6).
 *
 * <p>引用源: Story 7.4 (创建) / ARCHITECTURE-SPINE AD-6 + 一致性约定表 (tweet:{id}:media).
 */
@Value
@Builder
@Jacksonized
public class MediaRuntimeItem {

    /** 媒体 ID; provider 未返回时由调用方用 {@code tweetId:index:type} 兜底. */
    String mediaId;

    /** 媒体类型; 外部写入的旧快照反序列化后可能为 null. */
    TweetMediaType type;

    /** 下载状态; 外部写入的旧快照反序列化后可能为 null. */
    MediaDownloadStatus downloadStatus;

    /** 本地归档文件相对路径 (media/twitter/{date}/{tweetId}/{filename}); 未下载时为 null. */
    String localPath;

    /** 失败/跳过的截断根因; 成功 (DOWNLOADED) 时为 null. */
    String failureReason;

    /** 失败是否可重试 (来自 TweetMediaArchiver.MediaArchiveStatus.retryable); sidecar 重建时默认 false. */
    boolean retryable;
}
