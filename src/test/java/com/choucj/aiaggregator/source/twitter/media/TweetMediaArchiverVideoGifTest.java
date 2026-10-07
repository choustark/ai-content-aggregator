package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.10: VIDEO variant 选择与本地归档 + GIF 零变化专项测试.
 *
 * <p>用真实 {@link TweetMediaArchiveWriter} + {@code @TempDir} (软失败范式 §1.7, 不 mock Writer),
 * 覆盖 Story 10.10 I/O 矩阵全部 9 行: 多 mp4 不同码率 / 同码率确定性 / 无合法候选 / 未知大小 /
 * 超限 / 魔数不符 / 网络失败 / 幂等跳过 / GIF 零变化。零外部网络访问 (downloadClient 为 mock)。
 *
 * <p>VIDEO fixture 参照 {@code local-apify-actor-readiness-20260802.md} zhongying14 video media shape;
 * GIF 走合成 fixture (animated_gif 契约, 行为与 Story 7.3 基线零变化)。
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

    // ===== 矩阵行 1: 多 mp4 不同码率 → 最高码率被下载, sidecar SUCCEEDED + 完整证据 =====

    /** (AC1): 2+ mp4 variants → 选最高码率; 成功后 download=SUCCEEDED + localPath/文件大小/实际类型/码率摘要. */
    @Test
    void shouldSelectHighestBitrateMp4AndRecordEvidence() throws Exception {
        TweetMedia video = video("video-1", "https://video.twimg.com/vid/1280x720/abc.mp4",
                "https://pbs.twimg.com/thumb/abc.jpg", 1440, 2560, 0, List.of(
                        variant("https://video.twimg.com/vid/abc/432000.mp4", "video/mp4", 432000L),
                        variant("https://video.twimg.com/vid/abc/hls.m3u8", "application/x-mpegURL", null),
                        variant("https://video.twimg.com/vid/abc/832000.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-v1", PUBLISHED_AT, List.of(video));
        byte[] mp4Bytes = mp4Bytes(64);
        when(downloadClient.downloadBinary(eq("https://video.twimg.com/vid/abc/832000.mp4"),
                eq("tweet-v1"), eq("video-1")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes, "video/mp4"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-v1", PUBLISHED_AT, List.of(video));

        // 最高码率候选被下载, 其余候选零请求
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skipCount()).isZero();
        assertThat(result.failCount()).isZero();
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://video.twimg.com/vid/abc/832000.mp4"), eq("tweet-v1"), eq("video-1"));
        verify(downloadClient, never()).downloadBinary(
                eq("https://video.twimg.com/vid/abc/432000.mp4"), eq("tweet-v1"), eq("video-1"));
        verify(downloadClient, never()).downloadBinary(
                eq("https://video.twimg.com/vid/abc/hls.m3u8"), eq("tweet-v1"), eq("video-1"));

        MediaArchiveRecord record = writer.readSidecar("tweet-v1", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        assertThat(archived.getLocalPath()).startsWith("media/twitter/2026-08-02/tweet-v1/video-1-");
        assertThat(archived.getLocalPath()).endsWith(".mp4");
        // 文件证据字段 (Story 10.10 新增)
        assertThat(archived.getFileSizeBytes()).isEqualTo((long) mp4Bytes.length);
        assertThat(archived.getDownloadedContentType()).isEqualTo("video/mp4");
        // 文件真实落盘且非零
        Path archivedFile = tempDir.resolve(archived.getLocalPath());
        assertThat(Files.exists(archivedFile)).isTrue();
        assertThat(Files.size(archivedFile)).isEqualTo(mp4Bytes.length);
        // 元数据保真 + variant 摘要 (不含 URL)
        assertThat(archived.getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(archived.getPreviewImageUrl()).isEqualTo("https://pbs.twimg.com/thumb/abc.jpg");
        assertThat(archived.getWidth()).isEqualTo(1440);
        assertThat(archived.getHeight()).isEqualTo(2560);
        assertThat(archived.getVariants()).hasSize(3);
        assertThat(archived.getProviderRawSummary())
                .doesNotContain("video.twimg.com")
                .isEqualTo("video:variants=3,maxBitrate=832000,formats=video/mp4/application/x-mpegURL/video/mp4");
        // 证据字段确已持久化到 sidecar JSON; failureReason 被 fail-then-succeed 清空 (review patch 4b)
        assertThat(archived.getFailureReason()).isNull();
        String rawSidecar = Files.readString(tempDir.resolve("media/twitter/2026-08-02/tweet-v1/media.json"));
        assertThat(rawSidecar).contains("fileSizeBytes").contains("downloadedContentType");
    }

    // ===== 矩阵行 2: 同码率确定性 → URL 字典序选中, 与 variants 列表顺序无关 =====

    /** (AC5): 2 个同码率 mp4 variants → 按 URL 字典序选中, 列表顺序翻转后选择结果一致. */
    @Test
    void shouldTieBreakSameBitrateByLexicographicUrl_regardlessOfListOrder() {
        TweetMediaVariant a = variant("https://example.com/a-832k.mp4", "video/mp4", 832000L);
        TweetMediaVariant b = variant("https://example.com/b-832k.mp4", "video/mp4", 832000L);
        byte[] mp4Bytes = mp4Bytes(32);
        when(downloadClient.downloadBinary(eq("https://example.com/a-832k.mp4"), eq("tweet-tie1"), eq("v-tie")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes, "video/mp4"));
        when(downloadClient.downloadBinary(eq("https://example.com/a-832k.mp4"), eq("tweet-tie2"), eq("v-tie")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes, "video/mp4"));

        // 列表顺序 1: b 在前
        archiver.archiveMedia("tweet-tie1", PUBLISHED_AT,
                List.of(video("v-tie", "https://example.com/v1.mp4", null, 640, 480, 0, List.of(b, a))));
        // 列表顺序 2: a 在前 (顺序无关)
        archiver.archiveMedia("tweet-tie2", PUBLISHED_AT,
                List.of(video("v-tie", "https://example.com/v2.mp4", null, 640, 480, 0, List.of(a, b))));

        // 两次都只下载 URL 字典序最小者
        verify(downloadClient, never()).downloadBinary(
                eq("https://example.com/b-832k.mp4"), eq("tweet-tie1"), eq("v-tie"));
        verify(downloadClient, never()).downloadBinary(
                eq("https://example.com/b-832k.mp4"), eq("tweet-tie2"), eq("v-tie"));
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/a-832k.mp4"), eq("tweet-tie1"), eq("v-tie"));
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/a-832k.mp4"), eq("tweet-tie2"), eq("v-tie"));
    }

    // ===== 矩阵行 3: 无合法候选 → 零网络请求, FAILED_TERMINAL, 无文件写入 =====

    /** (AC2): variants 全为 HLS (或空) → 不发下载请求, download=FAILED_TERMINAL, 无本地文件. */
    @Test
    void shouldFailTerminalWithNoCandidate_whenAllVariantsAreNonMp4() throws Exception {
        TweetMedia video = video("v-hls", "https://example.com/v-hls.mp4", "https://example.com/t.jpg",
                640, 480, 0, List.of(
                        variant("https://example.com/hls.m3u8", "application/x-mpegURL", 832000L),
                        variant("https://example.com/webm", "video/webm", 432000L)));
        writer.writeSidecar("tweet-noauth", PUBLISHED_AT, List.of(video));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-noauth", PUBLISHED_AT, List.of(video));

        // 不发下载请求
        verifyNoInteractions(downloadClient);
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(result.mediaStatuses()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo(MediaDownloadStatus.FAILED);
            assertThat(status.retryable()).isFalse();
            assertThat(status.failureReason()).contains("无合法 mp4 下载候选");
        });
        MediaArchiveRecord record = writer.readSidecar("tweet-noauth", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(archived.getDownload().getAttempt()).isEqualTo(1);
        assertThat(archived.getDownload().getNextRetryAt()).isNull();
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
        // 证据字段在终态失败下不存在; @JsonInclude(NON_NULL) 序列化后字段消失 (review patch 4a 断言)
        assertThat(archived.getFileSizeBytes()).isNull();
        assertThat(archived.getDownloadedContentType()).isNull();
        String rawSidecar = Files.readString(tempDir.resolve("media/twitter/2026-08-02/tweet-noauth/media.json"));
        assertThat(rawSidecar).doesNotContain("fileSizeBytes").doesNotContain("downloadedContentType");
        // 不因候选失败回退到非 mp4 候选, 无本地文件写入
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-noauth");
        try (var files = uncheckedList(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".mp4") || name.endsWith(".tmp"));
        }
    }

    /** (AC2): variants 为空 → 同「无合法候选」终态. */
    @Test
    void shouldFailTerminalWithNoCandidate_whenVariantsEmpty() {
        TweetMedia video = video("v-empty", "https://example.com/v-empty.mp4", "https://example.com/t.jpg",
                640, 480, 0, List.of());
        writer.writeSidecar("tweet-vempty", PUBLISHED_AT, List.of(video));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-vempty", PUBLISHED_AT, List.of(video));

        verifyNoInteractions(downloadClient);
        assertThat(result.failCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-vempty", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownload().getStatus())
                .isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
    }

    // ===== 矩阵行 4: 未知大小 → 不落盘, FAILED_TERMINAL =====

    /** (AC2): 选中候选响应无 Content-Length (未知大小, client 侧 NonRetryable) → 不落盘, FAILED_TERMINAL. */
    @Test
    void shouldFailTerminal_whenResponseHasUnknownContentLength() {
        TweetMedia video = video("v-unknown", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-832k.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-unknown", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-832k.mp4"), eq("tweet-unknown"), eq("v-unknown")))
                .thenThrow(new NonRetryableException("媒体响应无 Content-Length(未知大小): tweetId=tweet-unknown"
                        + " mediaId=v-unknown", null));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-unknown", PUBLISHED_AT, List.of(video));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> assertThat(status.retryable()).isFalse());
        MediaArchiveRecord record = writer.readSidecar("tweet-unknown", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(archived.getDownload().getAttempt()).isEqualTo(1);
        assertThat(archived.getDownload().getNextRetryAt()).isNull();
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
        // 不落盘 (无 .mp4/.tmp 残留)
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-unknown");
        try (var files = uncheckedList(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".mp4") || name.endsWith(".tmp"));
        }
    }

    // ===== 矩阵行 5: 超限 → 不落盘, FAILED_TERMINAL =====

    /** (AC2): Content-Length 预检或 readBounded 超过 max-file-size-mb → 不落盘, FAILED_TERMINAL. */
    @Test
    void shouldFailTerminal_whenDownloadExceedsMaxFileSize() {
        TweetMedia video = video("v-oversize", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-huge.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-oversize", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-huge.mp4"), eq("tweet-oversize"), eq("v-oversize")))
                .thenThrow(new NonRetryableException("媒体文件超过大小限制: tweetId=tweet-oversize"
                        + " mediaId=v-oversize contentLength=20971520 max=10485760", null));

        TweetMediaArchiver.ArchiveResult result =
                archiver.archiveMedia("tweet-oversize", PUBLISHED_AT, List.of(video));

        assertThat(result.failCount()).isEqualTo(1);
        MediaArchiveRecord record = writer.readSidecar("tweet-oversize", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getDownload().getStatus())
                .isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-oversize");
        try (var files = uncheckedList(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".mp4") || name.endsWith(".tmp"));
        }
    }

    // ===== 矩阵行 6: 校验失败 (非 mp4 魔数) → .tmp 清理, FAILED_TERMINAL =====

    /** (AC2): 下载字节非 mp4 (ftyp 不符) → 不落盘, FAILED_TERMINAL, 无文件/临时文件残留. */
    @Test
    void shouldFailTerminal_whenDownloadedBytesNotMp4Magic() {
        TweetMedia video = video("v-bad", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-fake.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-vbad", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-fake.mp4"), eq("tweet-vbad"), eq("v-bad")))
                .thenReturn(new MediaDownloadClient.DownloadResult(
                        "<html>not a video</html>".getBytes(), "text/html"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-vbad", PUBLISHED_AT, List.of(video));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> assertThat(status.retryable()).isFalse());
        MediaArchiveRecord record = writer.readSidecar("tweet-vbad", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(archived.getDownload().getAttempt()).isEqualTo(1);
        assertThat(archived.getDownload().getNextRetryAt()).isNull();
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
        // 先校验后落盘: 无视频文件/临时文件残留
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-vbad");
        try (var files = uncheckedList(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".mp4") || name.endsWith(".tmp"));
        }
    }

    // ===== 矩阵行 7: 网络失败 → FAILED_TERMINAL, 不自动重试 =====

    /** (AC2): 连接/超时/5xx/4xx (Retryable 映射) → FAILED_TERMINAL 终态, 零自动重试. */
    @Test
    void shouldFailTerminalWithoutRetry_whenNetworkFails() {
        TweetMedia video = video("v-net", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-net.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-vnet", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-net.mp4"), eq("tweet-vnet"), eq("v-net")))
                .thenThrow(new RetryableException("媒体下载连接失败/超时: tweetId=tweet-vnet mediaId=v-net"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-vnet", PUBLISHED_AT, List.of(video));

        // 仅一次请求 (OQ1: 选择一次性确定, 失败即终态, 不回退次优也不自动重试)
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/v-net.mp4"), eq("tweet-vnet"), eq("v-net"));
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        MediaArchiveRecord record = writer.readSidecar("tweet-vnet", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(archived.getDownload().getNextRetryAt()).isNull();
        assertThat(archived.getDownload().getErrorClass()).isEqualTo("RETRYABLE");
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
    }

    /**
     * Story 10.10 review patch 4a: succeed-then-fail 清空文件证据字段 —
     * 先成功后文件丢失 + 重下失败, 旧 fileSizeBytes/downloadedContentType 不得残留
     * (不留下指向已不存在文件的证据)。
     */
    @Test
    void shouldClearEvidenceFields_whenSucceedThenFail() throws Exception {
        TweetMedia video = video("v-sf", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-sf.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-vsf", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-sf.mp4"), eq("tweet-vsf"), eq("v-sf")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(48), "video/mp4"));
        archiver.archiveMedia("tweet-vsf", PUBLISHED_AT, List.of(video));
        MediaArchiveRecord first = writer.readSidecar("tweet-vsf", PUBLISHED_AT).orElseThrow();
        assertThat(first.getMedia().get(0).getFileSizeBytes()).isNotNull();

        // 文件丢失 → 幂等双校验不通过 → 重新下载, 本次下载网络失败
        Files.delete(tempDir.resolve(first.getMedia().get(0).getLocalPath()));
        org.mockito.Mockito.reset(downloadClient);
        when(downloadClient.downloadBinary(eq("https://example.com/v-sf.mp4"), eq("tweet-vsf"), eq("v-sf")))
                .thenThrow(new RetryableException("媒体下载连接失败/超时"));

        TweetMediaArchiver.ArchiveResult second = archiver.archiveMedia("tweet-vsf", PUBLISHED_AT, List.of(video));

        assertThat(second.failCount()).isEqualTo(1);
        MediaArchiveRecord after = writer.readSidecar("tweet-vsf", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = after.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        // succeed-then-fail: 证据字段被清空, 序列化后消失
        assertThat(archived.getFileSizeBytes()).isNull();
        assertThat(archived.getDownloadedContentType()).isNull();
        String rawSidecar = Files.readString(tempDir.resolve("media/twitter/2026-08-02/tweet-vsf/media.json"));
        assertThat(rawSidecar).doesNotContain("fileSizeBytes").doesNotContain("downloadedContentType");
    }

    // ===== 矩阵行 8: 幂等跳过 (文件非零 + SUCCEEDED) / 文件缺失 → 重新下载 =====

    /** (AC3): 已成功 VIDEO 重复运行 → 幂等跳过, 零网络请求, sidecar 成功字段不回退. */
    @Test
    void shouldSkipRedownload_whenFileNonZeroAndDownloadPhaseSucceeded() throws Exception {
        TweetMedia video = video("v-idem", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-idem.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-videm", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-idem.mp4"), eq("tweet-videm"), eq("v-idem")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(48), "video/mp4"));

        archiver.archiveMedia("tweet-videm", PUBLISHED_AT, List.of(video));
        MediaArchiveRecord first = writer.readSidecar("tweet-videm", PUBLISHED_AT).orElseThrow();
        String firstPhaseUpdatedAt = String.valueOf(first.getMedia().get(0).getDownload().getUpdatedAt());
        Long firstFileSize = first.getMedia().get(0).getFileSizeBytes();

        TweetMediaArchiver.ArchiveResult second = archiver.archiveMedia("tweet-videm", PUBLISHED_AT, List.of(video));

        assertThat(second.successCount()).isEqualTo(1);
        // 双校验通过 → 0 次重复下载
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/v-idem.mp4"), eq("tweet-videm"), eq("v-idem"));
        // sidecar 成功证据字段不回退
        MediaArchiveRecord afterSkip = writer.readSidecar("tweet-videm", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = afterSkip.getMedia().get(0);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        assertThat(String.valueOf(archived.getDownload().getUpdatedAt())).isEqualTo(firstPhaseUpdatedAt);
        assertThat(archived.getFileSizeBytes()).isEqualTo(firstFileSize);
        assertThat(archived.getDownloadedContentType()).isEqualTo("video/mp4");
    }

    /** (AC3): 文件缺失 (或零字节) → 幂等双校验不通过, 重新下载. */
    @Test
    void shouldRedownload_whenArchivedFileMissing() throws Exception {
        TweetMedia video = video("v-redown", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-redown.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-vredown", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-redown.mp4"), eq("tweet-vredown"), eq("v-redown")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(48), "video/mp4"));
        archiver.archiveMedia("tweet-vredown", PUBLISHED_AT, List.of(video));
        MediaArchiveRecord first = writer.readSidecar("tweet-vredown", PUBLISHED_AT).orElseThrow();
        Files.delete(tempDir.resolve(first.getMedia().get(0).getLocalPath()));

        TweetMediaArchiver.ArchiveResult second = archiver.archiveMedia("tweet-vredown", PUBLISHED_AT, List.of(video));

        assertThat(second.successCount()).isEqualTo(1);
        verify(downloadClient, times(2)).downloadBinary(
                eq("https://example.com/v-redown.mp4"), eq("tweet-vredown"), eq("v-redown"));
    }

    /**
     * Story 10.10 review patch: 幂等双校验先于 variant 选择 — 已成功 (文件非零 + download=SUCCEEDED)
     * 的 VIDEO 重复运行时即使 variants 退化为空列表 (provider 数据退化), 也必须幂等跳过,
     * 不得把既有成功打成 FAILED_TERMINAL ("无合法候选")。
     */
    @Test
    void shouldSkipIdempotently_whenVariantsDegeneratedAfterSuccess() throws Exception {
        TweetMedia video = video("v-degrade", "https://example.com/v.mp4", null, 640, 480, 0,
                List.of(variant("https://example.com/v-degrade.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-vdegrade", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/v-degrade.mp4"),
                eq("tweet-vdegrade"), eq("v-degrade")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(48), "video/mp4"));
        archiver.archiveMedia("tweet-vdegrade", PUBLISHED_AT, List.of(video));
        assertThat(writer.readSidecar("tweet-vdegrade", PUBLISHED_AT).orElseThrow()
                .getMedia().get(0).getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);

        // 重复运行: 同 mediaId 但 variants 退化为空列表
        TweetMedia degraded = video("v-degrade", "https://example.com/v.mp4", null, 640, 480, 0, List.of());
        TweetMediaArchiver.ArchiveResult second =
                archiver.archiveMedia("tweet-vdegrade", PUBLISHED_AT, List.of(degraded));

        // 幂等跳过, 零下载请求, 不抛"无合法候选", 既有成功状态不回退
        assertThat(second.successCount()).isEqualTo(1);
        assertThat(second.failCount()).isZero();
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/v-degrade.mp4"), eq("tweet-vdegrade"), eq("v-degrade"));
        MediaArchiveRecord after = writer.readSidecar("tweet-vdegrade", PUBLISHED_AT).orElseThrow();
        assertThat(after.getMedia().get(0).getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        assertThat(after.getMedia().get(0).getLocalPath()).isNotBlank();
    }

    /**
     * Story 10.10 review patch: 成功回写未命中 (mediaId 不在 sidecar) → 删除已落盘最终文件
     * (不留孤儿文件) + 外层终态失败, 对齐 GIF 回写未命中既有口径。
     */
    @Test
    void shouldDeleteFinalFileAndFail_whenSuccessSidecarWriteMisses() throws Exception {
        TweetMedia sidecarVideo = video("sidecar-v", "https://example.com/sidecar.mp4",
                "https://example.com/sidecar.jpg", 640, 480, 0, List.of());
        TweetMedia incomingVideo = video("incoming-v", "https://example.com/incoming.mp4",
                "https://example.com/incoming.jpg", 640, 480, 0,
                List.of(variant("https://example.com/incoming-832k.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-wmiss", PUBLISHED_AT, List.of(sidecarVideo));
        when(downloadClient.downloadBinary(eq("https://example.com/incoming-832k.mp4"),
                eq("tweet-wmiss"), eq("incoming-v")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(48), "video/mp4"));

        TweetMediaArchiver.ArchiveResult result =
                archiver.archiveMedia("tweet-wmiss", PUBLISHED_AT, List.of(incomingVideo));

        // 下载确实发生了, 但回写未命中 → 计入失败
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(result.mediaStatuses()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo(MediaDownloadStatus.FAILED);
            assertThat(status.mediaId()).isEqualTo("incoming-v");
            assertThat(status.failureReason()).contains("VIDEO sidecar 回写未命中");
        });
        // 已落盘最终文件被删除, 无 .mp4/.tmp 残留
        Path archiveDir = tempDir.resolve("media/twitter/2026-08-02/tweet-wmiss");
        try (var files = uncheckedList(archiveDir)) {
            assertThat(files.map(Path::toString).toList())
                    .noneMatch(name -> name.endsWith(".mp4") || name.endsWith(".tmp"));
        }
        // 原 sidecar 项不受污染
        MediaArchiveRecord record = writer.readSidecar("tweet-wmiss", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getId()).isEqualTo("sidecar-v");
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
    }

    // ===== 矩阵行 9: GIF 零变化 =====

    /** (AC3): GIF 行为与 Story 7.3 基线完全一致 — 元数据归档 SKIPPED, 零下载, DEFERRED 阶段. */
    @Test
    void shouldKeepGifBehaviorUnchanged() {
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
                .originalPostUrl("https://x.com/owner/status/100")
                .providerRawSummary("gif:variants=1")
                .build();
        writer.writeSidecar("tweet-g1", PUBLISHED_AT, List.of(gif));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-g1", PUBLISHED_AT, List.of(gif));

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(result.failCount()).isZero();
        verifyNoInteractions(downloadClient);

        MediaArchiveRecord record = writer.readSidecar("tweet-g1", PUBLISHED_AT).orElseThrow();
        TweetMedia archived = record.getMedia().get(0);
        assertThat(archived.getType()).isEqualTo(TweetMediaType.GIF);
        assertThat(archived.getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(archived.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.DEFERRED);
        assertThat(archived.getFailureReason()).isEqualTo("视频/GIF 复现路径待 Story 8.2 spike 决定，暂不下载");
        assertThat(archived.getWidth()).isEqualTo(480);
        assertThat(archived.getHeight()).isEqualTo(270);
        assertThat(archived.getOriginalPostUrl()).isNotBlank();
        assertThat(archived.getManualInstruction()).isNotBlank();
        assertThat(archived.getProviderRawSummary()).isEqualTo("gif:variants=1,maxBitrate=0,formats=video/mp4");
    }

    /** GIF 绝不触发下载 (VIDEO 下载化后 GIF 语义零变化的另一半). */
    @Test
    void shouldNeverDownloadGif() {
        TweetMedia gif = TweetMedia.builder()
                .id("g").type(TweetMediaType.GIF).sourceUrl("https://example.com/g.mp4")
                .previewImageUrl("https://example.com/gt.jpg").order(1)
                .variants(List.of()).build();
        writer.writeSidecar("tweet-ng", PUBLISHED_AT, List.of(gif));

        archiver.archiveMedia("tweet-ng", PUBLISHED_AT, List.of(gif));

        verifyNoInteractions(downloadClient);
        assertThat(writer.readSidecar("tweet-ng", PUBLISHED_AT).orElseThrow()
                .getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
    }

    /** GIF 幂等: 重复 archiveMedia, sidecar 内容稳定 (Story 7.3 AC7 行为零变化). */
    @Test
    void shouldBeIdempotentForRepeatedGifArchive() {
        TweetMedia gif = TweetMedia.builder()
                .id("gif-idem")
                .type(TweetMediaType.GIF)
                .sourceUrl("https://video.twimg.com/gif/idem.mp4")
                .previewImageUrl("https://pbs.twimg.com/thumb/idem.jpg")
                .width(480)
                .height(270)
                .order(0)
                .variants(List.of(variant("https://video.twimg.com/gif/idem.mp4", "video/mp4", null)))
                .build();
        writer.writeSidecar("tweet-gidem", PUBLISHED_AT, List.of(gif));

        archiver.archiveMedia("tweet-gidem", PUBLISHED_AT, List.of(gif));
        TweetMedia first = writer.readSidecar("tweet-gidem", PUBLISHED_AT).orElseThrow().getMedia().get(0);
        String firstSummary = first.getProviderRawSummary();
        String firstFailure = first.getFailureReason();

        archiver.archiveMedia("tweet-gidem", PUBLISHED_AT, List.of(gif));
        TweetMedia second = writer.readSidecar("tweet-gidem", PUBLISHED_AT).orElseThrow().getMedia().get(0);

        assertThat(second.getDownloadStatus()).isEqualTo(MediaDownloadStatus.SKIPPED);
        assertThat(second.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.DEFERRED);
        assertThat(second.getProviderRawSummary()).isEqualTo(firstSummary);
        assertThat(second.getFailureReason()).isEqualTo(firstFailure);
        assertThat(second.getVariants()).hasSize(1);
    }

    // ===== 选择器/魔数纯函数与摘要 =====

    /** 码率/格式摘要: 多 contentType 保序, 不含 URL (T3.6 行为零变化, 经 VIDEO 成功路径持久化). */
    @Test
    void shouldBuildVariantSummaryWithoutUrls() {
        TweetMedia video = video("v6", "https://example.com/v6.mp4", "https://example.com/t6.jpg",
                1280, 720, 0, List.of(
                        variant("https://example.com/832k.mp4", "video/mp4", 832000L),
                        variant("https://example.com/hls.m3u8", "application/x-mpegURL", null)));
        writer.writeSidecar("tweet-s6", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/832k.mp4"), eq("tweet-s6"), eq("v6")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(32), "video/mp4"));

        archiver.archiveMedia("tweet-s6", PUBLISHED_AT, List.of(video));

        MediaArchiveRecord record = writer.readSidecar("tweet-s6", PUBLISHED_AT).orElseThrow();
        String summary = record.getMedia().get(0).getProviderRawSummary();
        // 不含完整 URL (N4)
        assertThat(summary).doesNotContain("example.com");
        assertThat(summary).isEqualTo("video:variants=2,maxBitrate=832000,formats=video/mp4/application/x-mpegURL");
    }

    /** null variant 元素在摘要中忽略, 不参与选择 (防御口径与 Story 7.3 一致). */
    @Test
    void shouldIgnoreNullVariantInSelectionAndSummary() {
        TweetMedia video = video("v-null", "https://example.com/null.mp4", "https://example.com/null.jpg",
                1280, 720, 0, Arrays.asList(
                        null,
                        variant("https://example.com/ok.mp4", "video/mp4", 832000L)));
        writer.writeSidecar("tweet-null", PUBLISHED_AT, List.of(video));
        when(downloadClient.downloadBinary(eq("https://example.com/ok.mp4"), eq("tweet-null"), eq("v-null")))
                .thenReturn(new MediaDownloadClient.DownloadResult(mp4Bytes(32), "video/mp4"));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-null", PUBLISHED_AT, List.of(video));

        assertThat(result.successCount()).isEqualTo(1);
        verify(downloadClient, times(1)).downloadBinary(
                eq("https://example.com/ok.mp4"), eq("tweet-null"), eq("v-null"));
        MediaArchiveRecord record = writer.readSidecar("tweet-null", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getProviderRawSummary())
                .isEqualTo("video:variants=1,maxBitrate=832000,formats=video/mp4");
    }

    /** (AC5): sidecar 回写未命中 (mediaId 不在 sidecar) → 失败计入 failCount, 原 sidecar 项不受污染. */
    @Test
    void shouldCountFailure_whenVideoMediaIdMissesSidecar() {
        TweetMedia sidecarVideo = video("sidecar-v", "https://example.com/sidecar.mp4",
                "https://example.com/sidecar.jpg", 640, 480, 0, List.of());
        TweetMedia incomingVideo = video("incoming-v", "https://example.com/incoming.mp4",
                "https://example.com/incoming.jpg", 640, 480, 0, List.of());
        writer.writeSidecar("tweet-miss", PUBLISHED_AT, List.of(sidecarVideo));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-miss", PUBLISHED_AT, List.of(incomingVideo));

        verifyNoInteractions(downloadClient);
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses()).singleElement()
                .satisfies(status -> {
                    assertThat(status.status()).isEqualTo(MediaDownloadStatus.FAILED);
                    assertThat(status.mediaId()).isEqualTo("incoming-v");
                    assertThat(status.retryable()).isFalse();
                    assertThat(status.failureReason()).contains("无合法 mp4 下载候选");
                });
        MediaArchiveRecord record = writer.readSidecar("tweet-miss", PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia().get(0).getId()).isEqualTo("sidecar-v");
        assertThat(record.getMedia().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
    }

    /** 逐媒体隔离: VIDEO 失败 + PHOTO 在后仍被处理 (AD-5, 失败计数独立). */
    @Test
    void shouldIsolatePerMediaFailure() {
        TweetMedia video = video("v7", "https://example.com/v7.mp4", "https://example.com/t7.jpg",
                640, 480, 0, List.of());
        TweetMedia photo = TweetMedia.builder()
                .id("p7").type(TweetMediaType.PHOTO)
                .sourceUrl("https://pbs.twimg.com/media/P7.jpg")
                .allowDownload(true).build();
        writer.writeSidecar("tweet-i7", PUBLISHED_AT, List.of(video, photo));

        TweetMediaArchiver.ArchiveResult result = archiver.archiveMedia("tweet-i7", PUBLISHED_AT, List.of(video, photo));

        // VIDEO 无合法候选终态失败 + PHOTO 下载失败 (downloadClient 未打桩), 二者独立计数
        assertThat(result.failCount()).isEqualTo(2);
        assertThat(result.skipCount()).isZero();
        // VIDEO 失败不阻塞 PHOTO 的处理 (status 均为 FAILED, 隔离可观察)
        assertThat(result.mediaStatuses()).hasSize(2);
    }

    /** Story 10.10: mp4 ftyp 魔数识别 — 合法 ftyp 通过, 非视频/过短/null 拒绝. */
    @Test
    void hasMp4MagicNumber_detectsFtypAndRejectsGarbage() {
        // 标准 ftyp box: 4 字节 box size + "ftyp" + major brand
        byte[] mp4 = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(mp4)).isTrue();
        // m4v 同为 ftyp 家族 (major brand M4V) — 校验只看 offset 4-7
        byte[] m4v = new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p', 'M', '4', 'V', ' ', 0, 0, 0, 0};
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(m4v)).isTrue();
        // JPEG (魔数在 offset 0-2, offset 4-7 为 "E0 00 00 JF") 不是 ftyp
        byte[] jpegLike = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 'J', 'F', 'I', 'F', 0, 1};
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(jpegLike)).isFalse();
        assertThat(TweetMediaArchiver.hasMp4MagicNumber("<html></html>".getBytes())).isFalse();
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(new byte[]{0, 0, 0, 24, 'f', 't', 'y'})).isFalse();
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(new byte[5])).isFalse();
        assertThat(TweetMediaArchiver.hasMp4MagicNumber(null)).isFalse();
    }

    /** 合成合法 mp4 ftyp 字节 (size ≥ 12), sizeBytes 控制长度供文件大小断言. */
    private static byte[] mp4Bytes(int size) {
        byte[] bytes = new byte[Math.max(size, 12)];
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 0;
        bytes[3] = 24;
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        bytes[8] = 'i';
        bytes[9] = 's';
        bytes[10] = 'o';
        bytes[11] = 'm';
        return bytes;
    }

    private static java.util.stream.Stream<Path> uncheckedList(Path dir) {
        try {
            return Files.list(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
