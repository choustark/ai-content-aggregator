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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleMediaReadinessGateTest {

    private final TweetMediaArchiveWriter writer = mock(TweetMediaArchiveWriter.class);
    private final ArticleMediaReadinessGate gate = new ArticleMediaReadinessGate(Optional.of(writer));

    @Test
    void should_allow_when_all_required_media_phases_succeeded() {
        when(writer.readCanonicalSidecar("1")).thenReturn(Optional.of(record(photo(MediaPhaseStatus.SUCCEEDED))));

        assertThat(gate.evaluate(article("tw-1"))).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
    }

    @Test
    void should_block_when_any_required_media_phase_is_incomplete() {
        when(writer.readCanonicalSidecar("1")).thenReturn(Optional.of(record(photo(MediaPhaseStatus.NOT_STARTED))));

        assertThat(gate.evaluate(article("tw-1")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
    }

    @Test
    void should_ignore_deferred_gif_but_block_unknown_media() {
        TweetMedia gif = TweetMedia.builder().id("g").type(TweetMediaType.GIF).build();
        gif.initializeDeliveryPhases("https://x/status/1");
        when(writer.readCanonicalSidecar("gif")).thenReturn(Optional.of(record(gif)));
        when(writer.readCanonicalSidecar("unknown")).thenReturn(Optional.of(record(
                TweetMedia.builder().id("u").type(TweetMediaType.UNKNOWN).build())));

        assertThat(gate.evaluate(article("tw-gif"))).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
        assertThat(gate.evaluate(article("tw-unknown")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.UNKNOWN_MEDIA);
    }

    @Test
    void should_reject_incomplete_gif_and_null_type() {
        when(writer.readCanonicalSidecar("bad-gif")).thenReturn(Optional.of(record(
                TweetMedia.builder().id("g").type(TweetMediaType.GIF).build())));
        when(writer.readCanonicalSidecar("null-type")).thenReturn(Optional.of(record(
                TweetMedia.builder().id("u").build())));

        assertThat(gate.evaluate(article("tw-bad-gif")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
        assertThat(gate.evaluate(article("tw-null-type")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.UNKNOWN_MEDIA);
    }

    @Test
    void should_allow_deliberately_degraded_video_publish() {
        when(writer.readCanonicalSidecar("1")).thenReturn(Optional.of(record(degradedVideo())));

        assertThat(gate.evaluate(article("tw-1"))).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
    }

    @Test
    void should_allow_mixed_photo_and_degraded_video() {
        when(writer.readCanonicalSidecar("1"))
                .thenReturn(Optional.of(record(photo(MediaPhaseStatus.SUCCEEDED), degradedVideo())));

        assertThat(gate.evaluate(article("tw-1"))).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
    }

    @Test
    void should_block_video_with_terminal_download_failure() {
        // markDownloadFailed 后 uploadStatus 保持 PENDING: 真实失败不得触发降级豁免
        when(writer.readCanonicalSidecar("1")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder()
                        .download(MediaPhaseState.builder().status(MediaPhaseStatus.FAILED_TERMINAL).build())
                        .build())));

        assertThat(gate.evaluate(article("tw-1")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
    }

    @Test
    void should_block_video_when_prepare_phase_actively_failed_despite_skip_mark() {
        // uploadStatus=SKIPPED 但 wechatPrepare 已进入失败/进行中状态: 不视为有意跳过
        when(writer.readCanonicalSidecar("failed")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder()
                        .wechatPrepare(MediaPhaseState.builder().status(MediaPhaseStatus.FAILED_TERMINAL).build())
                        .build())));
        when(writer.readCanonicalSidecar("running")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder()
                        .wechatPrepare(MediaPhaseState.builder().status(MediaPhaseStatus.IN_PROGRESS).build())
                        .build())));

        assertThat(gate.evaluate(article("tw-failed")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
        assertThat(gate.evaluate(article("tw-running")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
    }

    @Test
    void should_block_video_skipped_without_evidence_of_deliberate_degradation() {
        // 下载未成功即 SKIPPED / 缺跳过原因 / preparer 未运行 (PENDING): 均不放行
        when(writer.readCanonicalSidecar("not-downloaded")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder()
                        .download(MediaPhaseState.builder().status(MediaPhaseStatus.NOT_STARTED).build())
                        .build())));
        when(writer.readCanonicalSidecar("no-reason")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder().failureReason(" ").build())));
        when(writer.readCanonicalSidecar("preparer-not-run")).thenReturn(Optional.of(record(
                degradedVideo().toBuilder().uploadStatus(MediaUploadStatus.PENDING).build())));

        assertThat(gate.evaluate(article("tw-not-downloaded")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
        assertThat(gate.evaluate(article("tw-no-reason")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
        assertThat(gate.evaluate(article("tw-preparer-not-run")))
                .isEqualTo(ArticleMediaReadinessGate.GateResult.PHASE_INCOMPLETE);
    }

    @Test
    void should_allow_demonstrably_media_free_non_rewrite_article() {
        when(writer.readCanonicalSidecar("text")).thenReturn(Optional.empty());
        assertThat(gate.evaluate(article("tw-text"))).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
    }

    @Test
    void should_allow_plain_rewrite_without_sidecar() {
        Article article = article("tw-plain");
        article.setGenerationMode(ContentGenerationMode.REWRITE);

        assertThat(gate.evaluate(article)).isEqualTo(ArticleMediaReadinessGate.GateResult.READY);
    }

    private static Article article(String id) {
        return Article.builder().id(id).generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA).build();
    }

    private static TweetMedia photo(MediaPhaseStatus status) {
        MediaPhaseState phase = MediaPhaseState.builder().status(status).build();
        return TweetMedia.builder().id("p").type(TweetMediaType.PHOTO)
                .download(phase).wechatPrepare(phase).articleReference(phase).build();
    }

    /** 有意降级的 VIDEO: 下载成功 + 微信侧 SKIPPED (携带 8.2 原因) + 准备/引用两阶段未启动。 */
    private static TweetMedia degradedVideo() {
        MediaPhaseState download = MediaPhaseState.builder().status(MediaPhaseStatus.SUCCEEDED).build();
        return TweetMedia.builder().id("v").type(TweetMediaType.VIDEO)
                .download(download)
                .uploadStatus(MediaUploadStatus.SKIPPED)
                .failureReason("视频内嵌未证实，降级为预览图+原文链接 (video_embed_unverified, Story 8.2)")
                .build();
    }

    private static MediaArchiveRecord record(TweetMedia... media) {
        return MediaArchiveRecord.builder().media(List.of(media)).build();
    }
}
