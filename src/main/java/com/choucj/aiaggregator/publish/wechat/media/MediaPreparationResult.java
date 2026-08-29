package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;

import java.util.List;

/**
 * 微信正文图片准备结果摘要，让调用方在保持逐媒体隔离的同时识别失败是否可重试。
 *
 * <p>镜像 {@code TweetMediaArchiver.ArchiveResult} 的暴露模式：计数 + per-media 状态列表，
 * {@code retryable} 信号由 preparer 构造时确定，供 Story 8.5/8.6 调用方决定重试策略。
 *
 * <p>引用源: Story 8.4(创建) / ARCHITECTURE-SPINE AD-5(单媒体失败降级)。
 */
public record MediaPreparationResult(int successCount, int skipCount, int failCount,
                                     List<MediaPreparationStatus> mediaStatuses) {

    public static MediaPreparationResult empty() {
        return new MediaPreparationResult(0, 0, 0, List.of());
    }

    /**
     * 单媒体微信准备状态。
     *
     * @param mediaId       媒体 ID (provider id 或 synthetic {@code tweetId:index:type})
     * @param status        上传状态 (UPLOADED/FAILED/SKIPPED)
     * @param wechatUrl     上传成功时的微信正文图片 URL；未上传为 null (N4: 结果对象不截断，
     *                      但调用方不得把它写入日志全文)
     * @param failureReason 失败/跳过的截断原因；成功为 null
     * @param retryable     失败是否可重试 (探针抛 RetryableException 时为 true)
     */
    public record MediaPreparationStatus(String mediaId, MediaUploadStatus status,
                                         String wechatUrl, String failureReason, boolean retryable) {

        static MediaPreparationStatus uploaded(String mediaId, String wechatUrl) {
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.UPLOADED, wechatUrl, null, false);
        }

        static MediaPreparationStatus skipped(String mediaId, String reason) {
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.SKIPPED, null, reason, false);
        }

        static MediaPreparationStatus failed(String mediaId, String reason, boolean retryable) {
            return new MediaPreparationStatus(mediaId, MediaUploadStatus.FAILED, null, reason, retryable);
        }
    }
}
