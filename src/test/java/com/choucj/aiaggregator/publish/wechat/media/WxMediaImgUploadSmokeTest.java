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
 * Story 8.1: 微信正文图片真实上传 smoke test。
 *
 * <p>默认使用仓库内已归档样例图片；如需覆盖，可设置 {@code WECHAT_BODY_IMAGE_PATH}。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WxMediaImgUploadSmokeTest {

    private static final Path DEFAULT_IMAGE_PATH = Path.of(
            "archive/media/twitter/2026-08-23/2090838453126566066/PixPin_2026-05-31_23-43-58.png");

    @Autowired(required = false)
    private WeChatBodyImageUploadProbe uploadProbe;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Test
    @Timeout(60)
    void shouldUploadBodyImageWhenRealCredentialsAvailable() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时正文图片上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path imagePath = resolveImagePath();
        assumeThat(Files.isRegularFile(imagePath))
                .as("需要可用的本地图片样例")
                .isTrue();

        WeChatBodyImageUploadProbe.UploadedBodyImage uploaded;
        try {
            uploaded = uploadProbe.upload(imagePath);
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }
        String html = "<img src=\"" + uploaded.url() + "\" alt=\"\">";

        assertThat(uploaded.url()).isNotBlank().startsWith("http");
        assertThat(uploaded.urlHost()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(imagePath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();
        assertThat(html).contains(uploaded.url()).startsWith("<img src=\"");
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

    private static Path resolveImagePath() {
        String override = System.getenv("WECHAT_BODY_IMAGE_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_IMAGE_PATH.toAbsolutePath().normalize();
    }
}
