package com.choucj.aiaggregator.publish.wechat.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.wechat.client.WxJavaWeChatClient;
import com.choucj.aiaggregator.publish.wechat.config.WeChatProperties;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialVideoInfoResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Story 10.11: VIDEO 微信永久素材生产上传适配器 (Spike 10.9 §8 契约产品化).
 *
 * <p>契约要点 (源自 Spike 10.9 Go/No-Go §8, 不容偏离):
 * <ul>
 *   <li>唯一上传端点: 永久素材 {@code materialFileUpload(video)}; 绝不使用
 *       {@code media/uploadvideo} 临时端点或正文图片 {@code media/uploadimg}</li>
 *   <li>容量校验必须本地前置 — 微信永久端点超限表现为 Read timed out (WxJava 归一为
 *       errcode -99) 而非干净错误码, 不得依赖微信侧语义; 格式白名单已由 Story 10.10
 *       下载侧实施 (MIME video/mp4 + ftyp 魔数), 本适配器不重复格式校验</li>
 *   <li>{@code materialVideoInfo} 回读为 best-effort 证据 (默认 3 次 × 3s, 覆盖异步转码),
 *       失败仅记 warn 日志, 不作为上传成功的终态化前置</li>
 *   <li>异常统一经 {@link WxJavaWeChatClient#mapWxErrorException} 分类: 40164 →
 *       ENVIRONMENT_BLOCKED 终态, 40005/40006 → NonRetryable, 45009/40014/-1 → Retryable</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
public class WeChatVideoMediaAdapter {

    /**
     * WxJava 网络层错误码: {@code WxErrorException.DEFAULT_ERROR_CODE = -99} (常量为 private,
     * 无法引用), Read timed out 等框架网络异常以该码进入 {@code mapWxErrorException} 的 default
     * 分支归 NonRetryable; 对 VIDEO 上传这属瞬时故障, 须在本适配器内校正为 Retryable。
     */
    private static final int WX_NETWORK_ERROR_CODE = -99;

    private final WxMpService wxMpService;
    private final WeChatProperties weChatProperties;

    public WeChatVideoMediaAdapter(WxMpService wxMpService, WeChatProperties weChatProperties) {
        this.wxMpService = wxMpService;
        this.weChatProperties = weChatProperties;
    }

    /**
     * 上传本地视频到微信永久素材库并执行 best-effort 回读取证。
     *
     * @param videoPath   本地视频路径 (10.10 归档产物)
     * @param title       永久素材标题 (OQ1: 文章标题 + tweetId 后缀, 已由调用方截断)
     * @param description 永久素材描述 (OQ1: 文章简介截断)
     * @return 上传结果 (含回读证据)
     * @throws NonRetryableException 本地前置失败 / 40164 / 40005 / 40006 / 40001 / 空 mediaId 等
     * @throws RetryableException    45009 / 40014 / -1 / 网络层 -99 等瞬时故障
     */
    public UploadedVideo uploadPermanentVideo(Path videoPath, String title, String description) {
        Path normalized = validateVideoFile(videoPath);
        String safeTitle = requireText(title, "微信永久视频素材 title 不能为空");
        String safeDescription = requireText(description, "微信永久视频素材 description 不能为空");
        File videoFile = normalized.toFile();
        long sizeBytes = readSize(normalized);
        long startNanos = System.nanoTime();
        String mediaId;
        try {
            WxMpMaterial material = new WxMpMaterial(videoFile.getName(), videoFile, safeTitle, safeDescription);
            WxMpMaterialUploadResult result = wxMpService.getMaterialService()
                    .materialFileUpload(WxConsts.MediaFileType.VIDEO, material);
            if (result == null || !StringUtils.hasText(result.getMediaId())) {
                throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                        "微信 materialFileUpload(video) 返回空 media_id");
            }
            mediaId = result.getMediaId().trim();
        } catch (WxErrorException e) {
            throw adjustNetworkErrors(e, "materialFileUpload(video)");
        } catch (RetryableException e) {
            throw e;
        } catch (NonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NonRetryableException(
                    ErrorCode.WECHAT_API_ERROR,
                    "WxJava 视频永久素材上传框架异常: "
                            + TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), 200),
                    e);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("微信永久视频素材上传成功: fileName={}, sizeBytes={}, mediaIdPrefix={}, elapsedMs={}",
                videoFile.getName(), sizeBytes, prefixMediaId(mediaId), elapsedMs);

        ReadbackOutcome readback = readbackVideoInfo(mediaId);
        if (!readback.succeeded()) {
            log.warn("微信永久视频素材回读失败 (best-effort, 不影响上传成功): mediaIdPrefix={}, attempts={}",
                    prefixMediaId(mediaId), readback.attempts());
        }
        return new UploadedVideo(mediaId, videoFile.getName(), sizeBytes,
                readback.succeeded(), readback.attempts());
    }

    /**
     * best-effort {@code materialVideoInfo} 回读 — 覆盖微信异步转码窗口。
     * <p>任何异常 (含微信错误码) 均不外抛, 仅以 {@link ReadbackOutcome} 表达证据缺失;
     * 上传本身已成功, 回读失败不终态化、不重试上传。
     */
    private ReadbackOutcome readbackVideoInfo(String mediaId) {
        int attempts = Math.max(1, weChatProperties.getVideo().getReadbackAttempts());
        long intervalMs = Math.max(0L, weChatProperties.getVideo().getReadbackIntervalMs());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1 && intervalMs > 0) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ReadbackOutcome(false, attempt - 1);
                }
            }
            try {
                WxMpMaterialVideoInfoResult result = wxMpService.getMaterialService()
                        .materialVideoInfo(mediaId);
                // 就绪判据: 服务端派生字段 downUrl 非空 — title 是上传时自填字段会被立即回显,
                // 无法证明转码/证据就绪 (CR Story 10.11)。
                if (result != null && StringUtils.hasText(result.getTitle())
                        && StringUtils.hasText(result.getDownUrl())) {
                    log.info("微信永久视频素材回读成功: mediaIdPrefix={}, attempt={}",
                            prefixMediaId(mediaId), attempt);
                    return new ReadbackOutcome(true, attempt);
                }
                log.info("微信永久视频素材回读字段未就绪 (可能转码中): mediaIdPrefix={}, attempt={}",
                        prefixMediaId(mediaId), attempt);
            } catch (Exception e) {
                log.info("微信永久视频素材回读异常 (继续重试): mediaIdPrefix={}, attempt={}, error={}",
                        prefixMediaId(mediaId), attempt,
                        TextTruncateUtil.truncateForLog(TextTruncateUtil.getRootMessage(e), 200));
            }
        }
        return new ReadbackOutcome(false, attempts);
    }

    /**
     * 共享错误映射 + 网络层瞬时校正。
     * <p>errcode -99 (Read timed out 等) 经 {@code mapWxErrorException} default 分支落为
     * NonRetryable; 对上传而言属网络/超时瞬时故障, 校正为 Retryable (WECHAT_API_ERROR),
     * 使其进入既有 RETRY_SCHEDULED 有限重试窗口。其余映射语义与 PHOTO 完全一致。
     */
    private static RuntimeException adjustNetworkErrors(WxErrorException e, String operation) {
        RuntimeException mapped = WxJavaWeChatClient.mapWxErrorException(e, operation);
        int errcode = e.getError() == null ? -1 : e.getError().getErrorCode();
        if (errcode == WX_NETWORK_ERROR_CODE && mapped instanceof NonRetryableException nonRetryable) {
            return new RetryableException(ErrorCode.WECHAT_API_ERROR, nonRetryable.getMessage(), e);
        }
        return mapped;
    }

    /** 本地容量前置校验 — 微信永久端点超限表现为 Read timed out, 容量必须在本地拦截。 */
    private Path validateVideoFile(Path videoPath) {
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
        long maxBytes = maxVideoSizeBytes();
        if (sizeBytes > maxBytes) {
            throw new NonRetryableException(ErrorCode.WECHAT_API_ERROR,
                    "微信视频素材文件超过大小限制: fileName=" + normalized.getFileName()
                            + ", sizeBytes=" + sizeBytes + ", maxBytes=" + maxBytes);
        }
        return normalized;
    }

    private long maxVideoSizeBytes() {
        // 上限取 max(1MB, 配置值), 防御误配 0/负数导致所有文件被本地拒绝。
        return Math.max(1L, weChatProperties.getVideo().getSizeLimitMb()) * 1024L * 1024L;
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
        // N4: 日志不落完整 mediaId, 仅前 6 位前缀。
        return mediaId.length() <= 6 ? mediaId : mediaId.substring(0, 6);
    }

    /** 上传成功结果 (含回读证据)。 */
    public record UploadedVideo(String mediaId, String fileName, long sizeBytes,
                                boolean readbackSucceeded, int readbackAttempts) {
    }

    /** 回读取证结果; 失败仅表达证据缺失, 不影响上传成功语义。 */
    record ReadbackOutcome(boolean succeeded, int attempts) {
    }
}
