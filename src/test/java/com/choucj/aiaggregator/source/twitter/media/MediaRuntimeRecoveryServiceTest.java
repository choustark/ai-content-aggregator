package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeState;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Story 7.4: MediaRuntimeRecoveryService 断点恢复测试.
 *
 * <p>软失败范式 §1.7: 真实 RecoveryService + mock MediaRuntimeStateRepository +
 * 真实 TweetMediaArchiveWriter + @TempDir (sidecar fallback 与文件校验用真实 IO).
 */
@ExtendWith(MockitoExtension.class)
class MediaRuntimeRecoveryServiceTest {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 2, 10, 0);

    @Mock
    private MediaRuntimeStateRepository stateRepository;

    @TempDir
    Path tempDir;

    private TweetMediaArchiveWriter writer;
    private MediaRuntimeRecoveryService service;

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
        service = new MediaRuntimeRecoveryService(stateRepository, writer);
    }

    /** T5.6 (AC3): Redis hit → 返回 Redis 快照. */
    @Test
    void shouldReturnRedisSnapshotOnHit() {
        MediaRuntimeState snapshot = MediaRuntimeState.builder()
                .tweetId("t-redis")
                .mediaStates(List.of(
                        item("m1", MediaDownloadStatus.DOWNLOADED, "media/twitter/2026-08-02/t-redis/f.jpg"),
                        item("m2", MediaDownloadStatus.SKIPPED, null)))
                .build();
        when(stateRepository.getSnapshot("t-redis")).thenReturn(Optional.of(snapshot));

        List<MediaRuntimeItem> states = service.getMediaStates("t-redis", PUBLISHED_AT);

        assertThat(states).hasSize(2);
        assertThat(states.get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(states.get(1).getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
    }

    /** T5.6 (AC3/AC5): Redis 异常 → fallback sidecar 重建 (真实 Writer + @TempDir). */
    @Test
    void shouldFallbackToSidecarWhenRedisThrows() {
        TweetMedia downloaded = TweetMedia.builder()
                .id("m1").type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-02/t-fb/m1.jpg")
                .build();
        TweetMedia video = TweetMedia.builder()
                .id("m2").type(TweetMediaType.VIDEO)
                .downloadStatus(MediaDownloadStatus.SKIPPED)
                .failureReason("视频/GIF 复现路径待 Story 8.2 spike 决定，暂不下载")
                .build();
        writer.writeSidecar("t-fb", PUBLISHED_AT, List.of(downloaded, video));
        when(stateRepository.getSnapshot("t-fb")).thenThrow(new RetryableException("Redis 连接失败"));

        List<MediaRuntimeItem> states = service.getMediaStates("t-fb", PUBLISHED_AT);

        assertThat(states).hasSize(2);
        assertThat(states.get(0).getMediaId()).isEqualTo("m1");
        assertThat(states.get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(states.get(0).getType()).isEqualTo(TweetMediaType.PHOTO);
        // sidecar 重建时 retryable 默认 false (无可重试信号)
        assertThat(states.get(0).isRetryable()).isFalse();
        assertThat(states.get(1).getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
    }

    /** Review patch: Redis unchecked RuntimeException 同样 fallback sidecar, 不抛给调用方. */
    @Test
    void shouldFallbackToSidecarWhenRedisThrowsUnchecked() {
        TweetMedia downloaded = TweetMedia.builder()
                .id("m1").type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-02/t-fb-unchecked/m1.jpg")
                .build();
        writer.writeSidecar("t-fb-unchecked", PUBLISHED_AT, List.of(downloaded));
        when(stateRepository.getSnapshot("t-fb-unchecked")).thenThrow(new IllegalStateException("proxy boom"));

        List<MediaRuntimeItem> states = service.getMediaStates("t-fb-unchecked", PUBLISHED_AT);

        assertThat(states).singleElement()
                .satisfies(item -> assertThat(item.getMediaId()).isEqualTo("m1"));
    }

    /** T5.6 (AC3): Redis miss + sidecar miss → 空列表 (不抛). */
    @Test
    void shouldReturnEmptyWhenBothSourcesMiss() {
        when(stateRepository.getSnapshot("t-none")).thenReturn(Optional.empty());

        assertThat(service.getMediaStates("t-none", PUBLISHED_AT)).isEmpty();
    }

    /** T5.7 (AC4): DOWNLOADED + 文件存在 → skip=true. */
    @Test
    void shouldSkipDownloadWhenDownloadedAndFileExists() throws Exception {
        String localPath = "media/twitter/2026-08-02/t-skip/m1.jpg";
        // 在真实归档目录创建非零文件
        Path file = tempDir.resolve(localPath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        when(stateRepository.getSnapshot("t-skip")).thenReturn(Optional.of(snapshot("t-skip",
                item("m1", MediaDownloadStatus.DOWNLOADED, localPath))));

        assertThat(service.shouldSkipDownload("t-skip", PUBLISHED_AT, "m1")).isTrue();
    }

    /** T5.7 (AC4): DOWNLOADED + 文件不存在 (外部删除) → skip=false (需重新下载). */
    @Test
    void shouldNotSkipWhenFileMissing() {
        when(stateRepository.getSnapshot("t-miss")).thenReturn(Optional.of(snapshot("t-miss",
                item("m1", MediaDownloadStatus.DOWNLOADED, "media/twitter/2026-08-02/t-miss/m1.jpg"))));

        assertThat(service.shouldSkipDownload("t-miss", PUBLISHED_AT, "m1")).isFalse();
    }

    /** T5.7 (AC4): DOWNLOADED + 空文件 (半写入残留) → skip=false. */
    @Test
    void shouldNotSkipWhenFileEmpty() throws Exception {
        String localPath = "media/twitter/2026-08-02/t-empty/m1.jpg";
        Path file = tempDir.resolve(localPath);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        when(stateRepository.getSnapshot("t-empty")).thenReturn(Optional.of(snapshot("t-empty",
                item("m1", MediaDownloadStatus.DOWNLOADED, localPath))));

        assertThat(service.shouldSkipDownload("t-empty", PUBLISHED_AT, "m1")).isFalse();
    }

    /** T5.7 (AC4): SKIPPED/FAILED/PENDING → skip=false. */
    @Test
    void shouldNotSkipForNonDownloadedStatus() {
        when(stateRepository.getSnapshot("t-status")).thenReturn(Optional.of(snapshot("t-status",
                item("m1", MediaDownloadStatus.SKIPPED, null),
                item("m2", MediaDownloadStatus.FAILED, null),
                item("m3", MediaDownloadStatus.PENDING, null))));

        assertThat(service.shouldSkipDownload("t-status", PUBLISHED_AT, "m1")).isFalse();
        assertThat(service.shouldSkipDownload("t-status", PUBLISHED_AT, "m2")).isFalse();
        assertThat(service.shouldSkipDownload("t-status", PUBLISHED_AT, "m3")).isFalse();
    }

    /** T5.7 (AC4): media 不在快照 → skip=false (待处理). */
    @Test
    void shouldNotSkipWhenMediaNotInSnapshot() {
        when(stateRepository.getSnapshot("t-absent")).thenReturn(Optional.of(snapshot("t-absent",
                item("m1", MediaDownloadStatus.DOWNLOADED, "media/twitter/x.jpg"))));

        assertThat(service.shouldSkipDownload("t-absent", PUBLISHED_AT, "m-other")).isFalse();
    }

    /** T5.7 边界 (AC4): DOWNLOADED 但 localPath null → skip=false. */
    @Test
    void shouldNotSkipWhenLocalPathNull() {
        when(stateRepository.getSnapshot("t-nullpath")).thenReturn(Optional.of(snapshot("t-nullpath",
                item("m1", MediaDownloadStatus.DOWNLOADED, null))));

        assertThat(service.shouldSkipDownload("t-nullpath", PUBLISHED_AT, "m1")).isFalse();
    }

    /** T5.7 路径穿越防御: localPath 含 ../ 逃逸 baseDir → skip=false. */
    @Test
    void shouldNotSkipWhenLocalPathEscapesBaseDir() throws Exception {
        // 预置 baseDir 外的诱惑文件
        Path outside = tempDir.resolve("outside.jpg");
        Files.write(outside, new byte[]{1, 2, 3});
        when(stateRepository.getSnapshot("t-escape")).thenReturn(Optional.of(snapshot("t-escape",
                item("m1", MediaDownloadStatus.DOWNLOADED, "../../../outside.jpg"))));

        assertThat(service.shouldSkipDownload("t-escape", PUBLISHED_AT, "m1")).isFalse();
    }

    /** Review patch: 畸形 localPath 不能让 shouldSkipDownload 抛 unchecked 异常. */
    @Test
    void shouldNotSkipWhenLocalPathIsInvalid() {
        when(stateRepository.getSnapshot("t-invalid-path")).thenReturn(Optional.of(snapshot("t-invalid-path",
                item("m1", MediaDownloadStatus.DOWNLOADED, "bad\u0000path.jpg"))));

        assertThat(service.shouldSkipDownload("t-invalid-path", PUBLISHED_AT, "m1")).isFalse();
    }

    /** T5.6 fallback 边界: sidecar 读取抛 NonRetryable (如 tweetId 非法) → 空列表不抛. */
    @Test
    void shouldReturnEmptyWhenSidecarReadFails() {
        when(stateRepository.getSnapshot("bad/id")).thenReturn(Optional.empty());
        // tweetId 含非法字符 → resolveArchiveDir 抛 NonRetryable → fail-open 空列表

        assertThat(service.getMediaStates("bad/id", PUBLISHED_AT)).isEmpty();
    }

    private static MediaRuntimeItem item(String mediaId, MediaDownloadStatus status, String localPath) {
        return MediaRuntimeItem.builder()
                .mediaId(mediaId)
                .downloadStatus(status)
                .localPath(localPath)
                .build();
    }

    private static MediaRuntimeState snapshot(String tweetId, MediaRuntimeItem... items) {
        return MediaRuntimeState.builder()
                .tweetId(tweetId)
                .mediaStates(List.of(items))
                .build();
    }
}
