package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialFileBatchGetResult;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 微信永久图片素材运维工具.
 *
 * <p>用于程序化上传/查询草稿封面图素材, 发布时由 resolver 按素材名称获取 {@code media_id}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatMaterialTool {

    private static final String MATERIAL_TYPE_IMAGE = "image";

    private final WxMpService wxMpService;

    public WeChatMaterialTool(WxMpService wxMpService) {
        this.wxMpService = wxMpService;
    }

    public UploadedMaterial uploadPermanentImage(Path imagePath) {
        File imageFile = validateImageFile(imagePath).toFile();
        try {
            WxMpMaterial material = new WxMpMaterial(imageFile.getName(), imageFile, null, null);
            WxMpMaterialUploadResult result = wxMpService.getMaterialService()
                    .materialFileUpload(MATERIAL_TYPE_IMAGE, material);
            if (result == null || !StringUtils.hasText(result.getMediaId())) {
                throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                        "微信永久图片素材上传返回空 media_id");
            }
            log.info("微信永久图片素材上传成功: filename={}, mediaIdLength={}, hasUrl={}",
                    imageFile.getName(), result.getMediaId().length(), StringUtils.hasText(result.getUrl()));
            return new UploadedMaterial(result.getMediaId(), result.getUrl());
        } catch (WxErrorException e) {
            throw WxJavaWeChatClient.mapWxErrorException(e, "materialFileUpload");
        } catch (NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            String rootMessage = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), 200);
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "WxJava 素材上传框架异常: " + rootMessage, e);
        }
    }

    public PermanentImagePage listPermanentImages(int offset, int count) {
        if (offset < 0) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信永久图片素材查询 offset must be >= 0");
        }
        if (count < 1 || count > 20) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信永久图片素材查询 count must be between 1 and 20");
        }
        try {
            WxMpMaterialFileBatchGetResult result = wxMpService.getMaterialService()
                    .materialFileBatchGet(MATERIAL_TYPE_IMAGE, offset, count);
            List<PermanentImage> items = result.getItems() == null
                    ? List.of()
                    : result.getItems().stream()
                    .map(item -> new PermanentImage(
                            item.getMediaId(),
                            item.getName(),
                            item.getUrl(),
                            item.getUpdateTime() == null ? null : item.getUpdateTime().toInstant()))
                    .toList();
            log.info("微信永久图片素材查询成功: offset={}, count={}, total={}, returned={}",
                    offset, count, result.getTotalCount(), items.size());
            return new PermanentImagePage(result.getTotalCount(), result.getItemCount(), items);
        } catch (WxErrorException e) {
            throw WxJavaWeChatClient.mapWxErrorException(e, "materialFileBatchGet");
        } catch (RuntimeException e) {
            String rootMessage = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), 200);
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "WxJava 素材查询框架异常: " + rootMessage, e);
        }
    }

    private Path validateImageFile(Path imagePath) {
        if (imagePath == null) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信封面图片路径不能为空");
        }
        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信封面图片文件不存在: path=" + normalized);
        }
        return normalized;
    }

    public record UploadedMaterial(String mediaId, String url) {
    }

    public record PermanentImagePage(int totalCount, int itemCount, List<PermanentImage> items) {
    }

    public record PermanentImage(String mediaId, String name, String url, Instant updateTime) {
    }
}
