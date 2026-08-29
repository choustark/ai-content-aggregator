package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 8.4: 微信正文图片准备编排器端到端 smoke test (真实微信 API + 真实 sidecar 回写).
 *
 * <p>默认使用仓库内已归档样例图片，复制到 {@code @TempDir} 归档树后经
 * {@link WeChatMediaPreparer} 完整走 上传 → sidecar 回写 链路；如需覆盖样例，
 * 可设置 {@code WECHAT_BODY_IMAGE_PATH}。外部环境阻塞 (IP 白名单/配额/DNS) 自动跳过
 * (复用 Story 8.1 {@code WxMediaImgUploadSmokeTest} 保护模式)。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WxMediaPreparerSmokeTest {

    /** 独立于真实归档的 smoke tweetId (白名单字符集), 避免污染仓库 archive 目录. */
    private static final String SMOKE_TWEET_ID = "smoke840001";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 23, 0, 0);

    private static final Path DEFAULT_IMAGE_PATH = Path.of(
            "archive/media/twitter/2026-08-23/2090838453126566066/PixPin_2026-05-31_23-43-58.png");

    @TempDir
    static Path tempBase;

    @Autowired(required = false)
    private WeChatMediaPreparer preparer;

    @Autowired(required = false)
    private TweetMediaArchiveWriter archiveWriter;

    @Autowired
    private WxMpProperties wxMpProperties;

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("twitter.media.enabled", () -> "true");
        registry.add("twitter.media.base-directory", () -> tempBase.toString());
    }

    @Test
    @Timeout(60)
    void should_prepare_body_image_and_writeback_sidecar_when_real_credentials_available() throws IOException {
        assumeThat(preparer).as("wechat.mp.enabled=true 时 preparer 应注册").isNotNull();
        assumeThat(archiveWriter).as("twitter.media.enabled=true 时 sidecar writer 应注册").isNotNull();
        assumeThat(hasRealCredentials()).as("需要真实的微信公众号 app-id / secret").isTrue();

        Path sampleImage = resolveImagePath();
        assumeThat(Files.isRegularFile(sampleImage)).as("需要可用的本地图片样例").isTrue();

        // 在 TempDir 归档树中构造 已下载 PHOTO + sidecar 前置状态
        String fileName = sampleImage.getFileName().toString();
        Path mediaDir = archiveWriter.resolveArchiveDir(SMOKE_TWEET_ID, PUBLISHED_AT);
        Files.createDirectories(mediaDir);
        Files.copy(sampleImage, mediaDir.resolve(fileName));
        TweetMedia photo = TweetMedia.builder()
                .id("smoke-photo-1")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .localPath("media/twitter/2026-08-23/" + SMOKE_TWEET_ID + "/" + fileName)
                .build();
        archiveWriter.writeSidecar(SMOKE_TWEET_ID, PUBLISHED_AT, List.of(photo));

        // preparer 每媒体 catch 已捕获全部探针异常 (环境阻塞只会出现在结果对象 failureReason，
        // 不会外抛 —— CR 2026-08-28 删除不可达的异常路径死代码)
        MediaPreparationResult result = preparer.prepareMedia(SMOKE_TWEET_ID, PUBLISHED_AT, List.of(photo));

        for (MediaPreparationResult.MediaPreparationStatus status : result.mediaStatuses()) {
            if (status.failureReason() != null) {
                skipIfEnvironmentBlocked(status.failureReason());
            }
        }

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failCount()).isZero();
        assertThat(result.mediaStatuses()).hasSize(1);
        assertThat(result.mediaStatuses().get(0).status()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(result.mediaStatuses().get(0).wechatUrl()).isNotBlank().startsWith("http");

        TweetMedia sidecarMedia = archiveWriter.readSidecar(SMOKE_TWEET_ID, PUBLISHED_AT)
                .orElseThrow().getMedia().get(0);
        assertThat(sidecarMedia.getUploadStatus()).isEqualTo(MediaUploadStatus.UPLOADED);
        assertThat(sidecarMedia.getWechatUrl()).startsWith("http");
        assertThat(sidecarMedia.getFailureReason()).isNull();
    }

    private boolean hasRealCredentials() {
        // appId 与 secret 都必须排除占位值，防止单边占位混入触发注定失败的真调 (CR 2026-08-28)
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId())
                && !"dev-placeholder".equals(wxMpProperties.getSecret());
    }

    private static void skipIfEnvironmentBlocked(String message) {
        if (message == null) {
            return;
        }
        // 兼容 "=" 与 ":" 两种 errcode 格式 (mapWxErrorException 用 "="，WxJava 原生 message 用 ":")
        boolean whitelistBlocked = message.contains("errcode=40164") || message.contains("errcode: 40164");
        boolean quotaBlocked = message.contains("errcode=45009") || message.contains("errcode: 45009");
        // DNS 阻塞只按异常特征判定 —— 不匹配裸 "api.weixin.qq.com" host:
        // 该 host 出现在普通 SDK 错误 message 中，会把真实 API 失败洗绿成 skip (CR 2026-08-28)
        boolean dnsBlocked = message.contains("UnknownHostException")
                || message.contains("nodename nor servname provided");
        assumeTrue(!(whitelistBlocked || dnsBlocked || quotaBlocked), "跳过: 外部微信环境阻塞 - " + message);
    }

    private static Path resolveImagePath() {
        String override = System.getenv("WECHAT_BODY_IMAGE_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_IMAGE_PATH.toAbsolutePath().normalize();
    }
}
