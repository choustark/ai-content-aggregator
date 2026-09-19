package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.TestSlowOperationRecorder;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpMaterialService;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialFileBatchGetResult;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatMaterialToolTest {

    @TempDir
    private Path tempDir;

    @Mock
    private WxMpService wxMpService;

    @Mock
    private WxMpMaterialService materialService;

    private WeChatMaterialTool tool;

    @BeforeEach
    void setUp() {
        tool = new WeChatMaterialTool(wxMpService, TestSlowOperationRecorder.create());
    }

    @Test
    void shouldUploadPermanentImageAndReturnMediaId() throws Exception {
        Path image = tempDir.resolve("cover.jpg");
        Files.writeString(image, "fake image bytes");
        WxMpMaterialUploadResult uploadResult = new WxMpMaterialUploadResult();
        uploadResult.setMediaId("media-id-123");
        uploadResult.setUrl("https://mmbiz.example/cover.jpg");
        ArgumentCaptor<WxMpMaterial> materialCaptor = ArgumentCaptor.forClass(WxMpMaterial.class);
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("image"), materialCaptor.capture()))
                .thenReturn(uploadResult);

        WeChatMaterialTool.UploadedMaterial result = tool.uploadPermanentImage(image);

        assertThat(result.mediaId()).isEqualTo("media-id-123");
        assertThat(result.url()).isEqualTo("https://mmbiz.example/cover.jpg");
        assertThat(materialCaptor.getValue().getName()).isEqualTo("cover.jpg");
        assertThat(materialCaptor.getValue().getFile()).isEqualTo(image.toFile());
    }

    @Test
    void shouldFailFastWhenImagePathMissing() {
        assertThatThrownBy(() -> tool.uploadPermanentImage(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_API_ERROR))
                .hasMessageContaining("微信封面图片路径不能为空");
    }

    @Test
    void shouldFailFastWhenImageFileDoesNotExist() {
        Path missing = tempDir.resolve("missing.jpg");

        assertThatThrownBy(() -> tool.uploadPermanentImage(missing))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("微信封面图片文件不存在")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void shouldMapWxUploadError() throws Exception {
        Path image = tempDir.resolve("cover.jpg");
        Files.writeString(image, "fake image bytes");
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileUpload(eq("image"), org.mockito.ArgumentMatchers.any(WxMpMaterial.class)))
                .thenThrow(wxError(40001, "invalid credential"));

        assertThatThrownBy(() -> tool.uploadPermanentImage(image))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((AggregatorException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.WECHAT_INVALID_CREDENTIAL))
                .hasMessageContaining("微信 materialFileUpload 失败")
                .hasMessageContaining("errcode=40001");
    }

    @Test
    void shouldListPermanentImages() throws Exception {
        WxMpMaterialFileBatchGetResult.WxMaterialFileBatchGetNewsItem item =
                new WxMpMaterialFileBatchGetResult.WxMaterialFileBatchGetNewsItem();
        item.setMediaId("media-id-1");
        item.setName("cover.jpg");
        item.setUrl("https://mmbiz.example/cover.jpg");
        item.setUpdateTime(new Date(1_700_000_000_000L));
        WxMpMaterialFileBatchGetResult batch = new WxMpMaterialFileBatchGetResult();
        batch.setTotalCount(3);
        batch.setItemCount(1);
        batch.setItems(List.of(item));
        when(wxMpService.getMaterialService()).thenReturn(materialService);
        when(materialService.materialFileBatchGet("image", 0, 20)).thenReturn(batch);

        WeChatMaterialTool.PermanentImagePage page = tool.listPermanentImages(0, 20);

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.itemCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).mediaId()).isEqualTo("media-id-1");
        assertThat(page.items().get(0).name()).isEqualTo("cover.jpg");
    }

    @Test
    void shouldValidateListCountRange() {
        assertThatThrownBy(() -> tool.listPermanentImages(0, 21))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("count must be between 1 and 20");
    }

    private WxErrorException wxError(int code, String message) {
        WxError error = new WxError();
        error.setErrorCode(code);
        error.setErrorMsg(message);
        return new WxErrorException(error);
    }
}
