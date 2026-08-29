package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
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
 * Story 8.2: GIF 对微信正文图片接口接受度 smoke test。
 *
 * <p>该测试不要求接口一定成功；重点是记录 GIF 是否被微信 API 接受或拒绝。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WxGifUploadAcceptanceSmokeTest {

    private static final Path DEFAULT_GIF_PATH = Path.of(
            "target/spike-8.2/wechat-gif-test-small.gif");

    @Autowired(required = false)
    private WeChatBodyImageUploadProbe uploadProbe;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Test
    @Timeout(60)
    void shouldRecordWhetherGifIsAcceptedByMediaImgUpload() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时正文图片上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path gifPath = resolveGifPath();
        assumeThat(Files.isRegularFile(gifPath))
                .as("需要可用的本地 GIF 样例")
                .isTrue();

        try {
            WeChatBodyImageUploadProbe.UploadedBodyImage uploaded = uploadProbe.upload(gifPath);
            assertThat(uploaded.url()).isNotBlank().startsWith("http");
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            if (e instanceof NonRetryableException) {
                // GIF 被微信拒绝也属于本 Spike 的有效证据。
                assertThat(e.getMessage()).contains("微信");
                return;
            }
            throw e;
        }
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

    private static Path resolveGifPath() {
        String override = System.getenv("WECHAT_GIF_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_GIF_PATH.toAbsolutePath().normalize();
    }
}
