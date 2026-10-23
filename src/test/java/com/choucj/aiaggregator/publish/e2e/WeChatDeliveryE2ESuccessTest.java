package com.choucj.aiaggregator.publish.e2e;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.processor.MediaAwareRewriteGenerationGateway;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.WeChatPublisher;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaVariant;
import io.micrometer.core.instrument.MeterRegistry;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 10.12: 媒体真实交付 E2E 成功路径 (@Tag("external"))。
 *
 * <p>全链路: fixture Tweet (@MockBean TwitterSource 注入) → 改写 (@MockBean ContentRewriter
 * 固定 Markdown) → <b>真实媒体下载</b> (本地 HttpServer 提供 fixture 字节, MediaDownloadClient
 * 严格链路真实执行) → publishability gate → <b>真实微信准备</b> (PHOTO uploadimg + VIDEO
 * materialFileUpload) → 正文装配 (articleReference) → <b>真实草稿创建</b> (addDraft)。
 *
 * <p>守卫策略 (复用 Spike 10.9/10.11 形态): 无真实凭据 / 无样例文件 / 40164 白名单、
 * 45009 配额、40013/40001/40014 凭据、DNS 阻塞时 assume-skip, 不 fail CI
 * (样例文件缺失在读取前 assumeTrue, 避免 NoSuchFileException 硬失败)。
 * Story 10.15: 成功创建的真实草稿在 finally 中经 {@code draft/delete}
 * (WxJava {@code delDraft}) 自动删除, 仅删本测试自建草稿, 替代 created-artifacts.txt
 * 人工清理模式; 删除失败时 mediaId 追加 {@code target/release-acceptance/pending-cleanup.txt}
 * 供人工清理 (full mediaId 只落 target/, 不入仓库), 不掩盖测试结果。
 * 同时产出脱敏证据报告 {@code target/spike-10.12/evidence-report.md}。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "wechat.mp.video.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WeChatDeliveryE2ESuccessTest {

    private static final String TWEET_ID = "e2e1012succ01";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    private static final Path SAMPLE_IMAGE = Path.of(
            "archive/media/twitter/2026-08-23/2090838453126566066/PixPin_2026-05-31_23-43-58.png");
    private static final Path SAMPLE_VIDEO = Path.of("target/spike-8.2/wechat-video-test.mp4");
    private static final Path ARTIFACTS_DIR = Path.of("target/spike-10.12");

    @TempDir
    static Path tempBase;

    private static HttpServer mediaServer;
    private static volatile int mediaServerPort;

    @MockBean
    private com.choucj.aiaggregator.source.twitter.TwitterSource twitterSource;

    /** LLM stub — E2E 验证媒体链路, 不依赖 LLM API key (frozen 决议). */
    @MockBean
    private ContentRewriter contentRewriter;

    @Autowired(required = false)
    private MediaAwareRewriteGenerationGateway gateway;

    @Autowired(required = false)
    private TweetMediaArchiveWriter archiveWriter;

    @Autowired(required = false)
    private WeChatPublisher weChatPublisher;

    @Autowired
    private WxMpProperties wxMpProperties;

    /** 真实 WxMpService (不 mock) — Story 10.15 draft/delete teardown 经 WxJava draft API 执行。 */
    @Autowired
    private WxMpService wxMpService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("twitter.media.enabled", () -> "true");
        registry.add("twitter.media.base-directory", () -> tempBase.toString());
    }

    @AfterAll
    static void stopServer() {
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
    }

    @Test
    @Timeout(300)
    void shouldCreateDraftFromFixtureTweetWithPhotoAndVideoMix() throws IOException {
        assumeThat(gateway).as("wechat.mp.enabled=true 时媒体感知 gateway 应注册").isNotNull();
        assumeThat(archiveWriter).as("twitter.media.enabled=true 时 sidecar writer 应注册").isNotNull();
        assumeThat(weChatPublisher).as("wechat.mp.enabled=true 时 WeChatPublisher 应注册").isNotNull();
        assumeThat(hasRealCredentials()).as("需要真实的微信公众号 app-id / secret").isTrue();
        assumeThat(redisReachable()).as("草稿状态机需要本地 Redis").isTrue();
        assumeTrue(Files.exists(SAMPLE_IMAGE), "跳过: 样例图片缺失 - " + SAMPLE_IMAGE);
        assumeTrue(Files.exists(SAMPLE_VIDEO), "跳过: 样例视频缺失 - " + SAMPLE_VIDEO);

        startMediaServer();
        String photoUrl = "http://127.0.0.1:" + mediaServerPort + "/photo.png";
        String videoUrl = "http://127.0.0.1:" + mediaServerPort + "/video.mp4";

        TweetMedia photo = TweetMedia.builder()
                .id("e2e-photo-1")
                .type(TweetMediaType.PHOTO)
                .sourceUrl(photoUrl)
                .order(0)
                .build();
        TweetMedia video = TweetMedia.builder()
                .id("e2e-video-1")
                .type(TweetMediaType.VIDEO)
                .sourceUrl(videoUrl)
                .order(1)
                .variants(List.of(TweetMediaVariant.builder()
                        .url(videoUrl)
                        .contentType("video/mp4")
                        .bitrate(832000L)
                        .width(1280)
                        .height(720)
                        .build()))
                .build();

        whenRewriteReturnsArticle();
        var tweet = com.choucj.aiaggregator.source.twitter.model.Tweet.builder()
                .id(TWEET_ID)
                .url("https://x.com/e2e/status/" + TWEET_ID)
                .publishedAt(PUBLISHED_AT)
                .media(List.of(photo, video))
                .build();

        long startNanos = System.nanoTime();
        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration generation =
                gateway.generate(tweet);
        long generateMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 微信侧环境阻塞时 skip, 不洗绿 (三阶段 sidecar 证据判定)
        if (generation.embeddedMediaCount() < 2) {
            archiveWriter.readSidecar(TWEET_ID, PUBLISHED_AT).ifPresent(record ->
                    record.getMedia().forEach(media ->
                            skipIfEnvironmentBlocked(media.getFailureReason())));
        }

        assertThat(generation.article().getGenerationMode())
                .isEqualTo(com.choucj.aiaggregator.common.model.ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(generation.embeddedMediaCount()).isEqualTo(2);
        assertThat(generation.degradedMediaCount()).isZero();

        // sidecar 三阶段证据: PHOTO/VIDEO 全部 SUCCEEDED (10.12 观测的权威来源)
        MediaArchiveRecord record = archiveWriter.readSidecar(TWEET_ID, PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia()).hasSize(2);
        record.getMedia().forEach(media -> {
            assertThat(media.getDownload()).isNotNull();
            assertThat(media.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
            assertThat(media.getWechatPrepare()).isNotNull();
            assertThat(media.getWechatPrepare().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
            assertThat(media.getArticleReference()).isNotNull();
            assertThat(media.getArticleReference().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        });

        // Story 10.12 指标: 单推文单次交付, download/wechat_prepare/article_reference
        // 各自 photo+video succeeded 恰为 1.0 (与单测同口径, 过宽断言会掩盖双计缺陷)
        assertThat(counterValue("photo", "download", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("video", "download", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("photo", "wechat_prepare", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("video", "wechat_prepare", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("photo", "article_reference", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("video", "article_reference", "succeeded", "none")).isEqualTo(1.0);

        // 真实草稿创建 (封面素材缺失属账号未预备, assume-skip 不洗绿)
        long publishStart = System.nanoTime();
        String draftMediaId = null;
        long publishMs = -1;
        try {
            draftMediaId = weChatPublisher.publishDraft(generation.article());
            // 计时止于 publishDraft 返回: finally 中 draft/delete teardown 耗时不得计入草稿创建耗时
            publishMs = (System.nanoTime() - publishStart) / 1_000_000;
        } catch (NonRetryableException e) {
            if (e.getMessage() != null && e.getMessage().contains("封面")) {
                writeEvidenceReport("SKIP(封面素材缺失)", record, generateMs, -1, "THUMB_MISSING");
                assumeTrue(false, "跳过: 账号缺少封面永久图片素材 (ai_content_cover) - " + e.getMessage());
            }
            writeEvidenceReport("FAIL(addDraft)", record, generateMs, -1, "ADD_DRAFT_ERROR");
            throw e;
        } finally {
            // Story 10.15: draft/delete 自动 teardown (仅删自建草稿), 替代 created-artifacts.txt 人工模式;
            // 清理失败不掩盖测试结果, 只记录待人工清理清单
            deleteSelfCreatedDraft(draftMediaId);
        }

        assertThat(draftMediaId).isNotBlank();
        writeEvidenceReport("DRAFT_CREATED", record, generateMs, publishMs, "none");

        System.out.printf("[story-10.12] E2E 成功路径完成: draftMediaIdPrefix=%s, generateMs=%d, publishMs=%d%n",
                prefix(draftMediaId, 8), generateMs, publishMs);
    }

    // ===== 基建 =====

    private void startMediaServer() throws IOException {
        byte[] photoBytes = Files.readAllBytes(SAMPLE_IMAGE.toAbsolutePath().normalize());
        byte[] videoBytes = Files.readAllBytes(SAMPLE_VIDEO.toAbsolutePath().normalize());

        mediaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mediaServer.setExecutor(Executors.newSingleThreadExecutor());
        mediaServer.createContext("/photo.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, photoBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(photoBytes);
            }
        });
        mediaServer.createContext("/video.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, videoBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(videoBytes);
            }
        });
        mediaServer.start();
        mediaServerPort = mediaServer.getAddress().getPort();
    }

    private void whenRewriteReturnsArticle() {
        org.mockito.Mockito.when(contentRewriter.rewrite(
                        org.mockito.ArgumentMatchers.any(
                                com.choucj.aiaggregator.source.twitter.model.Tweet.class)))
                .thenReturn(Article.builder()
                        .id("tw-" + TWEET_ID)
                        .title("story-10.12 E2E 改写标题")
                        .content("改写正文段落, 用于媒体交付 E2E。")
                        .aiGenerated(true)
                        .build());
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId())
                && !"dev-placeholder".equals(wxMpProperties.getSecret());
    }

    private boolean redisReachable() {
        if (redisTemplate == null) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(redisTemplate.getConnectionFactory()
                    .getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }

    /** 与 Spike 10.9/10.11 统一口径: 40164/45009/40013/40001/40014 + DNS 特征 → skip. */
    private static void skipIfEnvironmentBlocked(String message) {
        if (message == null) {
            return;
        }
        boolean blocked = message.contains("errcode=40164")
                || message.contains("errcode=45009")
                || message.contains("errcode=40013")
                || message.contains("errcode=40001")
                || message.contains("errcode=40014")
                || message.contains("UnknownHostException")
                || message.contains("nodename nor servname provided");
        assumeTrue(!blocked, "跳过: 外部微信环境阻塞 - " + message);
    }

    /**
     * Story 10.15 draft/delete 自动 teardown: 经 WxJava {@code delDraft} 删除本测试自建草稿。
     * 删除失败不掩盖测试结果, 只把 full mediaId 追加到
     * {@code target/release-acceptance/pending-cleanup.txt} (不入仓库) 供人工清理,
     * 控制台仅输出前缀脱敏标识。
     */
    private void deleteSelfCreatedDraft(String draftMediaId) {
        if (!StringUtils.hasText(draftMediaId)) {
            return; // 草稿未创建 (skip/失败路径), 无需清理
        }
        try {
            Boolean deleted = wxMpService.getDraftService().delDraft(draftMediaId);
            if (Boolean.TRUE.equals(deleted)) {
                System.out.printf("[story-10.12] 自建草稿已自动删除: draftMediaIdPrefix=%s%n",
                        prefix(draftMediaId, 8));
            } else {
                recordPendingCleanup(draftMediaId, "delDraft 返回 false");
            }
        } catch (Exception e) {
            // 脱敏: 控制台只打异常类名与前缀, full mediaId 只落 target/ 待人工清单
            recordPendingCleanup(draftMediaId, e.getClass().getSimpleName());
        }
    }

    /** 清理失败 → 追加待人工清理清单 (与 story-10.15 release 测试共用同一文件, 守门器读取标记 CONCERN)。 */
    private static void recordPendingCleanup(String draftMediaId, String reason) {
        String line = LocalDateTime.now() + " story-10.12-e2e-success draftMediaId=" + draftMediaId
                + " reason=" + reason + System.lineSeparator();
        try {
            Path pending = Path.of("target/release-acceptance/pending-cleanup.txt")
                    .toAbsolutePath().normalize();
            Files.createDirectories(pending.getParent());
            Files.writeString(pending, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.printf("[story-10.12] 待清理清单写入失败(请人工记录该 mediaId): err=%s%n",
                    e.getMessage());
        }
        System.out.printf("[story-10.12][CONCERN] draft/delete 清理失败 (reason=%s), "
                        + "draftMediaIdPrefix=%s 已记录待人工清理%n",
                reason, prefix(draftMediaId, 8));
    }

    /**
     * 脱敏证据报告: articleId/draftMediaId 仅前缀, 含媒体类型/阶段结果/耗时/错误码/人工预览待填栏。
     */
    private static void writeEvidenceReport(String draftState, MediaArchiveRecord record,
                                            long generateMs, long publishMs, String errorCode) {
        StringBuilder report = new StringBuilder();
        report.append("# Story 10.12 媒体真实交付 E2E 证据报告\n\n");
        report.append("- 运行时间: ").append(LocalDateTime.now()).append('\n');
        report.append("- 草稿状态: ").append(draftState).append('\n');
        report.append("- articleId (脱敏): tw-").append(TWEET_ID, 0, 6).append("***\n");
        report.append("- 生成阶段耗时(ms): ").append(generateMs).append('\n');
        report.append("- 草稿创建耗时(ms): ").append(publishMs).append('\n');
        report.append("- 错误码: ").append(errorCode).append('\n');
        report.append("\n## 媒体清单 (type × 三阶段)\n\n");
        for (TweetMedia media : record.getMedia()) {
            report.append("- type=").append(media.getType())
                    .append(", download=")
                    .append(media.getDownload() == null ? "N/A" : media.getDownload().getStatus())
                    .append(", wechatPrepare=")
                    .append(media.getWechatPrepare() == null ? "N/A" : media.getWechatPrepare().getStatus())
                    .append(", articleReference=")
                    .append(media.getArticleReference() == null ? "N/A" : media.getArticleReference().getStatus())
                    .append('\n');
        }
        report.append("\n## 人工预览待填栏\n\n");
        report.append("- 草稿处置: 已由 draft/delete 自动清理 (仅删自建草稿);\n")
                .append("  若 target/release-acceptance/pending-cleanup.txt 有记录则该草稿待人工删除\n");
        report.append("- 公众号后台预览结果: 不适用 (草稿已由 draft/delete 自动清理; ")
                .append("如需后台人工预览, 临时禁用 deleteSelfCreatedDraft 后重跑)\n");
        report.append("- 人工发布状态: 不适用 (草稿已自动清理, 不进入发布流程)\n");
        try {
            Path evidence = ARTIFACTS_DIR.resolve("evidence-report.md").toAbsolutePath().normalize();
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, report.toString());
        } catch (Exception e) {
            System.out.printf("[story-10.12] evidence-report.md 写入失败: err=%s%n", e.getMessage());
        }
    }

    private double counterValue(String type, String phase, String outcome, String errorClass) {
        return meterRegistry.get("aiaggregator.media.phase.result")
                .tag("type", type)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .tag("errorClass", errorClass)
                .counter().count();
    }

    private static String prefix(String value, int length) {
        if (value == null) {
            return "null";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }
}
