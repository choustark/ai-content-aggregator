package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.observability.SlowOperationProperties;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaConfig;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 7.2: TweetMediaArchiver 使用真实 sidecar writer 的归档测试.
 */
@ExtendWith(MockitoExtension.class)
class TweetMediaArchiverTest {

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

    @Test
    void shouldDownloadPhotoAndUpdateSidecar() throws Exception {
        TweetMedia photo = photo("photo-1", "https://pbs.twimg.com/media/ABC.jpg");
        writer.writeSidecar("tweet-1", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-1"), eq("photo-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-1", PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> {
                    assertThat(status.mediaId()).isEqualTo("photo-1");
                    assertThat(status.status()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
                    assertThat(status.retryable()).isFalse();
                    assertThat(status.localPath()).startsWith("media/twitter/2026-08-02/tweet-1/photo-1-");
                });
        MediaArchiveRecord record = writer.readSidecar("tweet-1", PUBLISHED_AT).orElseThrow();
        TweetMedia updated = record.getMedia().get(0);
        assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(updated.getLocalPath()).startsWith("media/twitter/2026-08-02/tweet-1/photo-1-");
        assertThat(Files.exists(tempDir.resolve(updated.getLocalPath()))).isTrue();
        // Story 10.8: 成功直写三阶段 download=SUCCEEDED (幂等跳过的权威依据)
        assertThat(updated.getDownload()).isNotNull();
        assertThat(updated.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
    }

    /**
     * Story 10.8 (I/O 矩阵「已校验文件重复运行」): download=SUCCEEDED 且文件存在非零 →
     * 幂等跳过 — 0 次网络下载, sidecar 与归档文件不变 (不二次回写 download 阶段)。
     */
    @Test
    void shouldSkipRedownload_whenFileNonZeroAndDownloadPhaseSucceeded() throws Exception {
        TweetMedia photo = photo("photo-idem", "https://pbs.twimg.com/media/IDEM.jpg");
        writer.writeSidecar("tweet-idem", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-idem"), eq("photo-idem")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        archiver.archiveMedia("tweet-idem", PUBLISHED_AT, List.of(photo));
        MediaArchiveRecord first = writer.readSidecar("tweet-idem", PUBLISHED_AT).orElseThrow();
        String firstPhaseUpdatedAt = String.valueOf(first.getMedia().get(0).getDownload().getUpdatedAt());

        TweetMediaArchiver.ArchiveResult second =
                archiver.archiveMedia("tweet-idem", PUBLISHED_AT, List.of(photo));

        assertThat(second.successCount()).isEqualTo(1);
        // 双校验通过 → 0 次重复下载
        verify(downloadClient, times(1))
                .downloadBinary(eq(photo.getSourceUrl()), eq("tweet-idem"), eq("photo-idem"));
        // sidecar download 阶段不被二次回写 (权威状态不变)
        MediaArchiveRecord afterSkip = writer.readSidecar("tweet-idem", PUBLISHED_AT).orElseThrow();
        assertThat(String.valueOf(afterSkip.getMedia().get(0).getDownload().getUpdatedAt()))
                .isEqualTo(firstPhaseUpdatedAt);
    }

    /**
     * Story 10.8 (双校验另一半): 文件存在非零但 sidecar download 阶段非 SUCCEEDED →
     * 必须重新下载并校验 (文件存在不是唯一权威)。
     */
    @Test
    void shouldRedownload_whenFileExistsButDownloadPhaseNotSucceeded() {
        TweetMedia photo = photo("photo-redown", "https://pbs.twimg.com/media/REDOWN.jpg");
        writer.writeSidecar("tweet-redown", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-redown"), eq("photo-redown")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));
        archiver.archiveMedia("tweet-redown", PUBLISHED_AT, List.of(photo));

        // 模拟阶段证据缺失/被重置: 文件仍在, download 阶段回 NOT_STARTED
        // (须同时降 downloadStatus, 否则 10.7 兼容投影会把 DOWNLOADED 读回 SUCCEEDED)
        writer.updateMedia("tweet-redown", PUBLISHED_AT, "photo-redown",
                original -> original.toBuilder()
                        .download(MediaPhaseState.notStarted())
                        .downloadStatus(MediaDownloadStatus.PENDING)
                        .build());

        TweetMediaArchiver.ArchiveResult second =
                archiver.archiveMedia("tweet-redown", PUBLISHED_AT, List.of(photo));

        assertThat(second.successCount()).isEqualTo(1);
        verify(downloadClient, times(2))
                .downloadBinary(eq(photo.getSourceUrl()), eq("tweet-redown"), eq("photo-redown"));
        MediaArchiveRecord record = writer.readSidecar("tweet-redown", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
    }

    /**
     * Story 10.8 (I/O 矩阵「下载内容损坏」): 魔数校验失败按下载失败处理 —
     * FAILED_TERMINAL / attempt=1 / nextRetryAt=null + downloadStatus=FAILED, 不自动重试,
     * 且失败发生在落盘前 (归档目录无图片文件)。
     */
    @Test
    void shouldFailTerminal_whenDownloadedBytesNotImageMagic() throws Exception {
        TweetMedia photo = photo("photo-bad", "https://pbs.twimg.com/media/BAD.jpg");
        writer.writeSidecar("tweet-bad", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-bad"), eq("photo-bad")))
                .thenReturn(new MediaDownloadClient.DownloadResult(
                        "<html>not an image</html>".getBytes(), "text/html"));

        TweetMediaArchiver.ArchiveResult result =
                archiver.archiveMedia("tweet-bad", PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> assertThat(status.retryable()).isFalse());
        MediaArchiveRecord record = writer.readSidecar("tweet-bad", PUBLISHED_AT).orElseThrow();
        TweetMedia updated = record.getMedia().get(0);
        assertThat(updated.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(updated.getDownload().getAttempt()).isEqualTo(1);
        assertThat(updated.getDownload().getNextRetryAt()).isNull();
        assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
        // 先校验后落盘: 无图片文件/临时文件残留
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-bad");
        try (var files = Files.list(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".jpg") || name.endsWith(".tmp"));
        }
    }

    /** Story 10.8: 图片魔数识别 — JPEG/PNG/WebP 通过, 非图片/过短/null 拒绝。 */
    @Test
    void hasImageMagicNumber_detectsJpegPngWebpAndRejectsGarbage() {
        byte[] jpeg = new byte[12];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        assertThat(TweetMediaArchiver.hasImageMagicNumber(jpeg)).isTrue();

        byte[] png = new byte[12];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        assertThat(TweetMediaArchiver.hasImageMagicNumber(png)).isTrue();

        byte[] webp = new byte[12];
        webp[0] = 'R';
        webp[1] = 'I';
        webp[2] = 'F';
        webp[3] = 'F';
        webp[8] = 'W';
        webp[9] = 'E';
        webp[10] = 'B';
        webp[11] = 'P';
        assertThat(TweetMediaArchiver.hasImageMagicNumber(webp)).isTrue();

        assertThat(TweetMediaArchiver.hasImageMagicNumber("<html></html>".getBytes())).isFalse();
        assertThat(TweetMediaArchiver.hasImageMagicNumber(new byte[]{1, 2, 3})).isFalse();
        assertThat(TweetMediaArchiver.hasImageMagicNumber(new byte[5])).isFalse();
        assertThat(TweetMediaArchiver.hasImageMagicNumber(null)).isFalse();
    }

    /**
     * 真实生产管道路径修复 (Story 9.1 真实环境缺陷): 推文首次处理时 sidecar 从不存在,
     * archiveMedia 必须先用推文媒体列表种子化 media.json, 否则后续 updateMedia 全部
     * "未找到 sidecar, 跳过" → gate/preparer 链雪崩 (media sidecar missing after prepareMedia)。
     * 既有测试均先 writeSidecar 预置 fixture, 掩盖了该缺陷 — 本测试不预置。
     */
    @Test
    void shouldSeedSidecarWithTweetMediaList_whenSidecarAbsent() throws Exception {
        TweetMedia photo = photo("photo-seed-1", "https://pbs.twimg.com/media/SEED.jpg");
        assertThat(writer.readSidecar("tweet-seed", PUBLISHED_AT)).isEmpty();
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-seed"), eq("photo-seed-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-seed", PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-seed", PUBLISHED_AT).orElseThrow();
        assertThat(record.getTweetId()).isEqualTo("tweet-seed");
        assertThat(record.getMedia()).singleElement().satisfies(updated -> {
            assertThat(updated.getId()).isEqualTo("photo-seed-1");
            assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
            assertThat(updated.getLocalPath()).startsWith("media/twitter/2026-08-02/tweet-seed/photo-seed-1-");
        });
        assertThat(Files.exists(tempDir.resolve(record.getMedia().get(0).getLocalPath()))).isTrue();
    }

    /**
     * 幂等种子化: sidecar 已存在 (重试场景) 时不得覆盖 — 否则既有 uploadStatus/wechatUrl
     * 等上传进度会被重置 (烧微信配额重传)。
     */
    @Test
    void shouldNotOverwriteExistingSidecar_whenRetrying() throws Exception {
        TweetMedia photo = photo("photo-retry-1", "https://pbs.twimg.com/media/RETRY.jpg")
                .toBuilder()
                .uploadStatus(com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus.UPLOADED)
                .wechatUrl("https://mmbiz.qpic.cn/existing")
                .build();
        writer.writeSidecar("tweet-retry", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-retry"), eq("photo-retry-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        archiver.archiveMedia("tweet-retry", PUBLISHED_AT, List.of(photo));

        MediaArchiveRecord record = writer.readSidecar("tweet-retry", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia()).singleElement().satisfies(updated -> {
            // 下载回写只覆盖 downloadStatus/localPath, 上传进度字段必须保留
            assertThat(updated.getUploadStatus())
                    .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus.UPLOADED);
            assertThat(updated.getWechatUrl()).isEqualTo("https://mmbiz.qpic.cn/existing");
            assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        });
    }

    @Test
    void shouldPersistSyntheticId_whenMediaIdIsNull() {
        TweetMedia photo = photo(null, "https://pbs.twimg.com/media/XYZ.jpg");
        writer.writeSidecar("tweet-2", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-2"), eq("tweet-2:0:photo")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-2", PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-2", PUBLISHED_AT).orElseThrow();
        TweetMedia updated = record.getMedia().get(0);
        assertThat(updated.getId()).isEqualTo("tweet-2:0:photo");
        assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(updated.getLocalPath()).contains("tweet-2_0_photo-");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(stateRepository).saveSnapshot(eq("tweet-2"), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getMediaId()).isEqualTo("tweet-2:0:photo");
                    assertThat(item.getType()).isEqualTo(TweetMediaType.PHOTO);
                });
    }

    @Test
    void shouldDisambiguateSanitizedFilenameCollisions() {
        TweetMedia first = photo("a/b", "https://pbs.twimg.com/media/A.jpg");
        TweetMedia second = photo("a.b", "https://pbs.twimg.com/media/B.jpg");
        writer.writeSidecar("tweet-3", PUBLISHED_AT, List.of(first, second));
        when(downloadClient.downloadBinary(eq(first.getSourceUrl()), eq("tweet-3"), eq("a/b")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));
        when(downloadClient.downloadBinary(eq(second.getSourceUrl()), eq("tweet-3"), eq("a.b")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-3", PUBLISHED_AT, List.of(first, second));

        assertThat(result.successCount()).isEqualTo(2);
        MediaArchiveRecord record = writer.readSidecar("tweet-3", PUBLISHED_AT).orElseThrow();
        String firstPath = record.getMedia().get(0).getLocalPath();
        String secondPath = record.getMedia().get(1).getLocalPath();
        assertThat(firstPath).isNotEqualTo(secondPath);
        assertThat(Files.exists(tempDir.resolve(firstPath))).isTrue();
        assertThat(Files.exists(tempDir.resolve(secondPath))).isTrue();
    }

    @Test
    void shouldReturnRetryabilityPerFailedMedia() {
        TweetMedia retryable = photo("retry", "https://example.com/retry.jpg");
        TweetMedia permanent = photo("permanent", "https://example.com/permanent.jpg");
        writer.writeSidecar("tweet-4", PUBLISHED_AT, List.of(retryable, permanent));
        when(downloadClient.downloadBinary(eq(retryable.getSourceUrl()), eq("tweet-4"), eq("retry")))
                .thenThrow(new RetryableException("临时失败"));
        when(downloadClient.downloadBinary(eq(permanent.getSourceUrl()), eq("tweet-4"), eq("permanent")))
                .thenThrow(new NonRetryableException("永久失败"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-4", PUBLISHED_AT, List.of(retryable, permanent));

        assertThat(result.failCount()).isEqualTo(2);
        assertThat(result.mediaStatuses())
                .extracting(TweetMediaArchiver.MediaArchiveStatus::retryable)
                .containsExactly(true, false);
    }

    /**
     * Story 10.10: VIDEO 走严格下载主链路 — variant 选择 + ftyp 校验落盘 + download=SUCCEEDED
     * + 证据字段 (原 Story 7.3 元数据跳过行为已由 VIDEO 下载化取代)。
     */
    @Test
    void shouldDownloadVideoAndUpdateSidecar() {
        TweetMedia video = TweetMedia.builder()
                .id("video-1")
                .type(TweetMediaType.VIDEO)
                .sourceUrl("https://example.com/video.mp4")
                .previewImageUrl("https://example.com/thumb.jpg")
                .width(1440)
                .height(2560)
                .order(0)
                .variants(List.of(
                        TweetMediaVariant.builder()
                                .url("https://example.com/video.mp4")
                                .contentType("video/mp4")
                                .bitrate(832000L)
                                .width(1440)
                                .height(2560)
                                .build()))
                .build();
        writer.writeSidecar("tweet-5", PUBLISHED_AT, List.of(video));
        byte[] mp4Bytes = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        when(downloadClient.downloadBinary(eq("https://example.com/video.mp4"), eq("tweet-5"), eq("video-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes, "video/mp4"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-5", PUBLISHED_AT, List.of(video));

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skipCount()).isZero();
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> {
                    assertThat(status.status()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
                    assertThat(status.localPath()).startsWith("media/twitter/2026-08-02/tweet-5/video-1-");
                });
        MediaArchiveRecord record = writer.readSidecar("tweet-5", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        // 证据字段 (Story 10.10)
        assertThat(archived.getFileSizeBytes()).isEqualTo((long) mp4Bytes.length);
        assertThat(archived.getDownloadedContentType()).isEqualTo("video/mp4");
        // 元数据保留
        assertThat(archived.getPreviewImageUrl()).isEqualTo("https://example.com/thumb.jpg");
        assertThat(archived.getWidth()).isEqualTo(1440);
        assertThat(archived.getHeight()).isEqualTo(2560);
        assertThat(archived.getVariants()).hasSize(1);
        // 码率/格式摘要写入 providerRawSummary, 不含 URL
        assertThat(archived.getProviderRawSummary()).isEqualTo(
                "video:variants=1,maxBitrate=832000,formats=video/mp4");
        // 归档文件真实落盘
        assertThat(Files.exists(tempDir.resolve(archived.getLocalPath()))).isTrue();
    }

    @Test
    void shouldNotRegisterArchiver_whenMediaDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TwitterMediaConfig.class))
                .withUserConfiguration(TweetMediaArchiverTestConfig.class)
                .withBean(TweetMediaArchiveWriter.class, () -> writer)
                .withPropertyValues("twitter.media.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TweetMediaArchiver.class);
                    // Story 7.4 T5.8 (AC7): 运行时状态/恢复服务同生同灭
                    assertThat(context).doesNotHaveBean(MediaRuntimeStateRepository.class);
                    assertThat(context).doesNotHaveBean(MediaRuntimeRecoveryService.class);
                });
    }

    @Test
    void shouldRegisterArchiver_whenMediaEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TwitterMediaConfig.class))
                .withUserConfiguration(TweetMediaArchiverTestConfig.class)
                .withBean(TweetMediaArchiveWriter.class, () -> writer)
                .withBean(MediaRuntimeStateRepository.class,
                        () -> new MediaRuntimeStateRepository(org.mockito.Mockito.mock(
                                com.choucj.aiaggregator.common.repository.RedisRepository.class)))
                .withPropertyValues(
                        "twitter.media.enabled=true",
                        "twitter.media.download-timeout-seconds=30",
                        "twitter.media.max-file-size-mb=10")
                .run(context -> {
                    assertThat(context).hasSingleBean(TweetMediaArchiver.class);
                    // Story 7.4 T5.8 (AC7): 开关开启时运行时状态仓库同注册
                    assertThat(context).hasSingleBean(MediaRuntimeStateRepository.class);
                });
    }

    /** Story 7.4 T5.4 (AC1/AC2): archiveMedia 完成后 saveSnapshot 被调用, 快照含正确状态计数. */
    @Test
    void shouldSaveRuntimeSnapshotAfterArchiveMedia() {
        TweetMedia photo = photo("photo-r1", "https://example.com/r1.jpg");
        TweetMedia video = TweetMedia.builder()
                .id("video-r1").type(TweetMediaType.VIDEO)
                .sourceUrl("https://example.com/v.mp4")
                .previewImageUrl("https://example.com/t.jpg")
                .variants(List.of())
                .build();
        writer.writeSidecar("tweet-r1", PUBLISHED_AT, List.of(photo, video));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-r1"), eq("photo-r1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-r1", PUBLISHED_AT, List.of(photo, video));

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(stateRepository, org.mockito.Mockito.times(2)).saveSnapshot(eq("tweet-r1"), captor.capture());
        List<com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem> snapshot =
                captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(snapshot).hasSize(2);
        // PHOTO: DOWNLOADED + localPath + type 对齐
        assertThat(snapshot).anySatisfy(item -> {
            assertThat(item.getMediaId()).isEqualTo("photo-r1");
            assertThat(item.getType()).isEqualTo(TweetMediaType.PHOTO);
            assertThat(item.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
            assertThat(item.getLocalPath()).startsWith("media/twitter/2026-08-02/tweet-r1/");
        });
        // Story 10.10: VIDEO 无合法候选 (variants 空) → FAILED + 终态失败原因
        assertThat(snapshot).anySatisfy(item -> {
            assertThat(item.getMediaId()).isEqualTo("video-r1");
            assertThat(item.getType()).isEqualTo(TweetMediaType.VIDEO);
            assertThat(item.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
            assertThat(item.getFailureReason()).contains("无合法 mp4 下载候选");
        });
    }

    /** Story 7.4 review patch: 每个媒体处理完成后增量写快照, 不只在循环结束写一次. */
    @Test
    void shouldSaveRuntimeSnapshotAfterEachMediaProcessed() {
        TweetMedia first = photo("p-inc-1", "https://example.com/inc1.jpg");
        TweetMedia second = photo("p-inc-2", "https://example.com/inc2.jpg");
        writer.writeSidecar("tweet-inc", PUBLISHED_AT, List.of(first, second));
        when(downloadClient.downloadBinary(eq(first.getSourceUrl()), eq("tweet-inc"), eq("p-inc-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));
        when(downloadClient.downloadBinary(eq(second.getSourceUrl()), eq("tweet-inc"), eq("p-inc-2")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));

        archiver.archiveMedia("tweet-inc", PUBLISHED_AT, List.of(first, second));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(stateRepository, org.mockito.Mockito.times(2)).saveSnapshot(eq("tweet-inc"), captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(1);
        assertThat(captor.getAllValues().get(1)).hasSize(2);
    }

    /** Story 7.4 review patch: 空媒体列表也写入空快照, 避免旧 Redis 快照残留. */
    @Test
    void shouldSaveEmptyRuntimeSnapshot_whenMediaListIsEmpty() {
        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-empty", PUBLISHED_AT, List.of());

        assertThat(result).isEqualTo(TweetMediaArchiver.ArchiveResult.empty());
        verify(stateRepository).saveSnapshot(eq("tweet-empty"), eq(List.of()));
    }

    /**
     * Story 7.4 T5.4 (AC5/AC9): Redis 宕机 (saveSnapshot 抛异常) 不阻塞归档 —
     * archiveMedia 仍返回正确结果且 sidecar 仍写成功.
     *
     * <p>注: saveSnapshot 内部软失败; 本测试通过让 mock stateRepository.saveSnapshot 直接抛,
     * 验证 TweetMediaArchiver 不因快照回写失败而中断 (双保险: 即使 Repository 软失败逻辑被改坏,
     * archiveMedia 也不崩).
     */
    @Test
    void shouldNotBlockArchiveWhenSnapshotSaveFails() {
        TweetMedia photo = photo("photo-r2", "https://example.com/r2.jpg");
        writer.writeSidecar("tweet-r2", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-r2"), eq("photo-r2")))
                .thenReturn(new MediaDownloadClient.DownloadResult(jpegBytes(), "image/jpeg"));
        org.mockito.Mockito.doThrow(new RetryableException("Redis 连接失败"))
                .when(stateRepository).saveSnapshot(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList());

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-r2", PUBLISHED_AT, List.of(photo));

        // 归档结果不受 Redis 影响
        assertThat(result.successCount()).isEqualTo(1);
        // sidecar 仍写成功 (AC2: 本地归档信息不丢)
        MediaArchiveRecord record = writer.readSidecar("tweet-r2", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(java.nio.file.Files.exists(tempDir.resolve(record.getMedia().get(0).getLocalPath()))).isTrue();
    }

    @Import(TweetMediaArchiver.class)
    static class TweetMediaArchiverTestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        DependencyMetrics dependencyMetrics(MeterRegistry meterRegistry) {
            return new DependencyMetrics(meterRegistry);
        }

        @Bean
        SlowOperationRecorder slowOperationRecorder(DependencyMetrics dependencyMetrics) {
            return new SlowOperationRecorder(dependencyMetrics, new SlowOperationProperties());
        }
    }

    private static TweetMedia photo(String id, String sourceUrl) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl(sourceUrl)
                .allowDownload(true)
                .build();
    }

    /** Story 10.8: 合法 JPEG 魔数 fixture (下载内容校验要求 JPEG/PNG/WebP 魔数, ≥12 字节). */
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 'J', 'F', 'I', 'F', 0, 1};
    }
}
