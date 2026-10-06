package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
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

    private static MediaArchiveRecord record(TweetMedia... media) {
        return MediaArchiveRecord.builder().media(List.of(media)).build();
    }
}
