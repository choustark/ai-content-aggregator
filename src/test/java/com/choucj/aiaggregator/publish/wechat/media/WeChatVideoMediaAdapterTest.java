package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpMaterialService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialVideoInfoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 10.11: VIDEO 微信永久素材生产上传适配器单元测试 (Spike 10.9 §8 契约).
 *
 * <p>覆盖 I/O 矩阵适配器职责面: 上传成功 + 回读证据 / 空 mediaId / 40164 / 40005 / 45009 /
 * 网络层 -99 校正 Retryable / 本地前置校验 (缺失/空/超限) / 回读耗尽仅 warn 不终态化 /
 * 框架异常包装。默认零外部网络 (mock WxMpService)。
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WeChatVideoMediaAdapterTest {

    private static final String VIDEO_MEDIA_ID = "wxvid001mediaid";

    @TempDir
    private Path tempDir;

    @Mock
    private WxMpService wxMpService;

    @Mock
    private WxMpMaterialService materialService;

    private WeChatProperties properties;
    private WeChatVideoMediaAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new WeChatProperties();
        // 回读立即成功/立即耗尽, 测试零等待
        properties.getVideo().setReadbackAttempts(1);
        properties.getVideo().setReadbackIntervalMs(0L);
        adapter = new WeChatVideoMediaAdapter(wxMpService, properties);
    }

    // ===== 上传成功 + 回读证据 =====

    @Test
    void shouldUploadPermanentVideoAndReturnMediaIdWithReadbackEvidence(CapturedOutput output) throws Exception {
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(VIDEO_MEDIA_ID);
        WxMpMaterialVideoInfoResult infoResult = new WxMpMaterialVideoInfoResult();
        infoResult.setTitle("素材标题");
        infoResult.setDownUrl("https://servable.weixin.qq.com/video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(uploadResult);
        when(materialService.materialVideoInfo(VIDEO_MEDIA_ID)).thenReturn(infoResult);

        WeChatVideoMediaAdapter.UploadedVideo uploaded =
                adapter.uploadPermanentVideo(video, "标题 tw-123", "简介");

        assertThat(uploaded.mediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(uploaded.fileName()).isEqualTo("video.mp4");
        assertThat(uploaded.sizeBytes()).isPositive();
        assertThat(uploaded.readbackSucceeded()).isTrue();
        assertThat(uploaded.readbackAttempts()).isEqualTo(1);
        assertThat(output)
                .contains("微信永久视频素材上传成功")
                .contains("mediaIdPrefix=" + VIDEO_MEDIA_ID.substring(0, 6))
                .doesNotContain(VIDEO_MEDIA_ID);
    }

    @Test
    void shouldPassTitleAndDescriptionToMaterial(CapturedOutput output) throws Exception {
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(VIDEO_MEDIA_ID);
        WxMpMaterialVideoInfoResult infoResult = new WxMpMaterialVideoInfoResult();
        infoResult.setTitle("t");
        infoResult.setDownUrl("https://servable.weixin.qq.com/video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        ArgumentCaptor<WxMpMaterial> materialCaptor = ArgumentCaptor.forClass(WxMpMaterial.class);
        when(materialService.materialFileUpload(eq("video"), materialCaptor.capture())).thenReturn(uploadResult);
        when(materialService.materialVideoInfo(VIDEO_MEDIA_ID)).thenReturn(infoResult);

        adapter.uploadPermanentVideo(video, "文章标题 tw-123", "文章简介");

        assertThat(materialCaptor.getValue().getVideoTitle()).isEqualTo("文章标题 tw-123");
        assertThat(materialCaptor.getValue().getVideoIntroduction()).isEqualTo("文章简介");
    }

    // ===== 回读 best-effort =====

    /** I/O 矩阵「回读失败」: 上传成功但回读耗尽 → 仍 SUCCEEDED 语义, 仅 warn. */
    @Test
    void shouldStillSucceedWhenReadbackExhausted(CapturedOutput output) throws Exception {
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(VIDEO_MEDIA_ID);
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(uploadResult);
        when(materialService.materialVideoInfo(VIDEO_MEDIA_ID))
                .thenThrow(wxError(-1, "system busy"));

        WeChatVideoMediaAdapter.UploadedVideo uploaded =
                adapter.uploadPermanentVideo(video, "title", "desc");

        assertThat(uploaded.mediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(uploaded.readbackSucceeded()).isFalse();
        assertThat(uploaded.readbackAttempts()).isEqualTo(1);
        verify(materialService, times(1)).materialVideoInfo(VIDEO_MEDIA_ID);
        assertThat(output)
                .contains("微信永久视频素材回读失败")
                .contains("不影响上传成功");
    }

    @Test
    void shouldRetryReadbackUntilSuccessWithinConfiguredAttempts() throws Exception {
        properties.getVideo().setReadbackAttempts(3);
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(VIDEO_MEDIA_ID);
        WxMpMaterialVideoInfoResult infoResult = new WxMpMaterialVideoInfoResult();
        infoResult.setTitle("ready");
        infoResult.setDownUrl("https://servable.weixin.qq.com/video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(uploadResult);
        when(materialService.materialVideoInfo(VIDEO_MEDIA_ID))
                .thenThrow(wxError(-1, "transcoding"))
                .thenThrow(wxError(-1, "transcoding"))
                .thenReturn(infoResult);

        WeChatVideoMediaAdapter.UploadedVideo uploaded =
                adapter.uploadPermanentVideo(video, "title", "desc");

        assertThat(uploaded.readbackSucceeded()).isTrue();
        assertThat(uploaded.readbackAttempts()).isEqualTo(3);
        verify(materialService, times(3)).materialVideoInfo(VIDEO_MEDIA_ID);
    }

    /** title 是上传时自填字段会被立即回显, 不构成就绪证据 — downUrl (服务端派生) 缺失仍判未就绪. */
    @Test
    void shouldTreatTitleOnlyReadbackAsNotReady() throws Exception {
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(VIDEO_MEDIA_ID);
        WxMpMaterialVideoInfoResult infoResult = new WxMpMaterialVideoInfoResult();
        infoResult.setTitle("自填标题立即回显");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(uploadResult);
        when(materialService.materialVideoInfo(VIDEO_MEDIA_ID)).thenReturn(infoResult);

        WeChatVideoMediaAdapter.UploadedVideo uploaded =
                adapter.uploadPermanentVideo(video, "title", "desc");

        assertThat(uploaded.mediaId()).isEqualTo(VIDEO_MEDIA_ID);
        assertThat(uploaded.readbackSucceeded()).isFalse();
    }

    // ===== 微信错误码映射 =====

    @Test
    void shouldMap40164ToEnvironmentBlocked() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(wxError(40164, "invalid ip, not in whitelist"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_ENVIRONMENT_BLOCKED))
                .hasMessageContaining("errcode=40164");
    }

    /** I/O 矩阵: 40005 媒体类型错误 → NonRetryable 终态 (不进入有限重试). */
    @Test
    void shouldMap40005ToNonRetryable() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(wxError(40005, "invalid media type"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("errcode=40005");
    }

    /** I/O 矩阵: 40006 媒体大小错误 → NonRetryable 终态 (WxJavaWeChatClient 显式 case 触达). */
    @Test
    void shouldMap40006ToNonRetryable() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(wxError(40006, "invalid media size"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("errcode=40006");
    }

    @Test
    void shouldMap45009ToRetryable() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(wxError(45009, "reach max api daily quota limit"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(RetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_RATE_LIMITED))
                .hasMessageContaining("errcode=45009");
    }

    /** 网络层 -99 (Read timed out) — 共享映射落 default NonRetryable, 适配器校正为 Retryable. */
    @Test
    void shouldCorrectNetworkErrorMinus99ToRetryable() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(wxError(-99, "Read timed out"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(RetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("errcode=-99");
    }

    @Test
    void shouldReturnNonRetryableWhenMediaIdBlank() throws Exception {
        Path video = writeVideo("video.mp4");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId(" ");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(uploadResult);

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信 materialFileUpload(video) 返回空 media_id");
    }

    @Test
    void shouldWrapFrameworkRuntimeException() throws Exception {
        Path video = writeVideo("video.mp4");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class)))
                .thenThrow(new IllegalStateException("sdk state broken"));

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava 视频永久素材上传框架异常: sdk state broken");
    }

    // ===== 本地前置校验 (容量必须本地拦截 — 微信超限表现为 Read timed out) =====

    @Test
    void shouldFailFastWhenPathNull() {
        assertThatThrownBy(() -> adapter.uploadPermanentVideo(null, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材路径不能为空");
    }

    @Test
    void shouldFailFastWhenFileMissing() {
        Path missing = tempDir.resolve("missing.mp4");

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(missing, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件不存在")
                .hasMessageContaining("fileName=missing.mp4");
    }

    @Test
    void shouldFailFastWhenFileEmpty() throws Exception {
        Path empty = tempDir.resolve("empty.mp4");
        Files.createFile(empty);

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(empty, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件为空");
    }

    @Test
    void shouldFailFastWhenFileExceedsConfiguredSizeLimit() throws Exception {
        properties.getVideo().setSizeLimitMb(1);
        Path large = tempDir.resolve("large.mp4");
        try (RandomAccessFile raf = new RandomAccessFile(large.toFile(), "rw")) {
            raf.setLength(1024L * 1024L + 1);
        }

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(large, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件超过大小限制")
                .hasMessageContaining("maxBytes=1048576");
    }

    @Test
    void shouldFailFastWhenTitleBlank() throws Exception {
        Path video = writeVideo("video.mp4");

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, " ", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信永久视频素材 title 不能为空");
    }

    @Test
    void shouldFailFastWhenDescriptionBlank() throws Exception {
        Path video = writeVideo("video.mp4");

        assertThatThrownBy(() -> adapter.uploadPermanentVideo(video, "title", null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信永久视频素材 description 不能为空");
    }

    // ===== helpers =====

    private Path writeVideo(String filename) throws Exception {
        Path video = tempDir.resolve(filename);
        Files.writeString(video, "fake video bytes");
        return video;
    }

    private static WxErrorException wxError(int code, String message) {
        WxError error = new WxError();
        error.setErrorCode(code);
        error.setErrorMsg(message);
        return new WxErrorException(error);
    }
}
