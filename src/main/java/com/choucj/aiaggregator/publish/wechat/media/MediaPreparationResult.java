package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;

import java.util.List;

/**
 * 微信正文图片准备结果摘要，让调用方在保持逐媒体隔离的同时识别失败分类。
 *
 * <p>镜像 {@code TweetMediaArchiver.ArchiveResult} 的暴露模式：计数 + per-media 状态列表。
 * Story 10.8 起 {@code retryable} 布尔升级为 {@link FailureClass} 四分类
 * (ENVIRONMENT_BLOCKED/RATE_LIMITED/RETRYABLE/PERMANENT)，供生成器分流：
 * 可重试 → 写 {@code RETRY_SCHEDULED} + 抛 {@code RetryableException} 走任务级延迟重试；
 * 终态 → 写证据 + 立即四层收敛。
 *
 * <p>引用源: Story 8.4(创建) / Story 10.8(失败分类) / ARCHITECTURE-SPINE AD-5(单媒体失败降级)。
 */
public record MediaPreparationResult(int successCount, int skipCount, int failCount,
                                     List<MediaPreparationStatus> mediaStatuses) {

    /** Story 10.8: 准备失败四分类 — 决定 sidecar 阶段证据与生成器分流方向. */
    public enum FailureClass {
        /** 环境阻塞(40164 等) — 不可重试终态, 立即收敛. */
        ENVIRONMENT_BLOCKED,
        /** 微信限流(45009) — Retryable, 复用 task.retry.* 既有窗口. */
        RATE_LIMITED,
        /** 一般可重试(40014/-1/网络抖动) — Retryable, 复用 task.retry.* 既有窗口. */
        RETRYABLE,
        /** 永久失败 — 不可重试终态, 立即收敛. */
        PERMANENT
    }

    public static MediaPreparationResult empty() {
        return new MediaPreparationResult(0, 0, 0, List.of());
    }

    /**
     * 是否存在终态失败(ENVIRONMENT_BLOCKED/PERMANENT) — 调用方应立即写证据并四层收敛.
     */
    public boolean hasTerminalFailure() {
        return mediaStatuses.stream()
                .anyMatch(s -> s.status() == MediaUploadStatus.FAILED
                        && (s.failureClass() == FailureClass.ENVIRONMENT_BLOCKED
                        || s.failureClass() == FailureClass.PERMANENT));
    }

    /**
     * 是否存在可重试失败(RATE_LIMITED/RETRYABLE) — 调用方应写 RETRY_SCHEDULED 并走任务级延迟重试.
     */
    public boolean hasRetryableFailure() {
        return mediaStatuses.stream()
                .anyMatch(s -> s.status() == MediaUploadStatus.FAILED
                        && (s.failureClass() == FailureClass.RATE_LIMITED
                        || s.failureClass() == FailureClass.RETRYABLE));
    }

    /**
     * 单媒体微信准备状态。
     *
     * @param mediaId       媒体 ID (provider id 或 synthetic {@code tweetId:index:type})
     * @param status        上传状态 (UPLOADED/FAILED/SKIPPED)
     * @param wechatUrl     上传成功时的微信正文图片 URL；未上传为 null (N4: 结果对象不截断，
     *                      但调用方不得把它写入日志全文)
     * @param failureReason 失败/跳过的截断原因；成功为 null
     * @param retryable     失败是否可重试 (Story 10.8 前兼容信号: FAILURE 分类非 PERMANENT 即 true)
     * @param failureClass  Story 10.8 失败四分类; 成功/跳过为 null
     */
    public record MediaPreparationStatus(String mediaId, MediaUploadStatus status,
                                         String wechatUrl, String failureReason, boolean retryable,
                                         FailureClass failureClass) {

        static MediaPreparationStatus uploaded(String mediaId, String wechatUrl) {
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.UPLOADED, wechatUrl, null, false, null);
        }

        static MediaPreparationStatus skipped(String mediaId, String reason) {
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.SKIPPED, null, reason, false, null);
        }

        /** 兼容工厂(Story 10.8 前): retryable 布尔映射为 RETRYABLE/PERMANENT 二分类. */
        static MediaPreparationStatus failed(String mediaId, String reason, boolean retryable) {
            return failed(mediaId, reason, retryable ? FailureClass.RETRYABLE : FailureClass.PERMANENT);
        }

        /** Story 10.8 四分类工厂. */
        static MediaPreparationStatus failed(String mediaId, String reason, FailureClass failureClass) {
            boolean retryable = failureClass == FailureClass.RETRYABLE
                    || failureClass == FailureClass.RATE_LIMITED;
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.FAILED, null, reason,
                    retryable, failureClass);
        }
    }
}
