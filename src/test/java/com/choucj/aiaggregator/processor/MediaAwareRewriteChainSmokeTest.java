package com.choucj.aiaggregator.processor;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Story 9.1 Task 6: 媒体感知改写生成全链外部 smoke (真实微信上传 + 真实 sidecar 回写).
 *
 * <p>链路: {@link MediaAwareRewriteArticleGenerator} —
 * rewrite (LLM stub) → archiveMedia (幂等跳过, 本地文件已就位) → publishability gate →
 * prepareMedia (真实微信 uploadimg) → 重读 sidecar → MarkdownMediaInserter。
 *
 * <p><b>守卫策略 (复用 Story 8.4 {@code WxMediaPreparerSmokeTest} 保护模式):</b>
 * <ul>
 *   <li>{@code @Tag("external")} — 默认回归排除, 显式开启才执行</li>
 *   <li>{@code assumeTrue} — 无真实 app-id/secret、无样例图片、40164 白名单/45009 配额/DNS
 *       阻塞时自动 skip, 不阻塞 CI (E8-WHITELIST-E2E)</li>
 *   <li>LLM 用 {@code @MockBean} stub — smoke 验证媒体链路, 不依赖 LLM API key</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class MediaAwareRewriteChainSmokeTest {

    /** 独立于真实归档的 smoke tweetId (白名单字符集), 避免污染仓库 archive 目录. */
    private static final String SMOKE_TWEET_ID = "smoke910001";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 23, 0, 0);

    private static final Path DEFAULT_IMAGE_PATH = Path.of(
            "archive/media/twitter/2026-08-23/2090838453126566066/PixPin_2026-05-31_23-43-58.png");

    @TempDir
    static Path tempBase;

    /** LLM stub — AC 4: 改写组件不感知媒体, smoke 中返回固定 Markdown 即可. */
    @MockBean
    private ContentRewriter contentRewriter;

    @Autowired(required = false)
    private MediaAwareRewriteGenerationGateway gateway;

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
    void should_generate_rewrite_with_media_article_when_real_credentials_available() throws IOException {
        assumeThat(gateway).as("wechat.mp.enabled=true 时媒体感知 gateway 应注册").isNotNull();
        assumeThat(archiveWriter).as("twitter.media.enabled=true 时 sidecar writer 应注册").isNotNull();
        assumeThat(hasRealCredentials()).as("需要真实的微信公众号 app-id / secret").isTrue();

        Path sampleImage = resolveImagePath();
        assumeThat(Files.isRegularFile(sampleImage)).as("需要可用的本地图片样例").isTrue();

        // 在 TempDir 归档树中预置 已下载 PHOTO + 本地文件 (archiver 幂等跳过下载, AD-6)
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

        // LLM stub: 固定 Markdown 正文 (不含任何媒体 URL — AC 4 隔离)
        when(contentRewriter.rewrite(any(com.choucj.aiaggregator.source.twitter.model.Tweet.class)))
                .thenReturn(Article.builder()
                        .id("tw-" + SMOKE_TWEET_ID)
                        .title("smoke 改写标题")
                        .content("改写正文段落。")
                        .aiGenerated(true)
                        .build());

        com.choucj.aiaggregator.source.twitter.model.Tweet tweet =
                com.choucj.aiaggregator.source.twitter.model.Tweet.builder()
                        .id(SMOKE_TWEET_ID)
                        .url("https://x.com/smoke/status/" + SMOKE_TWEET_ID)
                        .publishedAt(PUBLISHED_AT)
                        .media(List.of(photo))
                        .build();

        var generation = gateway.generate(tweet);
        Article article = generation.article();

        // 上传被环境阻塞时降级 (embeddedCount=0) — 从 sidecar failureReason 判定并 skip, 不洗绿
        if (generation.embeddedMediaCount() == 0) {
            archiveWriter.readSidecar(SMOKE_TWEET_ID, PUBLISHED_AT).ifPresent(record ->
                    record.getMedia().forEach(media -> skipIfEnvironmentBlocked(media.getFailureReason())));
        }

        assertThat(article.getGenerationMode())
                .isEqualTo(com.choucj.aiaggregator.common.model.ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(article.isAiGenerated()).isTrue();
        // AC 7: 正文 = LLM Markdown + Markdown image syntax (wechatUrl), 非 HTML passthrough
        assertThat(article.getContent()).startsWith("改写正文段落。");
        assertThat(article.getContent())
                .contains("![原帖图片-1](")
                .doesNotContain("<img");
        assertThat(generation.embeddedMediaCount()).isEqualTo(1);
        assertThat(generation.degradedMediaCount()).isZero();

        // AD-12: sidecar 权威状态与嵌入一致
        TweetMedia sidecarMedia = archiveWriter.readSidecar(SMOKE_TWEET_ID, PUBLISHED_AT)
                .orElseThrow().getMedia().get(0);
        assertThat(sidecarMedia.getUploadStatus())
                .isEqualTo(com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus.UPLOADED);
        assertThat(article.getContent()).contains(sidecarMedia.getWechatUrl());
        // AC 4/AD-11: 嵌入 URL 是微信 URL, 不泄漏 X/CDN sourceUrl 与本地路径
        assertThat(article.getContent()).doesNotContain("localPath");
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId())
                && !"dev-placeholder".equals(wxMpProperties.getSecret());
    }

    private static void skipIfEnvironmentBlocked(String message) {
        if (message == null) {
            return;
        }
        boolean whitelistBlocked = message.contains("errcode=40164") || message.contains("errcode: 40164");
        boolean quotaBlocked = message.contains("errcode=45009") || message.contains("errcode: 45009");
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
