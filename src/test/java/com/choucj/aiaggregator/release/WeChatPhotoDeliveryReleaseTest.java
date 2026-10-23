package com.choucj.aiaggregator.release;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.processor.MediaAwareRewriteGenerationGateway;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.WeChatPublisher;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import io.micrometer.core.instrument.MeterRegistry;
import me.chanjar.weixin.mp.api.WxMpService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Story 10.15: PHOTO-only 成功交付 release 测试 (release acceptance 必需集, 不可跳过)。
 *
 * <p>复用 Story 10.12 真实交付 E2E 模式, 按 2026-10-22 No-Go 裁剪为 PHOTO-only:
 * fixture Tweet (@MockBean TwitterSource 注入) → 改写 (@MockBean ContentRewriter 固定
 * Markdown) → <b>真实媒体下载</b> (本地 HttpServer 提供仓库内稳定 fixture 字节) →
 * publishability gate → <b>真实微信准备</b> (PHOTO uploadimg) → 正文装配 →
 * <b>真实草稿创建</b> (addDraft)。不依赖视频样例文件, 不含 VIDEO 分支。
 *
 * <p>与开发 smoke 的本质区别: <b>无任何 assume 守卫</b> — 环境缺失由 preflight 前置
 * FAIL 兜底, 本类内任何异常都会真实失败并由守门器计入 gate failure, 不得以 skip 洗绿。
 *
 * <p>资源清理 (spec Always): 成功创建的真实草稿在 finally 中经 {@code draft/delete}
 * (WxJava {@code delDraft}) 自动删除, <b>仅删本测试自建的草稿</b>; 删除失败时把
 * mediaId 追加到 {@code target/release-acceptance/pending-cleanup.txt} 供人工清理
 * (full mediaId 只落 target/, 不入仓库; 控制台仅打前缀脱敏), 并不掩盖测试结果 —
 * 由守门器按该文件标记 CONCERN。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WeChatPhotoDeliveryReleaseTest {

    private static final String TWEET_ID = "rel1015phot01";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 10, 15, 9, 0);

    /** 仓库内稳定 PHOTO fixture — preflight 已断言存在, 此处只做真实读取。 */
    private static final String PHOTO_FIXTURE_CLASSPATH =
            "/fixtures/release-acceptance/photo.png";

    /** 清理失败待人工清单 (full mediaId 只落 target/, 不入仓库)。 */
    private static final Path PENDING_CLEANUP = Path.of("target/release-acceptance/pending-cleanup.txt");

    /** 脱敏证据报告输出目录。 */
    private static final Path EVIDENCE_DIR = Path.of("target/release-acceptance");

    @TempDir
    static Path tempBase;

    private static HttpServer mediaServer;
    private static volatile int mediaServerPort;

    @MockBean
    private com.choucj.aiaggregator.source.twitter.TwitterSource twitterSource;

    /** LLM stub — 与 10.12 E2E 同口径, 交付链路验证不依赖 LLM API key。 */
    @MockBean
    private ContentRewriter contentRewriter;

    @Autowired(required = false)
    private MediaAwareRewriteGenerationGateway gateway;

    @Autowired(required = false)
    private TweetMediaArchiveWriter archiveWriter;

    @Autowired(required = false)
    private WeChatPublisher weChatPublisher;

    /** 真实 WxMpService (不 mock) — draft/delete teardown 经 WxJava draft API 执行。 */
    @Autowired
    private WxMpService wxMpService;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("twitter.media.enabled", () -> "true");
        registry.add("twitter.media.base-directory", () -> tempBase.toString());
    }

    @BeforeEach
    void startMediaServer() throws IOException {
        // fixture 经 classpath 读取 (jar/目录两种 classpath 形态都成立)
        byte[] photoBytes;
        try (var stream = getClass().getResourceAsStream(PHOTO_FIXTURE_CLASSPATH)) {
            assertThat(stream).as("PHOTO fixture 应存在: %s", PHOTO_FIXTURE_CLASSPATH).isNotNull();
            photoBytes = stream.readAllBytes();
        }
        assertThat(photoBytes.length).as("PHOTO fixture 非空").isPositive();

        mediaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mediaServer.setExecutor(Executors.newSingleThreadExecutor());
        mediaServer.createContext("/photo.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, photoBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(photoBytes);
            }
        });
        mediaServer.start();
        mediaServerPort = mediaServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
    }

    @AfterEach
    void stopServerPerTest() {
        stopServer();
        mediaServer = null;
    }

    @Test
    @Timeout(300)
    void shouldDeliverPhotoFixtureToRealWeChatDraftAndCleanItUp() throws Exception {
        assertThat(gateway).as("wechat.mp.enabled=true 时媒体感知 gateway 应注册").isNotNull();
        assertThat(archiveWriter).as("twitter.media.enabled=true 时 sidecar writer 应注册").isNotNull();
        assertThat(weChatPublisher).as("wechat.mp.enabled=true 时 WeChatPublisher 应注册").isNotNull();

        String photoUrl = "http://127.0.0.1:" + mediaServerPort + "/photo.png";
        TweetMedia photo = TweetMedia.builder()
                .id("rel-photo-1")
                .type(TweetMediaType.PHOTO)
                .sourceUrl(photoUrl)
                // allowDownload 默认 false 会被 archiver 直接 SKIP, 交付链路要求真实下载
                .allowDownload(true)
                .order(0)
                .build();
        when(contentRewriter.rewrite(any(com.choucj.aiaggregator.source.twitter.model.Tweet.class)))
                .thenReturn(Article.builder()
                        .id("tw-" + TWEET_ID)
                        .title("story-10.15 PHOTO 交付 release 验收")
                        .content("release acceptance 成功路径: 单 PHOTO 真实交付草稿。")
                        // 失败收敛路径经 ArticleArchiveRepository.saveSnapshot, requireArticle
                        // 强制 createdAt 非空, fixture 需满足
                        .createdAt(PUBLISHED_AT)
                        .aiGenerated(true)
                        .build());
        var tweet = com.choucj.aiaggregator.source.twitter.model.Tweet.builder()
                .id(TWEET_ID)
                // publishability gate 推文级判定要求文本三字段不全 blank (T1), fixture 需携带源文本
                .content("release acceptance 成功路径 fixture: 单 PHOTO 真实交付草稿验收。")
                .url("https://x.com/release/status/" + TWEET_ID)
                .publishedAt(PUBLISHED_AT)
                .media(List.of(photo))
                .build();

        long startNanos = System.nanoTime();
        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration generation =
                gateway.generate(tweet);
        long generateMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(generation.article().getGenerationMode())
                .isEqualTo(com.choucj.aiaggregator.common.model.ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(generation.embeddedMediaCount()).isEqualTo(1);
        assertThat(generation.degradedMediaCount()).isZero();

        // sidecar 三阶段证据: PHOTO download/wechatPrepare/articleReference 全部 SUCCEEDED
        MediaArchiveRecord record = archiveWriter.readSidecar(TWEET_ID, PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia()).hasSize(1);
        record.getMedia().forEach(media -> {
            assertThat(media.getDownload()).isNotNull();
            assertThat(media.getDownload().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
            assertThat(media.getWechatPrepare()).isNotNull();
            assertThat(media.getWechatPrepare().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
            assertThat(media.getArticleReference()).isNotNull();
            assertThat(media.getArticleReference().getStatus()).isEqualTo(MediaPhaseStatus.SUCCEEDED);
        });

        // Story 10.12 观测口径: 单推文单次交付, photo 三阶段 succeeded 恰为 1.0
        assertThat(counterValue("photo", "download", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("photo", "wechat_prepare", "succeeded", "none")).isEqualTo(1.0);
        assertThat(counterValue("photo", "article_reference", "succeeded", "none")).isEqualTo(1.0);

        // 真实草稿创建 + finally 自动清理 (spec Always: 清理不掩盖测试结果)
        long publishStart = System.nanoTime();
        String draftMediaId = null;
        try {
            draftMediaId = weChatPublisher.publishDraft(generation.article());
            long publishMs = (System.nanoTime() - publishStart) / 1_000_000;
            assertThat(draftMediaId).isNotBlank();
            writeEvidenceReport("DRAFT_CREATED", record, generateMs, publishMs, "none");
            System.out.printf("[story-10.15] PHOTO 交付完成: draftMediaIdPrefix=%s, generateMs=%d, publishMs=%d%n",
                    prefix(draftMediaId, 8), generateMs, publishMs);
        } catch (Exception e) {
            writeEvidenceReport("FAIL(publishDraft)", record, generateMs, -1, "PUBLISH_DRAFT_ERROR");
            throw e;
        } finally {
            deleteSelfCreatedDraft(draftMediaId);
        }
    }

    // ===== 草稿清理 (draft/delete teardown) =====

    /**
     * finally 自动清理: 仅删除本测试创建的草稿 (draftMediaId 由本类 publishDraft 返回)。
     * 删除失败不掩盖测试结果, 只记录待人工清单并标记 CONCERN (由守门器读取)。
     */
    private void deleteSelfCreatedDraft(String draftMediaId) {
        if (!StringUtils.hasText(draftMediaId)) {
            return; // 草稿未创建 (测试已失败), 无需清理
        }
        try {
            Boolean deleted = wxMpService.getDraftService().delDraft(draftMediaId);
            if (Boolean.TRUE.equals(deleted)) {
                System.out.printf("[story-10.15] 自建草稿已自动删除: draftMediaIdPrefix=%s%n",
                        prefix(draftMediaId, 8));
            } else {
                recordPendingCleanup(draftMediaId, "delDraft 返回 false");
            }
        } catch (Exception e) {
            // 脱敏: 控制台只打异常类名与前缀, full mediaId 只落 target/ 待人工清单
            recordPendingCleanup(draftMediaId, e.getClass().getSimpleName());
        }
    }

    /** 清理失败 → 追加待人工清理清单 (full mediaId 只落 target/, 不入仓库)。 */
    private static void recordPendingCleanup(String draftMediaId, String reason) {
        String line = LocalDateTime.now() + " story-10.15-photo-release draftMediaId=" + draftMediaId
                + " reason=" + reason + System.lineSeparator();
        try {
            Files.createDirectories(PENDING_CLEANUP.getParent());
            Files.writeString(PENDING_CLEANUP, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception writeError) {
            System.out.printf("[story-10.15] 待清理清单写入失败(请人工记录该草稿): err=%s%n",
                    writeError.getClass().getSimpleName());
        }
        System.out.printf("[story-10.15][CONCERN] draft/delete 清理失败 (reason=%s), "
                        + "draftMediaIdPrefix=%s 已记录待人工清理: %s%n",
                reason, prefix(draftMediaId, 8), PENDING_CLEANUP);
    }

    /** 脱敏证据报告: 标识仅前缀, 不含凭据/完整素材标识/完整外部响应体。 */
    private static void writeEvidenceReport(String draftState, MediaArchiveRecord record,
                                            long generateMs, long publishMs, String errorCode) {
        StringBuilder report = new StringBuilder();
        report.append("# Story 10.15 PHOTO 交付 release 验收证据\n\n");
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
        try {
            Files.createDirectories(EVIDENCE_DIR);
            Path evidence = EVIDENCE_DIR.resolve("evidence-photo-delivery.md");
            Files.writeString(evidence, report.toString());
        } catch (Exception e) {
            System.out.printf("[story-10.15] 证据报告写入失败: err=%s%n", e.getClass().getSimpleName());
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
