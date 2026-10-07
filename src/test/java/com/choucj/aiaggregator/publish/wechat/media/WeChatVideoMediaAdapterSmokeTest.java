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
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 10.11: VIDEO 微信永久素材生产上传适配器端到端冒烟测试 (@Tag("external"))。
 *
 * <p>复用 Spike 10.9 smoke gate 形态: @SpringBootTest + wechat.mp.enabled=true,
 * 真实凭据 env 注入, 统一 skipIfBlocked (40164/45009/40013/40001/40014 + DNS 三特征)。
 *
 * <p>验证 10.11 契约主链路: 生产适配器 materialFileUpload(video) → mediaId →
 * 候选 A 正文形态 {@code <p>视频素材: {mediaId}</p>}。成功创建的永久素材
 * mediaId 追加写入 {@code target/spike-10.11/created-artifacts.txt} (不入库) 供人工清理。
 *
 * <p>默认不参与 surefire 主套件 (@Tag external); 视频样例与 Spike 共用
 * {@code target/spike-8.2/wechat-video-test.mp4}, 可用 {@code WECHAT_VIDEO_PATH} 覆盖。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "wechat.mp.video.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WeChatVideoMediaAdapterSmokeTest {

    private static final Path DEFAULT_VIDEO_PATH = Path.of(
            "target/spike-8.2/wechat-video-test.mp4");
    private static final Path CREATED_ARTIFACTS_PATH = Path.of(
            "target/spike-10.11/created-artifacts.txt");

    @Autowired(required = false)
    private WeChatVideoMediaAdapter videoAdapter;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Test
    @Timeout(120)
    void shouldUploadPermanentVideoViaProductionAdapter() {
        assumeThat(videoAdapter)
                .as("wechat.mp.enabled=true 时生产视频适配器应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例 (WECHAT_VIDEO_PATH 或 target/spike-8.2/wechat-video-test.mp4)")
                .isTrue();

        String title = "story-10.11-smoke-video tw-10-11";
        String description = "story-10.11-smoke";
        WeChatVideoMediaAdapter.UploadedVideo uploaded;
        try {
            uploaded = videoAdapter.uploadPermanentVideo(videoPath, title, description);
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        recordCreatedArtifact("shouldUploadPermanentVideoViaProductionAdapter",
                uploaded.mediaId());
        System.out.printf("[story-10.11] 生产适配器永久视频上传成功(已记入 created-artifacts.txt 待人工后台清理): "
                        + "fileName=%s, sizeBytes=%d, mediaIdPrefix=%s, readbackSucceeded=%s, readbackAttempts=%d%n",
                uploaded.fileName(), uploaded.sizeBytes(), prefix(uploaded.mediaId(), 8),
                uploaded.readbackSucceeded(), uploaded.readbackAttempts());

        // 10.11 契约: materialFileUpload(video) 返回非空 media_id
        assertThat(uploaded.mediaId()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(videoPath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();

        // mediaId 非空即具备候选 A 引用素材 — 正文装配形态 (<p>视频素材: {mediaId}</p>)
        // 由默认套件 OriginalPostRendererTest/MarkdownMediaInserterTest 的嵌入断言覆盖,
        // 此处不重复自拼自验 (同义反复断言无验证价值)。
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId());
    }

    /**
     * AggregatorException 路径的阻塞判定(与 Spike 10.9 单一口径一致):
     * errcode 覆盖 40164/45009/40013/40001/40014, 文本仅覆盖 DNS 两特征 —
     * 不含 {@code api.weixin.qq.com} 子串判定: 框架网络异常消息内嵌该 URL 时,
     * 真实故障会被误判为环境阻塞而静默 skip (CR Story 10.11)。
     */
    private static void skipIfEnvironmentBlocked(AggregatorException e) {
        String text = e.getMessage() == null ? "" : e.getMessage();
        boolean whitelistBlocked = text.contains("errcode=40164");
        boolean quotaBlocked = text.contains("errcode=45009");
        boolean credentialBlocked = text.contains("errcode=40001")
                || text.contains("errcode=40013")
                || text.contains("errcode=40014");
        boolean dnsBlocked = text.contains("UnknownHostException")
                || text.contains("nodename nor servname provided");
        assumeTrue(!(whitelistBlocked || quotaBlocked || credentialBlocked || dnsBlocked),
                "跳过: 外部微信环境阻塞 - " + text);
    }

    /**
     * 成功创建的永久素材 mediaId 追加写入 target/spike-10.11/created-artifacts.txt(不入库),
     * 一行一条(时间 + 来源 + mediaId), 供人工后台清理; 失败/阻塞分支不写。
     */
    private static void recordCreatedArtifact(String source, String mediaId) {
        String line = LocalDateTime.now() + " " + source + " " + mediaId + System.lineSeparator();
        try {
            Path artifacts = CREATED_ARTIFACTS_PATH.toAbsolutePath().normalize();
            Files.createDirectories(artifacts.getParent());
            Files.writeString(artifacts, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.printf("[story-10.11] created-artifacts.txt 写入失败(请人工记录该 mediaId): source=%s, err=%s%n",
                    source, e.getMessage());
        }
    }

    private static String prefix(String value, int length) {
        if (value == null) {
            return "null";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static Path resolveVideoPath() {
        String override = System.getenv("WECHAT_VIDEO_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_VIDEO_PATH.toAbsolutePath().normalize();
    }
}
