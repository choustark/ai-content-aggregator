package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaConfig;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        archiver = new TweetMediaArchiver(downloadClient, writer);
    }

    @Test
    void shouldDownloadPhotoAndUpdateSidecar() throws Exception {
        TweetMedia photo = photo("photo-1", "https://pbs.twimg.com/media/ABC.jpg");
        writer.writeSidecar("tweet-1", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-1"), eq("photo-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(new byte[]{1, 2, 3}, "image/jpeg"));

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
    }

    @Test
    void shouldPersistSyntheticId_whenMediaIdIsNull() {
        TweetMedia photo = photo(null, "https://pbs.twimg.com/media/XYZ.jpg");
        writer.writeSidecar("tweet-2", PUBLISHED_AT, List.of(photo));
        when(downloadClient.downloadBinary(eq(photo.getSourceUrl()), eq("tweet-2"), eq("tweet-2:0:photo")))
                .thenReturn(new MediaDownloadClient.DownloadResult(new byte[]{1}, "image/jpeg"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-2", PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-2", PUBLISHED_AT).orElseThrow();
        TweetMedia updated = record.getMedia().get(0);
        assertThat(updated.getId()).isEqualTo("tweet-2:0:photo");
        assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(updated.getLocalPath()).contains("tweet-2_0_photo-");
    }

    @Test
    void shouldDisambiguateSanitizedFilenameCollisions() {
        TweetMedia first = photo("a/b", "https://pbs.twimg.com/media/A.jpg");
        TweetMedia second = photo("a.b", "https://pbs.twimg.com/media/B.jpg");
        writer.writeSidecar("tweet-3", PUBLISHED_AT, List.of(first, second));
        when(downloadClient.downloadBinary(eq(first.getSourceUrl()), eq("tweet-3"), eq("a/b")))
                .thenReturn(new MediaDownloadClient.DownloadResult(new byte[]{1}, "image/jpeg"));
        when(downloadClient.downloadBinary(eq(second.getSourceUrl()), eq("tweet-3"), eq("a.b")))
                .thenReturn(new MediaDownloadClient.DownloadResult(new byte[]{2}, "image/jpeg"));

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

    @Test
    void shouldSkipNonPhotoWithSpecifiedReason() {
        TweetMedia video = TweetMedia.builder()
                .id("video-1")
                .type(TweetMediaType.VIDEO)
                .sourceUrl("https://example.com/video.mp4")
                .build();
        writer.writeSidecar("tweet-5", PUBLISHED_AT, List.of(video));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-5", PUBLISHED_AT, List.of(video));

        assertThat(result.skipCount()).isEqualTo(1);
        verify(downloadClient, never()).downloadBinary(eq(video.getSourceUrl()), eq("tweet-5"), eq("video-1"));
        MediaArchiveRecord record = writer.readSidecar("tweet-5", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(record.getMedia().get(0).getFailureReason()).isEqualTo("非 PHOTO 类型，跳过下载（Story 7.3 处理）");
    }

    @Test
    void shouldNotRegisterArchiver_whenMediaDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TwitterMediaConfig.class))
                .withUserConfiguration(TweetMediaArchiverTestConfig.class)
                .withBean(TweetMediaArchiveWriter.class, () -> writer)
                .withPropertyValues("twitter.media.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TweetMediaArchiver.class));
    }

    @Test
    void shouldRegisterArchiver_whenMediaEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TwitterMediaConfig.class))
                .withUserConfiguration(TweetMediaArchiverTestConfig.class)
                .withBean(TweetMediaArchiveWriter.class, () -> writer)
                .withPropertyValues(
                        "twitter.media.enabled=true",
                        "twitter.media.download-timeout-seconds=30",
                        "twitter.media.max-file-size-mb=10")
                .run(context -> assertThat(context).hasSingleBean(TweetMediaArchiver.class));
    }

    @Import(TweetMediaArchiver.class)
    static class TweetMediaArchiverTestConfig {
    }

    private static TweetMedia photo(String id, String sourceUrl) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl(sourceUrl)
                .allowDownload(true)
                .build();
    }
}
