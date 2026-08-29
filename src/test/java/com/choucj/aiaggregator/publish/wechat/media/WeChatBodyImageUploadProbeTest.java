package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpMaterialService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMediaImgUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WeChatBodyImageUploadProbeTest {

    @TempDir
    private Path tempDir;

    @Mock
    private WxMpService wxMpService;

    @Mock
    private WxMpMaterialService materialService;

    private WeChatBodyImageUploadProbe probe;

    @BeforeEach
    void setUp() {
        probe = new WeChatBodyImageUploadProbe(wxMpService);
    }

    @Test
    void shouldUploadBodyImageAndReturnWechatUrl(CapturedOutput output) throws Exception {
        Path image = tempDir.resolve("body.png");
        Files.writeString(image, "fake image bytes");
        WxMediaImgUploadResult result = new WxMediaImgUploadResult();
        result.setUrl("https://mmbiz.qpic.cn/sz_mmbiz_png/demo/body.png");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaImgUpload(any(File.class))).thenReturn(result);

        WeChatBodyImageUploadProbe.UploadedBodyImage uploaded = probe.upload(image);

        assertThat(uploaded.url()).isEqualTo(result.getUrl());
        assertThat(uploaded.urlHost()).isEqualTo("mmbiz.qpic.cn");
        assertThat(uploaded.fileName()).isEqualTo("body.png");
        assertThat(uploaded.sizeBytes()).isPositive();
        assertThat(output)
                .contains("微信正文图片上传成功")
                .contains("fileName=body.png")
                .contains("urlHost=mmbiz.qpic.cn");
    }

    @Test
    void shouldFailFastWhenImagePathMissing() {
        assertThatThrownBy(() -> probe.upload(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信正文图片路径不能为空");
    }

    @Test
    void shouldFailFastWhenImageFileDoesNotExist() {
        Path missing = tempDir.resolve("missing.png");

        assertThatThrownBy(() -> probe.upload(missing))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信正文图片文件不存在")
                .hasMessageContaining("fileName=missing.png");
    }

    @Test
    void shouldFailFastWhenImageFileEmpty() throws Exception {
        Path empty = tempDir.resolve("empty.png");
        Files.createFile(empty);

        assertThatThrownBy(() -> probe.upload(empty))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信正文图片文件为空")
                .hasMessageContaining("fileName=empty.png");
    }

    @Test
    void shouldFailWhenWechatReturnsBlankUrl() throws Exception {
        Path image = tempDir.resolve("body.png");
        Files.writeString(image, "fake image bytes");
        WxMediaImgUploadResult result = new WxMediaImgUploadResult();
        result.setUrl(" ");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaImgUpload(any(File.class))).thenReturn(result);

        assertThatThrownBy(() -> probe.upload(image))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信 mediaImgUpload 返回空 url");
    }

    @Test
    void shouldMapWxUploadError() throws Exception {
        Path image = tempDir.resolve("body.png");
        Files.writeString(image, "fake image bytes");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaImgUpload(any(File.class)))
                .thenThrow(wxError(40164, "invalid ip 127.0.0.1"));

        assertThatThrownBy(() -> probe.upload(image))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信 mediaImgUpload 失败")
                .hasMessageContaining("errcode=40164");
    }

    @Test
    void shouldWrapRuntimeException() throws Exception {
        Path image = tempDir.resolve("body.png");
        Files.writeString(image, "fake image bytes");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.mediaImgUpload(any(File.class)))
                .thenThrow(new IllegalStateException("sdk state broken"));

        assertThatThrownBy(() -> probe.upload(image))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("WxJava 正文图片上传框架异常: sdk state broken");
    }

    private static WxErrorException wxError(int code, String message) {
        WxError error = new WxError();
        error.setErrorCode(code);
        error.setErrorMsg(message);
        return new WxErrorException(error);
    }
}
