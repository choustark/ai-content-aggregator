package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * X 媒体本地归档配置 (Story 7.1 + 7.2).
 *
 * <p>命名空间 {@code twitter.media.*}, 与 {@code twitter.target.*} / {@code archive.*} 正交:
 * 本配置表达「X 推文媒体如何归档到本地」(目录结构 + sidecar 文件名 + 下载参数), {@code archive.*} 表达
 * 「文章 Markdown 如何归档」(Story 2.5).
 *
 * <p>集成型开关, 默认关闭 ({@code havingValue="true"} 不带 {@code matchIfMissing}):
 * 关闭时 {@code TweetMediaArchiveWriter} / {@code TweetMediaArchiver} Bean 不注册, 媒体归档完全不激活, 既有 Pipeline 零回归.
 *
 * <p>归档目录 = {@code {base-directory}/media/twitter/{yyyy-MM-dd}/{tweetId}/media.json}.
 * {@code base-directory} 为空时 fallback 到 {@code archive.base-directory} (Story 2.5),
 * 复用同一根目录的 Docker 卷挂载点.
 *
 * <p>引用源: Story 7.1 / architecture-x-media-fidelity ARCHITECTURE-SPINE AD-6 + 一致性约定表.
 */
@ConfigurationProperties(prefix = "twitter.media")
@Validated
@Data
public class TwitterMediaProperties {

    /**
     * 媒体归档总开关. 默认 {@code false} (集成型开关).
     *
     * <p>关闭时 TweetMediaArchiveWriter Bean 不注册
     * (@ConditionalOnProperty(twitter.media.enabled, havingValue=true, matchIfMissing=false)).
     */
    private boolean enabled = false;

    /**
     * 归档根目录. 为空 (默认) 时 fallback 到 {@code archive.base-directory}.
     *
     * <p>设值时单独的媒体归档根, 不与 Markdown 文章归档混放. 相对路径基于 JVM 工作目录.
     */
    private String baseDirectory;

    /**
     * 目录日期段格式. 默认 {@code yyyy-MM-dd} (ISO 8601).
     *
     * <p>校验: 仅允许 {@code [-_a-zA-Z0-9]} 字符 (拒绝 {@code /} 防子目录逃逸, 拒绝 {@code ..} 防路径穿越),
     * 与 {@code ArchiverProperties.datePattern} 一致 (W7/W8 模式).
     */
    @NotBlank(message = "twitter.media.date-pattern 不能为空")
    @Pattern(regexp = "[-_a-zA-Z0-9]+",
            message = "twitter.media.date-pattern 仅允许字母/数字/-/_ 字符 (拒绝 / 与 .. 防路径穿越)")
    private String datePattern = "yyyy-MM-dd";

    /**
     * sidecar 文件名. 默认 {@code media.json}.
     *
     * <p>校验: 不允许包含路径分隔符 {@code /} 或 {@code \} (防跨平台路径穿越),
     * 与 {@code ArchiverProperties.fileSuffix} 一致.
     */
    @NotBlank(message = "twitter.media.sidecar-filename 不能为空")
    @Pattern(regexp = "^[^/\\\\]*$", message = "twitter.media.sidecar-filename 不能包含路径分隔符 / 或 \\")
    private String sidecarFilename = "media.json";

    // --- Story 7.2: 图片下载参数 ---

    /**
     * HTTP 下载超时 (秒). 用于 {@link com.choucj.aiaggregator.source.twitter.media.MediaDownloadClient}
     * 的 RestClient connectTimeout + readTimeout.
     *
     * <p>合理范围: 5-120 秒 (大图可能慢).
     */
    @Min(value = 5, message = "twitter.media.download-timeout-seconds 不能小于 5 秒")
    @Max(value = 120, message = "twitter.media.download-timeout-seconds 不能超过 120 秒")
    private int downloadTimeoutSeconds = 30;

    /**
     * 单个下载文件大小上限 (MB). 超过此限制的文件不下载, 设
     * {@code downloadStatus=FAILED} + failureReason 记录截断原因.
     *
     * <p>合理范围: 1-50 MB (X 图片通常 &lt; 5 MB, 但全景图或高分辨率图可能更大).
     */
    @Min(value = 1, message = "twitter.media.max-file-size-mb 不能小于 1 MB")
    @Max(value = 50, message = "twitter.media.max-file-size-mb 不能超过 50 MB")
    private int maxFileSizeMb = 10;
}
