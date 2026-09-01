package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.media.model.MediaPublishabilityDecision;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetAccessStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetContentType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 7.5: TweetPublishabilityGate 单元测试 — 决策表全覆盖 + 聚合优先级 + sidecar 回写 + 软失败.
 *
 * <p>测试范式（AC10）: 真实 Gate + mock MediaRuntimeRecoveryService + 真实 TweetMediaArchiveWriter + @TempDir.
 *
 * <p><b>决策表覆盖（T5.1）:</b> AC2 每行 ≥1 用例（M1-M9 合规/下载/跳过/失败/待处理/不在快照）.
 *
 * <p><b>推文级 BLOCKED（T5.2）:</b> 文本三字段全空 / accessStatus 受限 各一用例.
 *
 * <p><b>聚合优先级（T5.3）:</b> BLOCKED > DEGRADED > UNKNOWN > PUBLISHABLE 组合用例.
 *
 * <p><b>sidecar 回写（T5.4）:</b> 验证 publishability 写入调用; 回写抛异常 → evaluate 不抛.
 */
@ExtendWith(MockitoExtension.class)
public class TweetPublishabilityGateTest {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 15, 10, 0);

    @Mock
    private MediaRuntimeRecoveryService mockRecoveryService;

    @TempDir
    Path tempDir;

    private TweetMediaArchiveWriter archiveWriter;
    private TweetPublishabilityGate gate;

    @BeforeEach
    void setUp() {
        TwitterMediaProperties mediaProperties = new TwitterMediaProperties();
        mediaProperties.setEnabled(true);
        mediaProperties.setBaseDirectory(tempDir.toString());
        mediaProperties.setDatePattern("yyyy-MM-dd");
        mediaProperties.setSidecarFilename("media.json");

        ArchiverProperties archiverProperties = new ArchiverProperties();
        archiverProperties.setBaseDirectory(tempDir.resolve("fallback").toString());

        archiveWriter = new TweetMediaArchiveWriter(
                mediaProperties, archiverProperties, new ObjectMapper().findAndRegisterModules());
        gate = new TweetPublishabilityGate(mockRecoveryService, archiveWriter);
    }

    // ========== T5.1: 决策表全覆盖（M1-M9） ==========

    @Test
    void shouldBlockMedia_when_failureReasonStartsWithAvailabilityPrefix() {
        // M1: 合规 BLOCKED（provider media availability: 前缀）
        String tweetId = "test-tweet-1";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-1")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("provider media availability: Banned by platform")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        // 媒体 BLOCKED 导致推文 DEGRADED（非 BLOCKED，因为文本可用）
        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.BLOCKED);
        assertThat(decision.getReason()).isEqualTo("provider media availability: Banned by platform");
        assertThat(decision.getMediaId()).isEqualTo("media-1");
    }

    @Test
    void shouldPublishMedia_when_downloadedAndFileExists() {
        // M2: DOWNLOADED + 文件存在 → PUBLISHABLE
        String tweetId = "test-tweet-2";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        String localPath = "media/twitter/2026-08-15/test-tweet-2/photo.jpg";
        createNonEmptyFile(localPath);
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-2")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath(localPath)
                .failureReason(null)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.PUBLISHABLE);
        assertThat(decision.getReason()).isNull();
    }

    @Test
    void shouldDegradeMedia_when_downloadedButFileMissing() {
        // M3: DOWNLOADED + 文件缺失 → DEGRADED
        String tweetId = "test-tweet-3";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-3")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-15/test-tweet-3/missing.jpg")
                .failureReason(null)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(decision.getReason()).contains("已下载文件缺失");
    }

    @Test
    void shouldDegradeVideoGifMedia_when_skippedWithTypeVideoOrGif() {
        // M4: SKIPPED + VIDEO/GIF → DEGRADED（固定 reason）
        String tweetId = "test-tweet-4";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem videoItem = MediaRuntimeItem.builder()
                .mediaId("video-1")
                .type(TweetMediaType.VIDEO)
                .downloadStatus(MediaDownloadStatus.SKIPPED)
                .failureReason("视频下载跳过")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(videoItem));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(decision.getReason()).isEqualTo("视频/GIF 复现路径待 Story 8.2 spike 决定，当前以封面/链接降级呈现");
    }

    @Test
    void shouldDegradeMedia_when_skippedWithOtherReason() {
        // M5: SKIPPED + 其他原因 → DEGRADED（沿用 failureReason）
        String tweetId = "test-tweet-5";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-5")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.SKIPPED)
                .failureReason("allowDownload=false 配置跳过")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(decision.getReason()).isEqualTo("allowDownload=false 配置跳过");
    }

    @Test
    void shouldDegradeMedia_withRetryableSuffix_when_failedAndRetryable() {
        // M6: FAILED + retryable=true → DEGRADED + "（可重试）"
        String tweetId = "test-tweet-6";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-6")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("HTTP 403 Forbidden")
                .retryable(true)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(decision.getReason()).isEqualTo("HTTP 403 Forbidden（可重试）");
    }

    @Test
    void shouldDegradeMedia_withoutRetryableSuffix_when_failedAndNotRetryable() {
        // M7: FAILED + retryable=false → DEGRADED（不追加后缀）
        String tweetId = "test-tweet-7";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-7")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("HTTP 410 Gone")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(decision.getReason()).isEqualTo("HTTP 410 Gone"); // 无可重试后缀
    }

    @Test
    void shouldUnknownMedia_when_statusPending() {
        // M8: PENDING → UNKNOWN（媒体尚未归档）
        String tweetId = "test-tweet-8";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-8")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.PENDING)
                .failureReason(null)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.UNKNOWN); // 推文级 UNKNOWN
        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.UNKNOWN);
        assertThat(decision.getReason()).isEqualTo("媒体尚未归档，无法评估");
    }

    @Test
    void shouldUnknownMedia_when_notInSnapshot() {
        // M9: 不在状态源中（downloadStatus == null）→ UNKNOWN
        String tweetId = "test-tweet-9";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-9")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(null) // 不在快照中
                .failureReason(null)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).hasSize(1);
        MediaPublishabilityDecision decision = result.getMediaDecisions().get(0);
        assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.UNKNOWN);
        assertThat(decision.getReason()).isEqualTo("媒体不在运行时状态中，无法评估");
    }

    // ========== T5.2: 推文级 BLOCKED ==========

    @Test
    void shouldBlockTweet_when_allTextFieldsBlank() {
        // T1: 文本三字段全 blank → BLOCKED
        String tweetId = "test-tweet-10";
        Tweet tweet = Tweet.builder()
                .id(tweetId)
                .content(null)
                .rawText("")
                .formattedText("  ") // blank 字符串
                .publishedAt(LocalDateTime.now())
                .media(List.of())
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.BLOCKED);
        assertThat(result.getTweetReason()).isEqualTo("源文本不可用（provider 未返回文本），需人工确认");
    }

    @Test
    void shouldNotBlockTweet_when_articleContentTypePresent() {
        // ARTICLE 是内容类型，不是访问受限信号
        String tweetId = "test-tweet-11";
        Tweet tweet = Tweet.builder()
                .id(tweetId)
                .content("测试文本")
                .rawText("原始文本")
                .formattedText("格式化文本")
                .contentType(TweetContentType.ARTICLE)
                .publishedAt(LocalDateTime.now())
                .media(List.of())
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.PUBLISHABLE);
        assertThat(result.getTweetReason()).isNull();
    }

    @Test
    void shouldNotBlockTweet_when_anyTextFieldNotBlank() {
        // 负向用例：文本任一字段非空不触发 BLOCKED
        String tweetId = "test-tweet-12";
        Tweet tweet = Tweet.builder()
                .id(tweetId)
                .content("有文本内容")
                .rawText(null)
                .formattedText(null)
                .publishedAt(LocalDateTime.now())
                .media(List.of())
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.PUBLISHABLE); // 无媒体且文本可用
    }

    // ========== T5.3: 聚合优先级 ==========

    @Test
    void shouldAggregateBlockedPriority_overMediaBlocked() {
        // 关键边界：媒体 BLOCKED + 文本可用 → 推文 DEGRADED（非 BLOCKED）
        String tweetId = "test-tweet-13";
        Tweet tweet = createTweetWithText(tweetId, "文本可用");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-13")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("provider media availability: Banned content")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        // 媒体 BLOCKED 不升级推文 BLOCKED → 推文 DEGRADED
        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(result.getTweetReason()).contains("存在 1 个降级/受限媒体");
    }

    @Test
    void shouldAggregateStatusPriority_blocked_degraded_unknown_publishable() {
        // 聚合优先级：BLOCKED > DEGRADED > UNKNOWN > PUBLISHABLE
        String tweetId = "test-tweet-14";
        Tweet tweet = Tweet.builder()
                .id(tweetId)
                .content("文本可用")
                .accessStatus(TweetAccessStatus.RESTRICTED)
                .publishedAt(LocalDateTime.now())
                .media(List.of())
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        // T2 推文级 BLOCKED 优先级最高
        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.BLOCKED);
        assertThat(result.getTweetReason()).contains("访问受限");
    }

    @Test
    void shouldAggregateDegraded_when_mediaDegradedExists() {
        String tweetId = "test-tweet-15";
        Tweet tweet = createTweetWithText(tweetId, "文本可用");
        MediaRuntimeItem degradedItem = MediaRuntimeItem.builder()
                .mediaId("media-15")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("下载失败")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(degradedItem));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
        assertThat(result.getTweetReason()).contains("存在 1 个降级/受限媒体");
    }

    // ========== T5.4: sidecar 回写 ==========

    @Test
    void shouldWriteBackToSidecar_when_evaluationCompletes() {
        String tweetId = "test-tweet-16";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        TweetMedia sidecarMedia = TweetMedia.builder()
                .id("media-16")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .publishability(PublishabilityStatus.UNKNOWN)
                .build();
        archiveWriter.writeSidecar(tweetId, PUBLISHED_AT, List.of(sidecarMedia));
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-16")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-15/test-tweet-16/photo.jpg")
                .failureReason(null)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        gate.evaluate(tweet);

        MediaArchiveRecord record = archiveWriter.readSidecar(tweetId, PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia()).singleElement()
                .satisfies(media -> assertThat(media.getPublishability()).isEqualTo(PublishabilityStatus.DEGRADED));
    }

    @Test
    void shouldNotThrowException_when_sidecarWriteFails() {
        // 软失败：sidecar 回写抛异常 → evaluate 不抛 + 返回值完整
        String tweetId = "test-tweet-17";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-17")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("下载失败")
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        TweetMediaArchiveWriter failingWriter = mock(TweetMediaArchiveWriter.class);
        TweetPublishabilityGate failingGate = new TweetPublishabilityGate(mockRecoveryService, failingWriter);
        when(failingWriter.updateMedia(eq(tweetId), any(LocalDateTime.class), eq("media-17"), any()))
                .thenThrow(new NonRetryableException("sidecar 写入失败", null));

        // 软失败：不抛异常
        PublishabilityResult result = failingGate.evaluate(tweet);

        // 返回值仍然完整，状态是 DEGRADED（下载失败）
        assertThat(result.getTweetId()).isEqualTo(tweetId);
        assertThat(result.getMediaDecisions()).hasSize(1);
        assertThat(result.getMediaDecisions().get(0).getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
    }

    // ========== T5.5: 双源容错 ==========

    @Test
    void shouldReturnPublishable_when_emptyMediaStatesAndTextAvailable() {
        // getMediaStates 返回空列表 + 文本可用 → 推文 PUBLISHABLE（无媒体）
        String tweetId = "test-tweet-18";
        Tweet tweet = createTweetWithText(tweetId, "文本可用");

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.PUBLISHABLE);
        assertThat(result.getMediaDecisions()).isEmpty();
    }

    @Test
    void shouldReturnUnknown_when_tweetMediaMissingFromRuntimeStates() {
        String tweetId = "test-tweet-missing-state";
        TweetMedia photo = TweetMedia.builder()
                .id("missing-media")
                .type(TweetMediaType.PHOTO)
                .build();
        Tweet tweet = createTweetWithText(tweetId, "文本可用")
                .toBuilder()
                .media(List.of(photo))
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.UNKNOWN);
        assertThat(result.getMediaDecisions()).singleElement()
                .satisfies(decision -> {
                    assertThat(decision.getMediaId()).isEqualTo("missing-media");
                    assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.UNKNOWN);
                    assertThat(decision.getReason()).isEqualTo("媒体不在运行时状态中，无法评估");
                });
    }

    @Test
    void shouldNotPublishMedia_when_localPathResolvesToDirectory() throws Exception {
        String tweetId = "test-tweet-dir";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        String localPath = "media/twitter/2026-08-15/test-tweet-dir/directory-media";
        Files.createDirectories(tempDir.resolve(localPath));
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("dir-media")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath(localPath)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getMediaDecisions()).singleElement()
                .satisfies(decision -> {
                    assertThat(decision.getStatus()).isEqualTo(PublishabilityStatus.DEGRADED);
                    assertThat(decision.getReason()).contains("已下载文件缺失");
                });
    }

    @Test
    void shouldReturnUnknown_withLogWarn_when_tweetIdBlank() {
        // tweetId blank → UNKNOWN + log.warn（不抛）
        Tweet tweet = Tweet.builder()
                .id("") // blank
                .content("测试文本")
                .publishedAt(LocalDateTime.now())
                .media(List.of())
                .build();

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetStatus()).isEqualTo(PublishabilityStatus.UNKNOWN);
        assertThat(result.getTweetReason()).contains("推文 ID 为空或媒体状态查询失败");
    }

    // ========== T5.7: N4 断言（reason 不含完整 URL） ==========

    @Test
    void shouldTruncateLongReason_when_failureReasonExceedsLimit() {
        // 构造超长的 failureReason 输入，验证截断到 200 code points
        String tweetId = "test-tweet-19";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        String longUrlWithParams = "https://example.com/very/long/path/image.jpg?param1=value1&param2=value2&token=xyz12345678901234567890123456789012345678901234567890123456789012345678901234567890";
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-19")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason("下载失败，URL: " + longUrlWithParams)
                .retryable(false)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        String reason = result.getMediaDecisions().get(0).getReason();
        assertThat(reason).isNotNull();
        assertThat(reason).contains("下载失败");
        assertThat(reason).doesNotContain("http");
        assertThat(reason.codePointCount(0, reason.length())).isLessThanOrEqualTo(200);
    }

    @Test
    void shouldKeepRetryableReasonWithinLimit_when_suffixIsAppended() {
        String tweetId = "test-tweet-retryable-long";
        Tweet tweet = createTweetWithText(tweetId, "测试推文");
        String longReason = "下载失败 " + "错".repeat(240);
        MediaRuntimeItem item = MediaRuntimeItem.builder()
                .mediaId("media-retryable")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.FAILED)
                .failureReason(longReason)
                .retryable(true)
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of(item));

        PublishabilityResult result = gate.evaluate(tweet);

        String reason = result.getMediaDecisions().get(0).getReason();
        assertThat(reason.codePointCount(0, reason.length())).isLessThanOrEqualTo(200);
    }

    @Test
    void shouldKeepTweetReasonWithinLimit_when_restrictionDetailIsLong() {
        String tweetId = "test-tweet-long-note";
        Tweet tweet = Tweet.builder()
                .id(tweetId)
                .content("文本可用")
                .rawText("文本可用")
                .formattedText("文本可用")
                .accessStatus(TweetAccessStatus.RESTRICTED)
                .restrictionDetail("访问受限 " + "原".repeat(240))
                .publishedAt(PUBLISHED_AT)
                .media(List.of())
                .build();

        when(mockRecoveryService.getMediaStates(eq(tweetId), any(LocalDateTime.class)))
                .thenReturn(List.of());

        PublishabilityResult result = gate.evaluate(tweet);

        assertThat(result.getTweetReason().codePointCount(0, result.getTweetReason().length()))
                .isLessThanOrEqualTo(200);
    }

    // ========== 辅助方法 ==========

    private Tweet createTweetWithText(String tweetId, String text) {
        return Tweet.builder()
                .id(tweetId)
                .content(text)
                .rawText(text)
                .formattedText(text)
                .publishedAt(PUBLISHED_AT)
                .media(List.of())
                .build();
    }

    private void createNonEmptyFile(String localPath) {
        try {
            Path file = tempDir.resolve(localPath);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[]{1, 2, 3});
        } catch (Exception e) {
            throw new AssertionError("测试文件创建失败: " + localPath, e);
        }
    }
}
