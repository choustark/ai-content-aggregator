package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 8.4: 微信正文图片准备编排器单元测试.
 *
 * <p>采用 Epic 3 软失败测试范式: mock {@link WeChatBodyImageUploadProbe} (外部微信调用) +
 * 真实 {@link TweetMediaArchiveWriter} (TempDir 构造), sidecar 回写用真实文件断言.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WeChatMediaPreparerTest {

    private static final String TWEET_ID = "2090838453126566066";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);
    private static final String WECHAT_URL = "https://mmbiz.qpic.cn/mmbiz_png/abc123/640?wx_fmt=png";
    private static final String WECHAT_URL_HOST = "mmbiz.qpic.cn";

    @TempDir
    Path tempDir;

    @Mock
    WeChatBodyImageUploadProbe uploadProbe;

    TweetMediaArchiveWriter writer;
    WeChatMediaPreparer preparer;

    @BeforeEach
    void setUp() {
        TwitterMediaProperties mediaProperties = new TwitterMediaProperties();
        mediaProperties.setBaseDirectory(tempDir.toString());
        ArchiverProperties archiverProperties = new ArchiverProperties();
        archiverProperties.setBaseDirectory(tempDir.toString());
        writer = new TweetMediaArchiveWriter(mediaProperties, archiverProperties,
                new ObjectMapper().findAndRegisterModules());
        preparer = new WeChatMediaPreparer(uploadProbe, Optional.of(writer));
    }

    // ===== AC1/AC2: 成功上传 + sidecar 回写 =====

    @Test
    void should_upload_photo_and_writeback_sidecar_when_local_file_ready() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo1.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skipCount()).isZero();
        assertThat(result.failCount()).isZero();
        assertThat(result.mediaStatuses()).hasSize(1);
        assertThat(result.mediaStatuses().get(0).status()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(result.mediaStatuses().get(0).wechatUrl()).isEqualTo(WECHAT_URL);

        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getWechatUrl()).isEqualTo(WECHAT_URL);
        assertThat(sidecarMedia.getFailureReason()).isNull();
        // 未混用字段: 正文图片不写 wechatMediaId (uploadimg 只返回 url)
        assertThat(sidecarMedia.getWechatMediaId()).isNull();
    }

    // ===== AC7: 幂等重入 =====

    @Test
    void should_skip_upload_when_media_already_uploaded() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        verifyNoInteractions(uploadProbe);
        // 幂等跳过契约: status=SKIPPED 但 wechatUrl 必须传播给调用方 (CR 2026-08-28 Patch#11)
        assertThat(result.mediaStatuses().get(0).status()).isEqualTo(MediaUploadStatus.SKIPPED);
        assertThat(result.mediaStatuses().get(0).wechatUrl()).isEqualTo(WECHAT_URL);
        // sidecar 保持不变
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getWechatUrl()).isEqualTo(WECHAT_URL);
    }

    // ===== AC7 判别性: sidecar 是唯一权威源，不信任传入对象 (CR 2026-08-28 Patch#6) =====

    @Test
    void should_skip_upload_when_sidecar_uploaded_but_input_pending() throws IOException {
        writeFile("photo1.png");
        // sidecar = UPLOADED (权威源)，传入对象 = PENDING —— 必须 skip，证明幂等判据读 sidecar
        TweetMedia sidecarPhoto = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(sidecarPhoto));
        TweetMedia inputPhoto = photo("media-1", "photo1.png").build();

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(inputPhoto));

        assertThat(result.skipCount()).isEqualTo(1);
        verifyNoInteractions(uploadProbe);
        assertThat(result.mediaStatuses().get(0).wechatUrl()).isEqualTo(WECHAT_URL);
    }

    @Test
    void should_upload_when_input_uploaded_but_sidecar_pending() throws IOException {
        writeFile("photo1.png");
        // sidecar = PENDING (权威源)，传入对象 = UPLOADED —— 必须重新上传，证明不信任传入对象
        TweetMedia sidecarPhoto = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(sidecarPhoto));
        TweetMedia inputPhoto = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .build();

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo1.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(inputPhoto));

        assertThat(result.successCount()).isEqualTo(1);
        verify(uploadProbe).upload(any(Path.class));
    }

    // ===== CR 2026-08-28 Patch#2: 输入列表与 sidecar 错位时回写命中同一媒体 =====

    @Test
    void should_writeback_to_matched_sidecar_item_when_input_order_differs() throws IOException {
        writeFile("photo1.png");
        writeFile("photo2.png");
        TweetMedia photoA = photo("media-a", "photo1.png").build();
        TweetMedia photoB = photo("media-b", "photo2.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photoA, photoB));

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo2.png", 3));

        // 输入只含 B (子集): 状态判定与回写必须都绑定 B，不得把 B 的 URL 写到 A 名下
        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photoB));

        assertThat(result.successCount()).isEqualTo(1);
        TweetMedia sidecarB = readSidecarMedia("media-b");
        assertThat(sidecarB.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarB.getWechatUrl()).isEqualTo(WECHAT_URL);
        TweetMedia sidecarA = readSidecarMedia("media-a");
        assertThat(sidecarA.getUploadStatus()).isNotEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarA.getWechatUrl()).isNull();
    }

    @Test
    void should_mark_failed_without_calling_probe_when_photo_absent_from_sidecar() throws IOException {
        writeFile("photo1.png");
        TweetMedia photoA = photo("media-a", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photoA));
        // 输入多一项 sidecar 不存在且 id 不匹配的 PHOTO: 定位失败 → FAILED，不调微信
        TweetMedia photoExtra = photo("media-extra", "photo1.png").build();

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo1.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photoA, photoExtra));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        verify(uploadProbe, times(1)).upload(any(Path.class));
        // sidecar 定位失败的媒体在结果对象中 FAILED；其本就不在 sidecar，无法落账 (软失败只告警)
        assertThat(result.mediaStatuses().get(1).status()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(result.mediaStatuses().get(1).failureReason()).contains("sidecar 未命中");
        assertThat(readSidecarMedia("media-a").getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
    }

    // ===== CR 2026-08-28 Patch#3: 失败路径 failureReason URL 脱敏 =====

    @Test
    void should_scrub_url_from_failure_reason_when_probe_error_contains_url(CapturedOutput capturedOutput)
            throws IOException {
        writeFile("photo1.png");
        TweetMedia photoItem = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photoItem));

        when(uploadProbe.upload(any(Path.class))).thenThrow(new RetryableException(
                ErrorCode.WECHAT_API_ERROR,
                "微信 mediaImgUpload 失败: 连接 https://api.weixin.qq.com/cgi-bin/media/uploadimg?access_token=SECRET 超时"));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photoItem));

        assertThat(result.failCount()).isEqualTo(1);
        String failureReason = result.mediaStatuses().get(0).failureReason();
        assertThat(failureReason).contains("<url>");
        assertThat(failureReason).doesNotContain("api.weixin.qq.com/cgi-bin");
        assertThat(failureReason).doesNotContain("access_token=SECRET");
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getFailureReason()).contains("<url>");
        assertThat(sidecarMedia.getFailureReason()).doesNotContain("access_token=SECRET");
        assertThat(capturedOutput.getAll()).doesNotContain("access_token=SECRET");
    }

    @Test
    void should_mark_failed_not_retryable_when_probe_throws_runtime_exception() throws IOException {
        writeFile("photo1.png");
        TweetMedia photoItem = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photoItem));

        when(uploadProbe.upload(any(Path.class)))
                .thenThrow(new IllegalStateException("WxJava frame\nunexpected state"));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photoItem));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.mediaStatuses().get(0).retryable()).isFalse();
        // 单行化: 异常 message 的换行不进入 failureReason (防日志伪造)
        assertThat(result.mediaStatuses().get(0).failureReason()).doesNotContain("\n");
    }

    // ===== CR 2026-08-28 Patch#9: synthetic id 计数对齐 Archiver (photo-only 计数) =====

    @Test
    void should_use_archiver_compatible_synthetic_id_when_media_id_blank() throws IOException {
        writeFile("photo.png");
        TweetMedia video = TweetMedia.builder().type(TweetMediaType.VIDEO)
                .downloadStatus(MediaDownloadStatus.SKIPPED).build();
        TweetMedia photoNoId = TweetMedia.builder().type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-23/" + TWEET_ID + "/photo.png")
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video, photoNoId));

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(video, photoNoId));

        // VIDEO 用全列表下标 0，PHOTO 用 photo-only 计数 0 —— 与 TweetMediaArchiver 持久化的 synthetic id 一致
        assertThat(result.mediaStatuses().get(0).mediaId()).isEqualTo(TWEET_ID + ":0:video");
        assertThat(result.mediaStatuses().get(1).mediaId()).isEqualTo(TWEET_ID + ":0:photo");
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skipCount()).isEqualTo(1);
    }

    // ===== AC4: 单媒体失败降级不阻塞 =====

    @Test
    void should_degrade_single_media_when_probe_throws_retryable() throws IOException {
        writeFile("photo1.png");
        writeFile("photo2.png");
        TweetMedia photo1 = photo("media-1", "photo1.png").build();
        TweetMedia photo2 = photo("media-2", "photo2.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo1, photo2));

        when(uploadProbe.upload(any(Path.class)))
                .thenThrow(new RetryableException(ErrorCode.WECHAT_API_ERROR,
                        "微信 mediaImgUpload 失败: errcode=-1 system busy"))
                .thenReturn(new WeChatBodyImageUploadProbe.UploadedBodyImage(
                        WECHAT_URL, WECHAT_URL_HOST, "photo2.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo1, photo2));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.mediaStatuses().get(0).retryable()).isTrue();
        assertThat(result.mediaStatuses().get(0).failureReason()).contains("errcode=-1");

        TweetMedia failed = readSidecarMedia("media-1");
        assertThat(failed.getUploadStatus()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("errcode=-1");
        // 失败媒体不写 wechatUrl
        assertThat(failed.getWechatUrl()).isNull();

        TweetMedia succeeded = readSidecarMedia("media-2");
        assertThat(succeeded.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(succeeded.getWechatUrl()).isEqualTo(WECHAT_URL);
    }

    // ===== AC5: 视频/GIF 不走图片路径 =====

    @Test
    void should_skip_video_and_gif_without_calling_probe() {
        TweetMedia video = TweetMedia.builder().id("media-v").type(TweetMediaType.VIDEO)
                .downloadStatus(MediaDownloadStatus.SKIPPED).build();
        TweetMedia gif = TweetMedia.builder().id("media-g").type(TweetMediaType.GIF)
                .downloadStatus(MediaDownloadStatus.SKIPPED).build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video, gif));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(video, gif));

        assertThat(result.skipCount()).isEqualTo(2);
        assertThat(result.failCount()).isZero();
        verifyNoInteractions(uploadProbe);

        assertThat(readSidecarMedia("media-v").getUploadStatus()).isEqualTo(MediaUploadStatus.SKIPPED);
        // 8.2 决策表 degradeReason token: video/gif 两级区分，供 8.5 渲染器机器可读消费 (CR 2026-08-28 Patch#5)
        assertThat(readSidecarMedia("media-v").getFailureReason()).contains("video_embed_unverified");
        assertThat(readSidecarMedia("media-g").getUploadStatus()).isEqualTo(MediaUploadStatus.SKIPPED);
        assertThat(readSidecarMedia("media-g").getFailureReason()).contains("gif_api_unverified");
    }

    // ===== AC7: 本地文件缺失 =====

    @Test
    void should_mark_failed_when_local_file_missing() {
        // downloadStatus=DOWNLOADED + localPath 有值, 但文件不存在
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        verifyNoInteractions(uploadProbe);
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(sidecarMedia.getFailureReason()).contains("local file missing");
    }

    @Test
    void should_mark_failed_when_photo_not_downloaded() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png")
                .downloadStatus(MediaDownloadStatus.PENDING)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        verifyNoInteractions(uploadProbe);
        assertThat(readSidecarMedia("media-1").getFailureReason()).contains("local file missing");
    }

    // ===== T1.7: writer 缺失显式 fail-fast =====

    @Test
    void should_fail_fast_when_archive_writer_absent() {
        WeChatMediaPreparer preparerWithoutWriter = new WeChatMediaPreparer(uploadProbe, Optional.empty());
        TweetMedia photo = photo("media-1", "photo1.png").build();

        assertThatThrownBy(() -> preparerWithoutWriter.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo)))
                .isInstanceOfSatisfying(NonRetryableException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NON_RETRYABLE_ERROR));
        verifyNoInteractions(uploadProbe);
    }

    // ===== AC2: 成功后清空 failureReason =====

    @Test
    void should_clear_failure_reason_when_retry_succeeds() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.FAILED)
                .failureReason("wechat network error")
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo1.png", 3));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.successCount()).isEqualTo(1);
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getWechatUrl()).isEqualTo(WECHAT_URL);
        assertThat(sidecarMedia.getFailureReason()).isNull();
    }

    // ===== AC8: 日志脱敏 =====

    @Test
    void should_not_log_sensitive_data_when_upload_completes(CapturedOutput capturedOutput) throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenReturn(
                new WeChatBodyImageUploadProbe.UploadedBodyImage(WECHAT_URL, WECHAT_URL_HOST, "photo1.png", 3));

        preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        String output = capturedOutput.getAll();
        // 不输出完整微信 URL (N4), 只允许 urlHost 摘要 (W11)
        assertThat(output).doesNotContain(WECHAT_URL);
        assertThat(output).doesNotContain("access_token");
        // W11 七字段完整性: tweetId/mediaId/fileName/sizeBytes/urlHost/status/elapsedMs (CR 2026-08-28 Patch#7)
        assertThat(output).contains("urlHost=" + WECHAT_URL_HOST);
        assertThat(output).contains("mediaId=media-1");
        assertThat(output).contains("tweetId=" + TWEET_ID);
        assertThat(output).contains("fileName=photo1.png");
        assertThat(output).contains("sizeBytes=3");
        assertThat(output).contains("status=UPLOADED");
        assertThat(output).contains("elapsedMs=");
    }

    // ===== 边界: sidecar 缺失 (媒体未归档) =====

    @Test
    void should_mark_all_media_failed_when_sidecar_missing() {
        TweetMedia photo1 = photo("media-1", "photo1.png").build();
        TweetMedia photo2 = photo("media-2", "photo2.png").build();

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo1, photo2));

        assertThat(result.failCount()).isEqualTo(2);
        assertThat(result.successCount()).isZero();
        verifyNoInteractions(uploadProbe);
        assertThat(result.mediaStatuses()).allMatch(s -> s.failureReason() != null
                && s.failureReason().contains("sidecar"));
    }

    // ===== helpers =====

    private TweetMedia.TweetMediaBuilder photo(String id, String filename) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-23/" + TWEET_ID + "/" + filename);
    }

    private Path writeFile(String filename) throws IOException {
        Path dir = writer.resolveArchiveDir(TWEET_ID, PUBLISHED_AT);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, new byte[]{1, 2, 3});
        return file;
    }

    private TweetMedia readSidecarMedia(String mediaId) {
        Optional<MediaArchiveRecord> record = writer.readSidecar(TWEET_ID, PUBLISHED_AT);
        assertThat(record).isPresent();
        return record.get().getMedia().stream()
                .filter(m -> mediaId.equals(m.getId()))
                .findFirst()
                .orElseThrow();
    }
}
