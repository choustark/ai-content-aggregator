package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.exception.AggregatorException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 8.2: 微信视频素材上传 smoke test。
 *
 * <p>默认使用仓库内测试 MP4 样例；如需覆盖，可设置 {@code WECHAT_VIDEO_PATH}。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WxVideoMediaUploadSmokeTest {

    private static final Path DEFAULT_VIDEO_PATH = Path.of(
            "target/spike-8.2/wechat-video-test.mp4");

    @Autowired(required = false)
    private WeChatVideoMediaUploadProbe uploadProbe;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Test
    @Timeout(60)
    void shouldUploadTempVideoWhenRealCredentialsAvailable() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        WeChatVideoMediaUploadProbe.UploadedTempVideo uploaded;
        try {
            uploaded = uploadProbe.uploadTempVideo(videoPath);
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        assertThat(uploaded.mediaId()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(videoPath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();
    }

    @Test
    @Timeout(60)
    void shouldUploadPermanentVideoWhenRealCredentialsAvailable() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        WeChatVideoMediaUploadProbe.UploadedPermanentVideo uploaded;
        try {
            uploaded = uploadProbe.uploadPermanentVideo(videoPath, "story-8.2-spike-video", "story-8.2-spike");
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        assertThat(uploaded.mediaId()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(videoPath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId());
    }

    private static void skipIfEnvironmentBlocked(AggregatorException e) {
        String message = e.getMessage();
        if (message == null) {
            return;
        }
        boolean whitelistBlocked = message.contains("errcode=40164");
        boolean dnsBlocked = message.contains("UnknownHostException")
                || message.contains("nodename nor servname provided")
                || message.contains("api.weixin.qq.com");
        boolean quotaBlocked = message.contains("errcode=45009");
        assumeTrue(!(whitelistBlocked || dnsBlocked || quotaBlocked), "跳过: 外部微信环境阻塞 - " + message);
    }

    private static Path resolveVideoPath() {
        String override = System.getenv("WECHAT_VIDEO_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_VIDEO_PATH.toAbsolutePath().normalize();
    }
}
