package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 10.10: VIDEO variant 确定性选择器纯函数单测.
 *
 * <p>覆盖: MIME 过滤 (仅 video/mp4) / bitrate 降序 (null 最后) / URL 字典序 tie-break /
 * 列表顺序无关 / null 与非法元素防御 / 空与 null 输入。
 */
class VideoVariantSelectorTest {

    @Test
    void shouldSelectHighestBitrateMp4() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(List.of(
                variant("https://example.com/320k.mp4", "video/mp4", 320000L),
                variant("https://example.com/832k.mp4", "video/mp4", 832000L),
                variant("https://example.com/432k.mp4", "video/mp4", 432000L)));

        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/832k.mp4");
    }

    @Test
    void shouldFilterNonMp4Mime() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(List.of(
                variant("https://example.com/hls.m3u8", "application/x-mpegURL", 2_000_000L),
                variant("https://example.com/webm", "video/webm", 1_500_000L),
                variant("https://example.com/432k.mp4", "video/mp4", 432000L)));

        // 高码率 HLS/webm 不得作为候选, 也不得回退
        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/432k.mp4");
    }

    @Test
    void shouldReturnEmpty_whenNoMp4Candidate() {
        assertThat(VideoVariantSelector.select(List.of(
                variant("https://example.com/hls.m3u8", "application/x-mpegURL", null),
                variant("https://example.com/webm", "video/webm", null)))).isEmpty();
    }

    @Test
    void shouldReturnEmpty_whenVariantsNullOrEmpty() {
        assertThat(VideoVariantSelector.select(null)).isEmpty();
        assertThat(VideoVariantSelector.select(List.of())).isEmpty();
    }

    @Test
    void shouldSortNullBitrateLast() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(List.of(
                variant("https://example.com/a.mp4", "video/mp4", null),
                variant("https://example.com/b.mp4", "video/mp4", 100L)));

        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/b.mp4");

        // 全部 null bitrate → URL 字典序最小者
        Optional<TweetMediaVariant> allNull = VideoVariantSelector.select(List.of(
                variant("https://example.com/z.mp4", "video/mp4", null),
                variant("https://example.com/a.mp4", "video/mp4", null)));
        assertThat(allNull).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/a.mp4");
    }

    @Test
    void shouldTieBreakSameBitrateByLexicographicUrl() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(List.of(
                variant("https://example.com/b.mp4", "video/mp4", 832000L),
                variant("https://example.com/a.mp4", "video/mp4", 832000L)));

        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/a.mp4");
    }

    @Test
    void shouldBeIndependentOfListOrder() {
        List<TweetMediaVariant> forward = List.of(
                variant("https://example.com/low.mp4", "video/mp4", 100L),
                variant("https://example.com/mid.mp4", "video/mp4", 300L),
                variant("https://example.com/high.mp4", "video/mp4", 900L),
                variant("https://example.com/hls.m3u8", "application/x-mpegURL", 5_000_000L));
        List<TweetMediaVariant> reversed = List.of(
                variant("https://example.com/hls.m3u8", "application/x-mpegURL", 5_000_000L),
                variant("https://example.com/high.mp4", "video/mp4", 900L),
                variant("https://example.com/mid.mp4", "video/mp4", 300L),
                variant("https://example.com/low.mp4", "video/mp4", 100L));

        assertThat(VideoVariantSelector.select(forward))
                .isEqualTo(VideoVariantSelector.select(reversed));
    }

    @Test
    void shouldIgnoreNullElementsAndBlankUrlsAndNullContentType() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(Arrays.asList(
                null,
                variant(null, "video/mp4", 900L),
                variant("https://example.com/blank.mp4", " ", 900L),
                variant("https://example.com/nomime.mp4", null, 900L),
                variant("https://example.com/ok.mp4", "video/mp4", 100L)));

        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/ok.mp4");
    }

    @Test
    void shouldTolerateMimeCaseAndWhitespace() {
        Optional<TweetMediaVariant> selected = VideoVariantSelector.select(List.of(
                variant("https://example.com/v.mp4", " VIDEO/MP4 ", 100L)));

        assertThat(selected).map(TweetMediaVariant::getUrl)
                .contains("https://example.com/v.mp4");
    }

    private static TweetMediaVariant variant(String url, String contentType, Long bitrate) {
        return TweetMediaVariant.builder()
                .url(url)
                .contentType(contentType)
                .bitrate(bitrate)
                .build();
    }
}
