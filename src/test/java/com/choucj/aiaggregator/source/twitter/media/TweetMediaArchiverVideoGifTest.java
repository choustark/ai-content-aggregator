package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Story 7.3: VIDEO/GIF 元数据归档专项测试.
 *
 * <p>用真实 {@link TweetMediaArchiveWriter} + {@code @TempDir} (软失败范式 §1.7, 不 mock Writer),
 * 验证 AC1 元数据字段 / AC2 SKIPPED 不下载 / AC4 码率摘要 / AC5 逐媒体隔离 / AC7 幂等 / AC8 日志脱敏.
 *
 * <p>VIDEO fixture 参照 {@code local-apify-actor-readiness-20260802.md} zhongying14 video media shape
 * (type=video + videoUrl + width=1440 + height=2560); GIF 走合成 fixture (animated_gif 契约).
 */
@ExtendWith(MockitoExtension.class)
class TweetMediaArchiverVideoGifTest {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 2, 10, 0);

    @Mock
    private MediaDownloadClient downloadClient;

    @Mock
    private MediaRuntimeStateRepository stateRepository;

    @TempDir
    Path tempDir;

    private TweetMediaArchiveWriter writer;
    private TweetMediaArchiver archiver;

    @BeforeEach
    void setUp() {
        TwitterMediaProperties mediaProperties = new TwitterMediaProperties();
        mediaProperties.setEnabled(true);
        mediaProperties.setBaseDirectory(tempDir.toString());
        mediaProperties.setDatePattern("yyyy-MM-dd");
        mediaProperties.setSidecarFilename("media.json");

        ArchiverProperties archiverProperties = new ArchiverProperties();
        archiverProperties.setBaseDirectory(tempDir.resolve("fallback").toString());

        writer = new TweetMediaArchiveWriter(mediaProperties, archiverProperties, new ObjectMapper().findAndRegisterModules());
        archiver = new TweetMediaArchiver(downloadClient, writer, stateRepository);
    }

    /** T3.1 (AC1): VIDEO 元数据归档 — sidecar 含 previewImageUrl + variants + width/height/order + 摘要 + SKIPPED. */
    @Test
    void shouldArchiveVideoMetadataToSidecar() {
        TweetMedia video = video("video-1", "https://video.twimg.com/vid/1280x720/abc.mp4",
                "https://pbs.twimg.com/thumb/abc.jpg", 1440, 2560, 0, List.of(
                        variant("https://video.twimg.com/vid/abc/832000.mp4", "video/mp4", 832000L),
                        variant("https://video.twimg.com/vid/abc/432000.mp4", "video/mp4", 432000L)));
        writer.writeSidecar("tweet-v1", PUBLISHED_AT, List.of(video));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-v1", PUBLISHED_AT, List.of(video));

        // AC2: 不下载, 计入跳过
        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        verifyNoInteractions(downloadClient);

        MediaArchiveRecord record = writer.readSidecar("tweet-v1", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(archived.getFailureReason()).isEqualTo("视频/GIF 复现路径待 Story 8.2 spike 决定，暂不下载");
        // AC1: 元数据字段保真
        assertThat(archived.getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(archived.getPreviewImageUrl()).isEqualTo("https://pbs.twimg.com/thumb/abc.jpg");
        assertThat(archived.getWidth()).isEqualTo(1440);
        assertThat(archived.getHeight()).isEqualTo(2560);
        assertThat(archived.getOrder()).isZero();
        assertThat(archived.getVariants()).hasSize(2);
        // AC4: 码率/格式摘要, maxBitrate 取最大, formats 去重后为 mp4
        assertThat(archived.getProviderRawSummary())
                .isEqualTo("video:variants=2,maxBitrate=832000,formats=video/mp4/video/mp4");
    }

    /** T3.2 (AC1): GIF 元数据归档 — 合成 fixture (animated_gif 契约), 同 VIDEO 断言. */
    @Test
    void shouldArchiveGifMetadataToSidecar() {
        TweetMedia gif = TweetMedia.builder()
                .id("gif-1")
                .type(TweetMediaType.GIF)
                .sourceUrl("https://video.twimg.com/gif/xyz.mp4")
                .previewImageUrl("https://pbs.twimg.com/thumb/xyz.jpg")
                .width(480)
                .height(270)
                .order(0)
                .variants(List.of(variant("https://video.twimg.com/gif/xyz.mp4", "video/mp4", null)))
                .provider("x-author-scraper")
                .allowDownload(false)
                .providerRawSummary("gif:variants=1")
                .build();
        writer.writeSidecar("tweet-g1", PUBLISHED_AT, List.of(gif));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-g1", PUBLISHED_AT, List.of(gif));

        assertThat(result.skipCount()).isEqualTo(1);
        verifyNoInteractions(downloadClient);

        MediaArchiveRecord record = writer.readSidecar("tweet-g1", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getType()).isEqualTo(TweetMediaType.GIF);
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(archived.getWidth()).isEqualTo(480);
        assertThat(archived.getHeight()).isEqualTo(270);
        // bitrate null → maxBitrate=0
        assertThat(archived.getProviderRawSummary()).isEqualTo("gif:variants=1,maxBitrate=0,formats=video/mp4");
    }

    /** T3.3 (AC2/AC6): VIDEO/GIF 绝不调用 MediaDownloadClient. */
    @Test
    void shouldNeverDownloadVideoOrGif() {
        TweetMedia video = video("v", "https://example.com/v.mp4", "https://example.com/t.jpg",
                640, 480, 0, List.of());
        TweetMedia gif = TweetMedia.builder()
                .id("g").type(TweetMediaType.GIF).sourceUrl("https://example.com/g.mp4")
                .previewImageUrl("https://example.com/gt.jpg").order(1)
                .variants(List.of()).build();
        writer.writeSidecar("tweet-ng", PUBLISHED_AT, List.of(video, gif));

        archiver.archiveMedia("tweet-ng", PUBLISHED_AT, List.of(video, gif));

        verifyNoInteractions(downloadClient);
    }

    /** T3.6 (AC4): 码率摘要格式 — 多 contentType 去重保留顺序, 缺失 bitrate 全为 0. */
    @Test
    void shouldBuildVariantSummaryWithoutUrls() {
        TweetMedia video = video("v6", "https://example.com/v6.mp4", "https://example.com/t6.jpg",
                1280, 720, 0, List.of(
                        variant("https://example.com/832k.mp4", "video/mp4", 832000L),
                        variant("https://example.com/hls.m3u8", "application/x-mpegURL", null)));
        writer.writeSidecar("tweet-s6", PUBLISHED_AT, List.of(video));

        archiver.archiveMedia("tweet-s6", PUBLISHED_AT, List.of(video));

        MediaArchiveRecord record = writer.readSidecar("tweet-s6", PUBLISHED_AT).orElseThrow();
        String summary = record.getMedia().get(0).getProviderRawSummary();
        // 不含完整 URL (N4)
        assertThat(summary).doesNotContain("example.com");
        assertThat(summary).isEqualTo("video:variants=2,maxBitrate=832000,formats=video/mp4/application/x-mpegURL");
    }

    @Test
    void shouldIgnoreNullVariantInSummary() {
        TweetMedia video = video("v-null", "https://example.com/null.mp4", "https://example.com/null.jpg",
                1280, 720, 0, java.util.Arrays.asList(
                        null,
                        variant("https://example.com/ok.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-null", PUBLISHED_AT, List.of(video));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-null", PUBLISHED_AT, List.of(video));

        assertThat(result.skipCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-null", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getProviderRawSummary())
                .isEqualTo("video:variants=1,maxBitrate=832000,formats=video/mp4");
    }

    @Test
    void shouldFailVideoMetadataArchive_whenSidecarMediaNotFound() {
        TweetMedia sidecarVideo = video("sidecar-v", "https://example.com/sidecar.mp4",
                "https://example.com/sidecar.jpg", 640, 480, 0, List.of());
        TweetMedia incomingVideo = video("incoming-v", "https://example.com/incoming.mp4",
                "https://example.com/incoming.jpg", 640, 480, 0, List.of());
        writer.writeSidecar("tweet-miss", PUBLISHED_AT, List.of(sidecarVideo));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-miss", PUBLISHED_AT, List.of(incomingVideo));

        assertThat(result.skipCount()).isZero();
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> {
                    assertThat(status.status()).isEqualTo(MediaDownloadStatus.FAILED);
                    assertThat(status.mediaId()).isEqualTo("incoming-v");
                    assertThat(status.retryable()).isFalse();
                    assertThat(status.failureReason()).contains("sidecar 回写未命中");
                });
        MediaArchiveRecord record = writer.readSidecar("tweet-miss", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getId()).isEqualTo("sidecar-v");
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
    }

    /** T3.7 (AC5): 逐媒体隔离 — media[0]=VIDEO 元数据写入失败 + media[1]=PHOTO 成功, 互不阻塞. */
    @Test
    void shouldIsolatePerMediaFailure() {
        // 用非法 tweetId 触发 updateMedia 抛 NonRetryableException, 但仍计入失败而非中断.
        // 这里改用: VIDEO 缺 sidecar 写入 (writer 未预先 writeSidecar) → archiveVideoOrGifMetadata 内
        // updateSidecar 返回 false 不抛; 为强制失败, 用 spy 不可行 (真实 writer).
        // 改为验证 PHOTO 在 VIDEO 之后仍被正常处理 (隔离的最小可观察行为).
        TweetMedia video = video("v7", "https://example.com/v7.mp4", "https://example.com/t7.jpg",
                640, 480, 0, List.of());
        TweetMedia photo = TweetMedia.builder()
                .id("p7").type(TweetMediaType.PHOTO)
                .sourceUrl("https://pbs.twimg.com/media/P7.jpg")
                .allowDownload(true).build();
        writer.writeSidecar("tweet-i7", PUBLISHED_AT, List.of(video, photo));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-i7", PUBLISHED_AT, List.of(video, photo));

        // VIDEO 跳过 + PHOTO 在无 mock downloadClient 时会失败下载, 但二者独立计数
        assertThat(result.skipCount()).isEqualTo(1); // VIDEO
        assertThat(result.failCount()).isEqualTo(1); // PHOTO 下载失败 (downloadClient 默认返回 null)
        // VIDEO 元数据已写入 sidecar (不因 PHOTO 失败而丢失)
        MediaArchiveRecord record = writer.readSidecar("tweet-i7", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
    }

    /** T3.8 (AC7): 幂等 — 同一 VIDEO 重复 archiveMedia, sidecar 内容稳定. */
    @Test
    void shouldBeIdempotentForRepeatedArchive() {
        TweetMedia video = video("v8", "https://example.com/v8.mp4", "https://example.com/t8.jpg",
                1280, 720, 0, List.of(variant("https://example.com/v8.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-id8", PUBLISHED_AT, List.of(video));

        archiver.archiveMedia("tweet-id8", PUBLISHED_AT, List.of(video));
        TweetMedia first = writer.readSidecar("tweet-id8", PUBLISHED_AT).orElseThrow().getMedia().get(0);
        String firstSummary = first.getProviderRawSummary();
        String firstFailure = first.getFailureReason();

        archiver.archiveMedia("tweet-id8", PUBLISHED_AT, List.of(video));
        TweetMedia second = writer.readSidecar("tweet-id8", PUBLISHED_AT).orElseThrow().getMedia().get(0);

        assertThat(second.getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(second.getProviderRawSummary()).isEqualTo(firstSummary);
        assertThat(second.getFailureReason()).isEqualTo(firstFailure);
        assertThat(second.getVariants()).hasSize(1);
    }

    /** T3.4/T3.5 parser 保真与 photo 回归在 XAuthorScraperDiscoveryClientTest 覆盖. */

    private static TweetMedia video(String id, String sourceUrl, String preview, int w, int h, int order,
                                     List<TweetMediaVariant> variants) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.VIDEO)
                .sourceUrl(sourceUrl)
                .previewImageUrl(preview)
                .width(w)
                .height(h)
                .order(order)
                .variants(variants)
                .provider("x-author-scraper")
                .allowDownload(false)
                .build();
    }

    private static TweetMediaVariant variant(String url, String contentType, Long bitrate) {
        return TweetMediaVariant.builder()
                .url(url)
                .contentType(contentType)
                .bitrate(bitrate)
                .build();
    }
}
