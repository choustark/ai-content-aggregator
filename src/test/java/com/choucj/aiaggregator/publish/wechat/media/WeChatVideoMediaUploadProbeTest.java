package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import me.chanjar.weixin.common.bean.result.WxMediaUploadResult;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpMaterialService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WeChatVideoMediaUploadProbeTest {

    @TempDir
    private Path tempDir;

    @Mock
    private WxMpService wxMpService;

    @Mock
    private WxMpMaterialService materialService;

    private WeChatVideoMediaUploadProbe probe;

    @BeforeEach
    void setUp() {
        probe = new WeChatVideoMediaUploadProbe(wxMpService);
    }

    @Test
    void shouldUploadTempVideoAndReturnMediaId(CapturedOutput output) throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        WxMediaUploadResult result = new WxMediaUploadResult();
        result.setMediaId("WoX7vD-temp-id");
        result.setType("video");
        result.setCreatedAt(123456L);
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaUpload(eq("video"), any(File.class))).thenReturn(result);

        WeChatVideoMediaUploadProbe.UploadedTempVideo uploaded = probe.uploadTempVideo(video);

        assertThat(uploaded.mediaId()).isEqualTo("WoX7vD-temp-id");
        assertThat(uploaded.type()).isEqualTo("video");
        assertThat(uploaded.createdAt()).isEqualTo(123456L);
        assertThat(uploaded.fileName()).isEqualTo("video.mp4");
        assertThat(uploaded.sizeBytes()).isPositive();
        assertThat(output)
                .contains("微信临时视频素材上传成功")
                .contains("fileName=video.mp4")
                .contains("mediaIdPrefix=WoX7vD");
    }

    @Test
    void shouldUploadPermanentVideoAndReturnMediaId(CapturedOutput output) throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        WxMpMaterialUploadResult result = new WxMpMaterialUploadResult();
        result.setMediaId("PermId123456");
        result.setUrl("https://mmbiz.example/video");
        ArgumentCaptor<WxMpMaterial> materialCaptor = ArgumentCaptor.forClass(WxMpMaterial.class);
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), materialCaptor.capture())).thenReturn(result);

        WeChatVideoMediaUploadProbe.UploadedPermanentVideo uploaded =
                probe.uploadPermanentVideo(video, "demo title", "demo desc");

        assertThat(uploaded.mediaId()).isEqualTo("PermId123456");
        assertThat(uploaded.url()).isEqualTo("https://mmbiz.example/video");
        assertThat(uploaded.fileName()).isEqualTo("video.mp4");
        assertThat(materialCaptor.getValue().getVideoTitle()).isEqualTo("demo title");
        assertThat(materialCaptor.getValue().getVideoIntroduction()).isEqualTo("demo desc");
        assertThat(output)
                .contains("微信永久视频素材上传成功")
                .contains("mediaIdPrefix=PermId");
    }

    @Test
    void shouldFailFastWhenVideoPathMissing() {
        assertThatThrownBy(() -> probe.uploadTempVideo(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信视频素材路径不能为空");
    }

    @Test
    void shouldFailFastWhenVideoFileDoesNotExist() {
        Path missing = tempDir.resolve("missing.mp4");

        assertThatThrownBy(() -> probe.uploadTempVideo(missing))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件不存在")
                .hasMessageContaining("fileName=missing.mp4");
    }

    @Test
    void shouldFailFastWhenVideoFileEmpty() throws Exception {
        Path empty = tempDir.resolve("empty.mp4");
        Files.createFile(empty);

        assertThatThrownBy(() -> probe.uploadTempVideo(empty))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件为空")
                .hasMessageContaining("fileName=empty.mp4");
    }

    @Test
    void shouldFailFastWhenVideoFileTooLarge() throws Exception {
        Path large = tempDir.resolve("large.mp4");
        try (RandomAccessFile raf = new RandomAccessFile(large.toFile(), "rw")) {
            raf.setLength(WeChatVideoMediaUploadProbe.MAX_VIDEO_SIZE_BYTES + 1);
        }

        assertThatThrownBy(() -> probe.uploadTempVideo(large))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信视频素材文件超过10MB限制")
                .hasMessageContaining("fileName=large.mp4");
    }

    @Test
    void shouldFailWhenTempUploadReturnsBlankMediaId() throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        WxMediaUploadResult result = new WxMediaUploadResult();
        result.setMediaId(" ");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaUpload(eq("video"), any(File.class))).thenReturn(result);

        assertThatThrownBy(() -> probe.uploadTempVideo(video))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信 mediaUpload(video) 返回空 media_id");
    }

    @Test
    void shouldFailWhenPermanentUploadReturnsBlankMediaId() throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        WxMpMaterialUploadResult result = new WxMpMaterialUploadResult();
        result.setMediaId("");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("video"), any(WxMpMaterial.class))).thenReturn(result);

        assertThatThrownBy(() -> probe.uploadPermanentVideo(video, "title", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信 materialFileUpload(video) 返回空 media_id");
    }

    @Test
    void shouldFailFastWhenPermanentVideoTitleBlank() throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");

        assertThatThrownBy(() -> probe.uploadPermanentVideo(video, " ", "desc"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信永久视频素材 title 不能为空");
    }

    @Test
    void shouldMapWxUploadError() throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaUpload(eq("video"), any(File.class)))
                .thenThrow(wxError(40164, "invalid ip 127.0.0.1"));

        assertThatThrownBy(() -> probe.uploadTempVideo(video))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信 mediaUpload(video) 失败")
                .hasMessageContaining("errcode=40164");
    }

    @Test
    void shouldWrapRuntimeException() throws Exception {
        Path video = tempDir.resolve("video.mp4");
        Files.writeString(video, "fake video bytes");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaUpload(eq("video"), any(File.class)))
                .thenThrow(new IllegalStateException("sdk state broken"));

        assertThatThrownBy(() -> probe.uploadTempVideo(video))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava 视频临时素材上传框架异常: sdk state broken");
    }

    private static WxErrorException wxError(int code, String message) {
        WxError error = new WxError();
        error.setErrorCode(code);
        error.setErrorMsg(message);
        return new WxErrorException(error);
    }
}
