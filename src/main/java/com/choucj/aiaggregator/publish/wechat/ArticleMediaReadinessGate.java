package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
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
            if (media.getType() == TweetMediaType.PHOTO || media.getType() == TweetMediaType.VIDEO) {
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
        }
        return GateResult.READY;
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
