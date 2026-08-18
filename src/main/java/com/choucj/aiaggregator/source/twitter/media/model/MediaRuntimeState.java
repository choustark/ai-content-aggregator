package com.choucj.aiaggregator.source.twitter.media.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Story 7.4: 推文媒体运行时状态快照 — Redis key {@code tweet:{id}:media} 的 JSON value.
 *
 * <p>一推文多媒体的聚合状态摘要, 由 {@code MediaRuntimeStateRepository.saveSnapshot} 在
 * {@code TweetMediaArchiver.archiveMedia} 完成后写入 (先 sidecar 后 Redis, 双源不冲突),
 * 供 {@code MediaRuntimeRecoveryService} 重启后识别已下载/失败/跳过/待重试媒体项.
 *
 * <p>Redis 快照是运行时快速恢复源 (TTL 30 天); media.json sidecar 是长期审计权威源.
 *
 * <p>引用源: Story 7.4 (创建) / ARCHITECTURE-SPINE AD-6 + NFR3 (运行时状态 Redis 持久化).
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaRuntimeState {

    /** 推文 ID (快照归属). */
    String tweetId;

    /** 快照生成时间; 由写入方填充. */
    LocalDateTime generatedAt;

    /** 每媒体状态摘要列表; 无媒体时为空列表. */
    @Builder.Default
    List<MediaRuntimeItem> mediaStates = List.of();
}
