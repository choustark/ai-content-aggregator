package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    WeChatVideoMediaAdapter videoAdapter;

    /** Story 10.11 测试用: VIDEO 永久素材 media_id (前 6 位以内落日志, 测试内为固定假值). */
    private static final String VIDEO_MEDIA_ID = "wxvid001-media-id";

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

    // ===== Story 9.1 Task 3: 上传前 publishability=BLOCKED 拦截 (AC 5) =====

    @Test
    void should_skip_blocked_photo_before_upload_without_calling_probe() throws IOException {
        writeFile("photo1.png");
        // gate 已判 BLOCKED 的 PHOTO: 本地就绪也不得上传 (AC 5: 上传前必须跳过 BLOCKED 媒体)
        TweetMedia photo = photo("media-1", "photo1.png")
                .publishability(PublishabilityStatus.BLOCKED)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.failCount()).isZero();
        verifyNoInteractions(uploadProbe);
        assertThat(result.mediaStatuses().get(0).status()).isEqualTo(MediaUploadStatus.SKIPPED);
        assertThat(result.mediaStatuses().get(0).failureReason()).contains("BLOCKED");
        // sidecar 回写 SKIPPED + 原因, publishability 字段本身不被 preparer 改写 (三字段所有权 AD-13)
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.SKIPPED);
        assertThat(sidecarMedia.getPublishability()).isEqualTo(PublishabilityStatus.BLOCKED);
        assertThat(sidecarMedia.getFailureReason()).contains("BLOCKED");
    }

    @Test
    void should_skip_blocked_photo_when_gate_result_not_written_back_to_sidecar() throws IOException {
        writeFile("photo1.png");
        TweetMedia inputPhoto = photo("media-1", "photo1.png")
                .publishability(PublishabilityStatus.BLOCKED)
                .build();
        TweetMedia sidecarPhoto = photo("media-1", "photo1.png")
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(sidecarPhoto));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(inputPhoto));

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.failCount()).isZero();
        verifyNoInteractions(uploadProbe);
        assertThat(readSidecarMedia("media-1").getUploadStatus()).isEqualTo(MediaUploadStatus.SKIPPED);
    }

    @Test
    void should_still_skip_uploaded_media_even_if_blocked() throws IOException {
        writeFile("photo1.png");
        // 已成功上传的媒体幂等优先: 即使 gate 后判 BLOCKED 也不重传/不改写,
        // 嵌入侧 (MarkdownMediaInserter) 谓词负责拒绝 BLOCKED 媒体进正文
        TweetMedia photo = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .publishability(PublishabilityStatus.BLOCKED)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.skipCount()).isEqualTo(1);
        verifyNoInteractions(uploadProbe);
        assertThat(readSidecarMedia("media-1").getWechatUrl()).isEqualTo(WECHAT_URL);
    }

    // ===== Story 9.1 Task 3: 后续失败不得覆盖既有成功 wechatUrl (AC 5 / AD-13) =====

    @Test
    void should_preserve_existing_wechat_url_when_retry_upload_fails() throws IOException {
        writeFile("photo1.png");
        // 历史状态: 曾成功上传拿到 wechatUrl, 但后续某次状态被写为 FAILED (如回写竞态)
        TweetMedia photo = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.FAILED)
                .wechatUrl(WECHAT_URL)
                .failureReason("stale failure")
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenThrow(new RetryableException(
                ErrorCode.WECHAT_API_ERROR, "微信 mediaImgUpload 失败: errcode=-1 system busy"));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        // 失败回写只更新 uploadStatus/failureReason, 绝不清空既有成功 wechatUrl (AD-13)
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(sidecarMedia.getWechatUrl()).isEqualTo(WECHAT_URL);
    }

    // ===== Story 10.8: 45009/40164 独立分类 + wechatPrepare 阶段证据 =====

    /** Story 10.8 (I/O 矩阵「45009/临时错误」): 限流分类 RETRYABLE/RATE_LIMITED + RETRY_SCHEDULED 阶段证据. */
    @Test
    void should_write_retry_scheduled_phase_when_probe_throws_rate_limited_45009() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenThrow(new RetryableException(
                ErrorCode.WECHAT_RATE_LIMITED,
                "微信 mediaImgUpload 失败: errcode=45009 reach max api daily quota limit"));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.hasRetryableFailure()).isTrue();
        assertThat(result.hasTerminalFailure()).isFalse();
        assertThat(result.mediaStatuses().get(0).retryable()).isTrue();
        assertThat(result.mediaStatuses().get(0).failureClass())
                .isEqualTo(MediaPreparationResult.FailureClass.RATE_LIMITED);

        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(sidecarMedia.getWechatPrepare()).isNotNull();
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED);
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(1);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNotNull();
        assertThat(sidecarMedia.getWechatPrepare().getErrorClass()).isEqualTo("RETRYABLE");
        assertThat(sidecarMedia.getWechatPrepare().getErrorCode()).isEqualTo("WECHAT_RATE_LIMITED");
        assertThat(sidecarMedia.getWechatPrepare().getErrorSummary()).contains("45009");
    }

    /** Story 10.8 (I/O 矩阵「40164 环境阻塞」): 终态 ENVIRONMENT_BLOCKED 证据 + nextRetryAt=null. */
    @Test
    void should_write_environment_blocked_phase_when_probe_throws_ip_whitelist_40164() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));

        when(uploadProbe.upload(any(Path.class))).thenThrow(new NonRetryableException(
                ErrorCode.WECHAT_ENVIRONMENT_BLOCKED,
                "微信 mediaImgUpload 失败: errcode=40164 invalid ip, not in whitelist"));

        MediaPreparationResult result = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.hasTerminalFailure()).isTrue();
        assertThat(result.hasRetryableFailure()).isFalse();
        assertThat(result.mediaStatuses().get(0).retryable()).isFalse();
        assertThat(result.mediaStatuses().get(0).failureClass())
                .isEqualTo(MediaPreparationResult.FailureClass.ENVIRONMENT_BLOCKED);

        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getWechatPrepare()).isNotNull();
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.ENVIRONMENT_BLOCKED);
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(1);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNull();
        assertThat(sidecarMedia.getWechatPrepare().getErrorClass()).isEqualTo("TERMINAL");
        assertThat(sidecarMedia.getWechatPrepare().getErrorCode()).isEqualTo("WECHAT_ENVIRONMENT_BLOCKED");
    }

    /** Story 10.8: attempt 单调递增 — 二次失败 attempt=2 且 nextRetryAt 按新 attempt 退避. */
    @Test
    void should_increment_attempt_on_repeated_retryable_failure() throws IOException {
        writeFile("photo1.png");
        TweetMedia photo = photo("media-1", "photo1.png").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(photo));
        when(uploadProbe.upload(any(Path.class))).thenThrow(new RetryableException(
                ErrorCode.WECHAT_RATE_LIMITED, "errcode=45009"));

        preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));
        MediaPreparationResult second = preparer.prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(photo));

        assertThat(second.failCount()).isEqualTo(1);
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED);
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(2);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNotNull();
    }

    /**
     * Story 10.8: 生成器级静态 helper — preparer 级崩溃路径的 RETRY_SCHEDULED 回写,
     * 只对「PHOTO 且未进入 SUCCEEDED/终态」的媒体落证据, 已成功媒体不被覆盖。
     */
    @Test
    void should_mark_sidecar_prepare_retry_scheduled_only_for_pending_photos() throws IOException {
        writeFile("photo1.png");
        writeFile("photo2.png");
        TweetMedia pending = photo("media-pending", "photo1.png").build();
        TweetMedia succeeded = photo("media-succeeded", "photo2.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(pending, succeeded));

        boolean mutated = WeChatMediaPreparer.markSidecarPrepareRetryScheduled(
                writer, TWEET_ID, PUBLISHED_AT,
                new com.choucj.aiaggregator.task.queue.RetryPolicyProperties(),
                "WECHAT_RATE_LIMITED", "errcode=45009 quota");

        assertThat(mutated).isTrue();
        TweetMedia pendingSidecar = readSidecarMedia("media-pending");
        assertThat(pendingSidecar.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED);
        assertThat(pendingSidecar.getWechatPrepare().getAttempt()).isEqualTo(1);
        assertThat(pendingSidecar.getWechatPrepare().getNextRetryAt()).isNotNull();
        // 已成功上传的媒体不被覆盖: wechatPrepare 仍为 SUCCEEDED 投影, uploadStatus 保持 UPLOADED
        assertThat(readSidecarMedia("media-succeeded").getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED);
        assertThat(readSidecarMedia("media-succeeded").getUploadStatus())
                .isEqualTo(MediaUploadStatus.UPLOADED);
    }

    /** Story 10.8: 已处于 RETRY_SCHEDULED 的媒体不被 helper 重写 (attempt 不双计, 证据不被覆盖). */
    @Test
    void should_skip_retry_scheduled_media_in_mark_sidecar_prepare_retry_scheduled() throws IOException {
        writeFile("photo1.png");
        TweetMedia scheduled = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.FAILED)
                .failureReason("preparer 细粒度原因")
                .wechatPrepare(com.choucj.aiaggregator.source.twitter.model.MediaPhaseState.builder()
                        .status(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED)
                        .attempt(2)
                        .nextRetryAt(PUBLISHED_AT.plusMinutes(1))
                        .errorClass("RETRYABLE")
                        .errorCode("WECHAT_RATE_LIMITED")
                        .errorSummary("preparer 已写证据")
                        .updatedAt(PUBLISHED_AT)
                        .build())
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(scheduled));

        boolean mutated = WeChatMediaPreparer.markSidecarPrepareRetryScheduled(
                writer, TWEET_ID, PUBLISHED_AT,
                new com.choucj.aiaggregator.task.queue.RetryPolicyProperties(),
                "WECHAT_RATE_LIMITED", "生成器级覆盖原因");

        assertThat(mutated).isFalse();
        TweetMedia sidecarMedia = readSidecarMedia("media-1");
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(2);
        assertThat(sidecarMedia.getWechatPrepare().getErrorSummary()).isEqualTo("preparer 已写证据");
        assertThat(sidecarMedia.getFailureReason()).isEqualTo("preparer 细粒度原因");
    }

    // ===== Story 10.11: VIDEO 永久素材生产分支 (I/O 矩阵) =====

    /** I/O 矩阵「正常上传引用」: 上传成功 → mediaId 写回 + wechatPrepare=SUCCEEDED. */
    @Test
    void should_upload_video_and_writeback_media_id_when_local_file_ready() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));
        WeChatMediaPreparer videoPreparer = videoPreparer();
        when(videoAdapter.uploadPermanentVideo(any(Path.class), anyString(), anyString()))
                .thenReturn(uploadedVideo());

        MediaPreparationResult result = videoPreparer.prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), "测试文章标题", "测试文章简介");

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.skipCount()).isZero();
        assertThat(result.failCount()).isZero();
        assertThat(result.mediaStatuses().get(0).status()).isEqualTo(MediaUploadStatus.UPLOADED);

        // OQ1: title = 文章标题 + tweetId 后缀; description = 文章简介
        org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> description = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(videoAdapter).uploadPermanentVideo(any(Path.class), title.capture(), description.capture());
        assertThat(title.getValue()).contains("测试文章标题").contains("tw-" + TWEET_ID);
        assertThat(description.getValue()).isEqualTo("测试文章简介");

        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getWechatVideoMediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(sidecarMedia.getWechatUrl()).isNull();
        assertThat(sidecarMedia.getFailureReason()).isNull();
        assertThat(sidecarMedia.getWechatPrepare()).isNotNull();
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED);
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(1);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNull();
    }

    /** I/O 矩阵「幂等跳过」: 已有 mediaId + wechatPrepare=SUCCEEDED → 零上传请求, 成功字段不回退. */
    @Test
    void should_skip_video_upload_idempotently_when_sidecar_already_prepared() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatVideoMediaId(VIDEO_MEDIA_ID)
                .wechatPrepare(com.choucj.aiaggregator.source.twitter.model.MediaPhaseState.builder()
                        .status(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED)
                        .attempt(1)
                        .updatedAt(PUBLISHED_AT)
                        .build())
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));

        MediaPreparationResult result = videoPreparer().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        verifyNoInteractions(videoAdapter);
        assertThat(result.mediaStatuses().get(0).failureReason()).contains("幂等跳过");
        // 既有成功字段不回退
        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getWechatVideoMediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED);
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
    }

    /** I/O 矩阵「环境阻断」: 40164 → ENVIRONMENT_BLOCKED 终态, 不重试. */
    @Test
    void should_write_environment_blocked_phase_when_video_adapter_throws_40164() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));
        when(videoAdapter.uploadPermanentVideo(any(Path.class), anyString(), anyString()))
                .thenThrow(new NonRetryableException(ErrorCode.WECHAT_ENVIRONMENT_BLOCKED,
                        "微信 materialFileUpload(video) 失败: errcode=40164 invalid ip"));

        MediaPreparationResult result = videoPreparer().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.hasTerminalFailure()).isTrue();
        assertThat(result.mediaStatuses().get(0).failureClass())
                .isEqualTo(MediaPreparationResult.FailureClass.ENVIRONMENT_BLOCKED);
        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.ENVIRONMENT_BLOCKED);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNull();
        assertThat(sidecarMedia.getWechatVideoMediaId()).isNull();
    }

    /** I/O 矩阵「临时错误」: Retryable (45009/网络) → RETRY_SCHEDULED + nextRetryAt 有限重试. */
    @Test
    void should_write_retry_scheduled_phase_when_video_adapter_throws_retryable() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));
        when(videoAdapter.uploadPermanentVideo(any(Path.class), anyString(), anyString()))
                .thenThrow(new RetryableException(ErrorCode.WECHAT_RATE_LIMITED,
                        "微信 materialFileUpload(video) 失败: errcode=45009 reach max api daily quota limit"));

        MediaPreparationResult result = videoPreparer().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.hasRetryableFailure()).isTrue();
        assertThat(result.mediaStatuses().get(0).retryable()).isTrue();
        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED);
        assertThat(sidecarMedia.getWechatPrepare().getAttempt()).isEqualTo(1);
        assertThat(sidecarMedia.getWechatPrepare().getNextRetryAt()).isNotNull();
        assertThat(sidecarMedia.getWechatPrepare().getErrorClass()).isEqualTo("RETRYABLE");
        assertThat(sidecarMedia.getWechatVideoMediaId()).isNull();
    }

    /** I/O 矩阵「本地前置失败」: 文件缺失 → 不发起上传, FAILED_TERMINAL (NonRetryable). */
    @Test
    void should_mark_video_terminal_when_local_file_missing() {
        TweetMedia video = video("media-v", "missing.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));

        MediaPreparationResult result = videoPreparer().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        assertThat(result.failCount()).isEqualTo(1);
        verifyNoInteractions(videoAdapter);
        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.FAILED);
        assertThat(sidecarMedia.getFailureReason()).contains("local file missing");
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(sidecarMedia.getWechatPrepare().getErrorClass()).isEqualTo("TERMINAL");
    }

    /** I/O 矩阵「回写未命中」防御口径: sidecar 未命中该媒体 → 不记成功, FAILED + 可诊断原因. */
    @Test
    void should_mark_video_failed_defensively_when_sidecar_misses_media() throws IOException {
        writeFile("video.mp4");
        // sidecar 只有 photo 项 (已幂等成功), 传入 video 下标越界 → ref null → 本地就绪预检失败 (不调上传)
        TweetMedia sidecarPhoto = photo("media-1", "photo1.png")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(WECHAT_URL)
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(sidecarPhoto));
        TweetMedia inputVideo = video("media-v", "video.mp4").build();

        MediaPreparationResult result = videoPreparer().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(sidecarPhoto, inputVideo), null, null);

        assertThat(result.failCount()).isEqualTo(1);
        verifyNoInteractions(videoAdapter);
        assertThat(result.mediaStatuses().get(1).failureReason()).contains("sidecar");
    }

    /** wechat.mp.video.enabled=false → VIDEO 保持 10.8 降级语义 (SKIPPED + video_embed_unverified). */
    @Test
    void should_fall_back_to_legacy_skip_when_video_preparation_disabled() {
        TweetMedia video = video("media-v", "video.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));

        MediaPreparationResult result = videoPreparerDisabled().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        assertThat(result.skipCount()).isEqualTo(1);
        assertThat(result.failCount()).isZero();
        verifyNoInteractions(videoAdapter);
        assertThat(readSidecarMedia("media-v").getUploadStatus()).isEqualTo(MediaUploadStatus.SKIPPED);
        assertThat(readSidecarMedia("media-v").getFailureReason()).contains("video_embed_unverified");
    }

    /** AC3 冻结: enabled=false 也不得盖写已 SUCCEEDED+mediaId 的 VIDEO — 幂等预检先于 enabled gate. */
    @Test
    void should_skip_video_idempotently_even_when_preparation_disabled() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatVideoMediaId(VIDEO_MEDIA_ID)
                .wechatPrepare(com.choucj.aiaggregator.source.twitter.model.MediaPhaseState.builder()
                        .status(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED)
                        .attempt(1)
                        .updatedAt(PUBLISHED_AT)
                        .build())
                .build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));

        MediaPreparationResult result = videoPreparerDisabled().prepareMedia(
                TWEET_ID, PUBLISHED_AT, List.of(video), null, null);

        // 幂等跳过 + 零写回: SKIPPED 降级分支不得盖写 uploadStatus/failureReason
        assertThat(result.skipCount()).isEqualTo(1);
        verifyNoInteractions(videoAdapter);
        assertThat(result.mediaStatuses().get(0).failureReason()).contains("幂等跳过");
        TweetMedia sidecarMedia = readSidecarMedia("media-v");
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getFailureReason()).isNull();
        assertThat(sidecarMedia.getWechatVideoMediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(sidecarMedia.getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.SUCCEEDED);
    }

    /** OQ1: 长标题截断后 tw-{tweetId} 锚点必须完整保留 (不产出残段). */
    @Test
    void should_preserve_tweet_anchor_suffix_when_title_exceeds_limit() throws IOException {
        writeFile("video.mp4");
        TweetMedia video = video("media-v", "video.mp4").build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(video));
        when(videoAdapter.uploadPermanentVideo(any(Path.class), anyString(), anyString()))
                .thenReturn(uploadedVideo());
        String longTitle = "很长的文章标题".repeat(12);

        videoPreparer().prepareMedia(TWEET_ID, PUBLISHED_AT, List.of(video), longTitle, "简介");

        org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(videoAdapter).uploadPermanentVideo(any(Path.class), title.capture(), anyString());
        String value = title.getValue();
        assertThat(value.endsWith("tw-" + TWEET_ID)).isTrue();
        assertThat(value.codePoints().count()).isLessThanOrEqualTo(64);
        assertThat(value).startsWith("很长的文章标题");
    }

    /** 10.11: 生成器级静态 helper 同步覆盖 VIDEO — 未成功 VIDEO 落 RETRY_SCHEDULED, GIF 仍不进入. */
    @Test
    void should_mark_sidecar_prepare_retry_scheduled_for_pending_video_not_gif() throws IOException {
        writeFile("video.mp4");
        TweetMedia pendingVideo = video("media-v", "video.mp4").build();
        TweetMedia gif = TweetMedia.builder().id("media-g").type(TweetMediaType.GIF)
                .downloadStatus(MediaDownloadStatus.SKIPPED).build();
        writer.writeSidecar(TWEET_ID, PUBLISHED_AT, List.of(pendingVideo, gif));

        boolean mutated = WeChatMediaPreparer.markSidecarPrepareRetryScheduled(
                writer, TWEET_ID, PUBLISHED_AT,
                new com.choucj.aiaggregator.task.queue.RetryPolicyProperties(),
                "WECHAT_API_ERROR", "网络超时");

        assertThat(mutated).isTrue();
        assertThat(readSidecarMedia("media-v").getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.RETRY_SCHEDULED);
        // GIF 保持 DEFERRED 语义不被触碰
        assertThat(readSidecarMedia("media-g").getWechatPrepare().getStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus.DEFERRED);
    }

    // ===== helpers =====

    private WeChatMediaPreparer videoPreparer() {
        return new WeChatMediaPreparer(uploadProbe, Optional.of(writer),
                new com.choucj.aiaggregator.task.queue.RetryPolicyProperties(),
                videoAdapter, new WeChatProperties());
    }

    private WeChatMediaPreparer videoPreparerDisabled() {
        WeChatProperties properties = new WeChatProperties();
        properties.getVideo().setEnabled(false);
        return new WeChatMediaPreparer(uploadProbe, Optional.of(writer),
                new com.choucj.aiaggregator.task.queue.RetryPolicyProperties(),
                videoAdapter, properties);
    }

    private TweetMedia.TweetMediaBuilder video(String id, String filename) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.VIDEO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-23/" + TWEET_ID + "/" + filename);
    }

    private static WeChatVideoMediaAdapter.UploadedVideo uploadedVideo() {
        return new WeChatVideoMediaAdapter.UploadedVideo(VIDEO_MEDIA_ID, "video.mp4", 3, true, 1);
    }

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
