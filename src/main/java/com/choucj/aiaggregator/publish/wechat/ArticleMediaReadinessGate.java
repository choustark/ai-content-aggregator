package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 在所有发布入口前读取权威 sidecar 并执行 fail-closed 的必需媒体门禁。 */
@Component
@RequiredArgsConstructor
public class ArticleMediaReadinessGate {

    private final Optional<TweetMediaArchiveWriter> archiveWriter;

    /** 返回有限枚举结果，调用方不得根据内存态绕过。 */
    public GateResult evaluate(Article article) {
        if (article == null || article.getGenerationMode() == ContentGenerationMode.REWRITE) {
            return GateResult.READY;
        }
        if (archiveWriter.isEmpty() || article.getId() == null || !article.getId().startsWith("tw-")) {
            return GateResult.SIDECAR_UNAVAILABLE;
        }
        String tweetId = article.getId().substring(3);
        Optional<MediaArchiveRecord> record = archiveWriter.get().readCanonicalSidecar(tweetId);
        if (record.isEmpty() || record.get().getMedia() == null || record.get().getMedia().isEmpty()) {
            if (article.getMediaAuditMarkdown() == null || article.getMediaAuditMarkdown().isBlank()) {
                return GateResult.READY;
            }
            return GateResult.SIDECAR_UNAVAILABLE;
        }
        for (TweetMedia media : record.get().getMedia()) {
            if (media == null) {
                return GateResult.UNKNOWN_MEDIA;
            }
            if (media.getType() == null) {
                return GateResult.UNKNOWN_MEDIA;
            }
            if (media.getType() == TweetMediaType.GIF) {
                if (media.getDownload() == null
                        || media.getDownload().getStatus() != MediaPhaseStatus.DEFERRED
                        || media.getOriginalPostUrl() == null || media.getOriginalPostUrl().isBlank()
                        || media.getManualInstruction() == null || media.getManualInstruction().isBlank()) {
                    return GateResult.PHASE_INCOMPLETE;
                }
                continue;
            }
            if (media.getType() == TweetMediaType.UNKNOWN) {
                return GateResult.UNKNOWN_MEDIA;
            }
            if (media.getType() == TweetMediaType.PHOTO) {
                if (media.getDownload() == null || media.getWechatPrepare() == null
                        || media.getArticleReference() == null) {
                    return GateResult.PHASE_INCOMPLETE;
                }
                if (media.getDownload().getStatus() != MediaPhaseStatus.SUCCEEDED
                        || media.getWechatPrepare().getStatus() != MediaPhaseStatus.SUCCEEDED
                        || media.getArticleReference().getStatus() != MediaPhaseStatus.SUCCEEDED) {
                    return GateResult.PHASE_INCOMPLETE;
                }
            }
            if (media.getType() == TweetMediaType.VIDEO) {
                if (isVideoFullyPrepared(media) || isDeliberatelyDegradedVideo(media)) {
                    continue;
                }
                return GateResult.PHASE_INCOMPLETE;
            }
        }
        return GateResult.READY;
    }

    /** VIDEO 完整链路: 下载/微信准备/正文引用三阶段全部 SUCCEEDED。 */
    private static boolean isVideoFullyPrepared(TweetMedia media) {
        return media.getDownload() != null && media.getWechatPrepare() != null
                && media.getArticleReference() != null
                && media.getDownload().getStatus() == MediaPhaseStatus.SUCCEEDED
                && media.getWechatPrepare().getStatus() == MediaPhaseStatus.SUCCEEDED
                && media.getArticleReference().getStatus() == MediaPhaseStatus.SUCCEEDED;
    }

    /**
     * VIDEO 降级发布豁免 (Story 8.2 语义, E10R-VIDEO-BLOCK-SEMANTICS): 仅放行
     * 「视频文件本身下载成功、微信侧被有意跳过 (uploadStatus=SKIPPED 且携带跳过原因)
     * 且准备/引用两阶段未启动」的 VIDEO —— 按预览图+原文链接降级发布, 不再整篇阻断。
     * download FAILED_TERMINAL 等真实失败不放行 (Story 10.10 整篇失败语义不变)。
     */
    private static boolean isDeliberatelyDegradedVideo(TweetMedia media) {
        return media.getDownload() != null
                && media.getDownload().getStatus() == MediaPhaseStatus.SUCCEEDED
                && media.getUploadStatus() == MediaUploadStatus.SKIPPED
                && media.getFailureReason() != null && !media.getFailureReason().isBlank()
                && phaseNotEntered(media.getWechatPrepare())
                && phaseNotEntered(media.getArticleReference());
    }

    /** 阶段未进入判定: null / 无状态 / NOT_STARTED 均视为未启动 (sidecar 缺字段防御)。 */
    private static boolean phaseNotEntered(MediaPhaseState phase) {
        return phase == null || phase.getStatus() == null
                || phase.getStatus() == MediaPhaseStatus.NOT_STARTED;
    }

    /** 门禁结果为固定有限集，避免日志产生高基数失败原因。 */
    public enum GateResult {
        READY,
        SIDECAR_UNAVAILABLE,
        UNKNOWN_MEDIA,
        PHASE_INCOMPLETE,
        ARTICLE_DELIVERY_FAILED
    }
}
