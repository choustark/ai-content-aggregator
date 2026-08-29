package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.result.WxMediaUploadResult;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Story 8.2: 微信视频素材上传验证探针。
 *
 * <p>显式区分视频素材路径与正文图片 {@code media/uploadimg} 路径，供视频/GIF spike 验证使用。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatVideoMediaUploadProbe {

    static final long MAX_VIDEO_SIZE_BYTES = 10L * 1024L * 1024L;

    private final WxMpService wxMpService;

    public WeChatVideoMediaUploadProbe(WxMpService wxMpService) {
        this.wxMpService = wxMpService;
    }

    /**
     * 上传临时视频素材。
     *
     * @param videoPath 本地视频路径
     * @return 临时素材摘要
     */
    public UploadedTempVideo uploadTempVideo(Path videoPath) {
        Path normalized = validateVideoFile(videoPath);
        File videoFile = normalized.toFile();
        long sizeBytes = readSize(normalized);
        long startNanos = System.nanoTime();
        try {
            WxMediaUploadResult result = wxMpService.getMaterialService()
                    .mediaUpload(WxConsts.MediaFileType.VIDEO, videoFile);
            if (result == null || !StringUtils.hasText(result.getMediaId())) {
                throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                        "微信 mediaUpload(video) 返回空 media_id");
            }
            String mediaId = result.getMediaId().trim();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("微信临时视频素材上传成功: fileName={}, sizeBytes={}, mediaIdPrefix={}, elapsedMs={}",
                    videoFile.getName(), sizeBytes, prefixMediaId(mediaId), elapsedMs);
            return new UploadedTempVideo(mediaId, result.getType(), result.getCreatedAt(),
                    videoFile.getName(), sizeBytes);
        } catch (WxErrorException e) {
            throw WxJavaWeChatClient.mapWxErrorException(e, "mediaUpload(video)");
        } catch (NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava 视频临时素材上传框架异常: "
                            + SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200),
                    e);
        }
    }

    /**
     * 上传永久视频素材。
     *
     * @param videoPath    本地视频路径
     * @param title        视频标题
     * @param description  视频描述
     * @return 永久素材摘要
     */
    public UploadedPermanentVideo uploadPermanentVideo(Path videoPath, String title, String description) {
        Path normalized = validateVideoFile(videoPath);
        String safeTitle = requireText(title, "微信永久视频素材 title 不能为空");
        String safeDescription = requireText(description, "微信永久视频素材 description 不能为空");
        File videoFile = normalized.toFile();
        long sizeBytes = readSize(normalized);
        long startNanos = System.nanoTime();
        try {
            WxMpMaterial material = new WxMpMaterial(videoFile.getName(), videoFile, safeTitle, safeDescription);
            WxMpMaterialUploadResult result = wxMpService.getMaterialService()
                    .materialFileUpload(WxConsts.MediaFileType.VIDEO, material);
            if (result == null || !StringUtils.hasText(result.getMediaId())) {
                throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                        "微信 materialFileUpload(video) 返回空 media_id");
            }
            String mediaId = result.getMediaId().trim();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("微信永久视频素材上传成功: fileName={}, sizeBytes={}, mediaIdPrefix={}, elapsedMs={}",
                    videoFile.getName(), sizeBytes, prefixMediaId(mediaId), elapsedMs);
            return new UploadedPermanentVideo(mediaId, result.getUrl(), videoFile.getName(), sizeBytes);
        } catch (WxErrorException e) {
            throw WxJavaWeChatClient.mapWxErrorException(e, "materialFileUpload(video)");
        } catch (NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava 视频永久素材上传框架异常: "
                            + SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200),
                    e);
        }
    }

    private static Path validateVideoFile(Path videoPath) {
        if (videoPath == null) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信视频素材路径不能为空");
        }
        Path normalized = videoPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信视频素材文件不存在: fileName=" + normalized.getFileName());
        }
        long sizeBytes = readSize(normalized);
        if (sizeBytes <= 0) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信视频素材文件为空: fileName=" + normalized.getFileName());
        }
        if (sizeBytes > MAX_VIDEO_SIZE_BYTES) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信视频素材文件超过10MB限制: fileName=" + normalized.getFileName());
        }
        return normalized;
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR, message);
        }
        return value.trim();
    }

    private static long readSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "读取微信视频素材大小失败: fileName=" + path.getFileName(),
                    e);
        }
    }

    private static String prefixMediaId(String mediaId) {
        return mediaId.length() <= 6 ? mediaId : mediaId.substring(0, 6);
    }

    public record UploadedTempVideo(String mediaId, String type, long createdAt,
                                    String fileName, long sizeBytes) {
    }

    public record UploadedPermanentVideo(String mediaId, String url,
                                         String fileName, long sizeBytes) {
    }
}
