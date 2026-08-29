package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMediaImgUploadResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Story 8.1: 微信正文图片上传验证探针。
 *
 * <p>把正文图片 {@code media/uploadimg} 与封面永久素材 {@code materialFileUpload} 明确分离，
 * 为 Spike 和后续 Story 8.4/8.5 提供最小可复用的真实上传入口。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatBodyImageUploadProbe {

    private final WxMpService wxMpService;

    public WeChatBodyImageUploadProbe(WxMpService wxMpService) {
        this.wxMpService = wxMpService;
    }

    /**
     * 上传微信正文图片并返回可用于 {@code <img src="">} 的 URL。
     *
     * @param imagePath 本地图片路径
     * @return 上传结果摘要
     */
    public UploadedBodyImage upload(Path imagePath) {
        Path normalized = validateImageFile(imagePath);
        File imageFile = normalized.toFile();
        long sizeBytes = readSize(normalized);
        long startNanos = System.nanoTime();
        try {
            WxMediaImgUploadResult result = wxMpService.getMaterialService().mediaImgUpload(imageFile);
            if (result == null || !StringUtils.hasText(result.getUrl())) {
                throw new NonRetryableException(
                        ErrorCode.WECHAT_API_ERROR,
                        "微信 mediaImgUpload 返回空 url");
            }
            String url = result.getUrl().trim();
            String urlHost = extractHost(url);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("微信正文图片上传成功: fileName={}, sizeBytes={}, urlHost={}, elapsedMs={}",
                    imageFile.getName(), sizeBytes, urlHost, elapsedMs);
            return new UploadedBodyImage(url, urlHost, imageFile.getName(), sizeBytes);
        } catch (WxErrorException e) {
            throw WxJavaWeChatClient.mapWxErrorException(e, "mediaImgUpload");
        } catch (NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava 正文图片上传框架异常: "
                            + SingleModelRewriter.truncateForLog(SingleModelRewriter.getRootMessage(e), 200),
                    e);
        }
    }

    private static Path validateImageFile(Path imagePath) {
        if (imagePath == null) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信正文图片路径不能为空");
        }
        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信正文图片文件不存在: fileName=" + normalized.getFileName());
        }
        long sizeBytes = readSize(normalized);
        if (sizeBytes <= 0) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信正文图片文件为空: fileName=" + normalized.getFileName());
        }
        return normalized;
    }

    private static long readSize(Path imagePath) {
        try {
            return Files.size(imagePath);
        } catch (Exception e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "读取微信正文图片大小失败: fileName=" + imagePath.getFileName(),
                    e);
        }
    }

    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                throw new IllegalArgumentException("missing host");
            }
            return host;
        } catch (RuntimeException e) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信正文图片 URL 非法: " + SingleModelRewriter.truncateForLog(url, 120), e);
        }
    }

    public record UploadedBodyImage(String url, String urlHost, String fileName, long sizeBytes) {
    }
}
