package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
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
    private final SlowOperationRecorder slowOperationRecorder;

    public WeChatMaterialTool(WxMpService wxMpService, SlowOperationRecorder slowOperationRecorder) {
        this.wxMpService = wxMpService;
        this.slowOperationRecorder = slowOperationRecorder;
    }

    public UploadedMaterial uploadPermanentImage(Path imagePath) {
        File imageFile = validateImageFile(imagePath).toFile();
        try {
            WxMpMaterial material = new WxMpMaterial(imageFile.getName(), imageFile, null, null);
            WxMpMaterialUploadResult result = slowOperationRecorder.observe(
                    Kind.SDK, Dependency.WECHAT, Operation.UPLOAD, () -> {
                        try {
                            WxMpMaterialUploadResult uploadResult = wxMpService.getMaterialService()
                                    .materialFileUpload(MATERIAL_TYPE_IMAGE, material);
                            if (uploadResult == null || !StringUtils.hasText(uploadResult.getMediaId())) {
                                throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                                        "微信永久图片素材上传返回空 media_id");
                            }
                            return uploadResult;
                        } catch (WxErrorException e) {
                            throw WxJavaWeChatClient.mapWxErrorException(e, "materialFileUpload");
                        }
                    });
            log.info("微信永久图片素材上传成功: filename={}, mediaIdLength={}, hasUrl={}",
                    imageFile.getName(), result.getMediaId().length(), StringUtils.hasText(result.getUrl()));
            return new UploadedMaterial(result.getMediaId(), result.getUrl());
        } catch (NonRetryableException | RetryableException e) {
            // mapWxErrorException 对 40014/-1 等瞬态 errcode 抛 RetryableException,
            // 必须原样重抛, 不得落入下方兜底被二次包装成 NonRetryable 击穿重试.
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
            WxMpMaterialFileBatchGetResult result = slowOperationRecorder.observe(
                    Kind.SDK, Dependency.WECHAT, Operation.SEARCH, () -> {
                        try {
                            return wxMpService.getMaterialService()
                                    .materialFileBatchGet(MATERIAL_TYPE_IMAGE, offset, count);
                        } catch (WxErrorException e) {
                            throw WxJavaWeChatClient.mapWxErrorException(e, "materialFileBatchGet");
                        }
                    });
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
        } catch (NonRetryableException | RetryableException e) {
            // 同 uploadPermanentImage: 保留 mapWxErrorException 的可重试语义.
            throw e;
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
