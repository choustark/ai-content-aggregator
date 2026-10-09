package com.choucj.aiaggregator.publish.e2e;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.processor.MediaAwareRewriteGenerationGateway;
import com.choucj.aiaggregator.publish.storage.ArchivedArticle;
import com.choucj.aiaggregator.publish.storage.ArticleArchiveRepository;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import io.micrometer.core.instrument.MeterRegistry;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import org.mockito.Answers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 10.12: 媒体真实交付 E2E 失败路径 (@Tag("external"))。
 *
 * <p>必需媒体 (PHOTO) 指向不可达 URL (127.0.0.1:9, 连接拒绝 → Retryable → 下载终态失败) →
 * {@code MediaAwareRewriteArticleGenerator} 四层收敛:
 * <ol>
 *   <li>media.json sidecar: download=FAILED_TERMINAL (三阶段证据)</li>
 *   <li>文章快照: failureStage=MEDIA_DOWNLOAD / status=DELIVERY_FAILED</li>
 *   <li>Redis 镜像: article:{id}:status=DELIVERY_FAILED</li>
 *   <li>TaskQueue: taskId="delivery:tw-{id}" state=DEAD_LETTER + dead-letter 集合成员</li>
 * </ol>
 * 且 {@code WxMpService.addDraft} 零交互 (草稿 0 次 — WxMpService 为直调依赖, 须 mock 才可断言)。
 *
 * <p>本地无 Redis 时 assume-skip 不 fail (Redis 是四层收敛的必需基础设施)。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "wechat.mp.video.enabled=true",
        "archive.enabled=true",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WeChatDeliveryE2EFailureTest {

    private static final String TWEET_ID = "e2e1012fail01";
    private static final String ARTICLE_ID = "tw-" + TWEET_ID;
    private static final String TASK_ID = "delivery:" + ARTICLE_ID;
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    @TempDir
    static Path tempBase;

    @MockBean
    private com.choucj.aiaggregator.source.twitter.TwitterSource twitterSource;

    @MockBean
    private ContentRewriter contentRewriter;

    /** WxMpService 是 WeChatPublisher 直调依赖 (非 WeChatClient 接口), 须 mock 才可断言零交互. */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private WxMpService wxMpService;

    @Autowired(required = false)
    private MediaAwareRewriteGenerationGateway gateway;

    @Autowired(required = false)
    private TweetMediaArchiveWriter archiveWriter;

    @Autowired(required = false)
    private ArticleArchiveRepository articleArchiveRepository;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("twitter.media.enabled", () -> "true");
        registry.add("twitter.media.base-directory", () -> tempBase.toString());
        // 文章快照隔离到 TempDir, 不污染仓库 archive 目录
        registry.add("archive.base-directory", () -> tempBase.resolve("archive-root").toString());
    }

    @BeforeEach
    @AfterEach
    void requireRedisAndCleanKeys() {
        if (!redisReachable()) {
            return;
        }
        redisTemplate.delete(RedisKeys.articleStatus(ARTICLE_ID));
        redisTemplate.delete(RedisKeys.taskState(TASK_ID));
        redisTemplate.opsForSet().remove(RedisKeys.taskDeadLetter(), TASK_ID);
    }

    @Test
    @Timeout(120)
    void shouldConvergeDeliveryFailureAcrossAllFourStoragesWithoutDraft()
            throws me.chanjar.weixin.common.error.WxErrorException {
        assumeThat(gateway).as("wechat.mp.enabled=true 时媒体感知 gateway 应注册").isNotNull();
        assumeThat(archiveWriter).as("twitter.media.enabled=true 时 sidecar writer 应注册").isNotNull();
        assumeThat(articleArchiveRepository).as("archive.enabled=true 时文章归档仓库应注册").isNotNull();
        assumeThat(redisReachable()).as("四层收敛需要本地 Redis").isTrue();

        when(contentRewriter.rewrite(any(com.choucj.aiaggregator.source.twitter.model.Tweet.class)))
                .thenReturn(Article.builder()
                        .id(ARTICLE_ID)
                        .title("story-10.12 失败路径 E2E")
                        .content("改写正文段落, 必需媒体下载失败应整体交付失败。")
                        .aiGenerated(true)
                        .build());

        TweetMedia photo = TweetMedia.builder()
                .id("e2e-fail-photo")
                .type(TweetMediaType.PHOTO)
                // 不可达: discard 端口 9, 连接立即拒绝 → Retryable → 下载终态失败
                .sourceUrl("http://127.0.0.1:9/e2e-fail-photo.jpg")
                .order(0)
                .build();
        var tweet = com.choucj.aiaggregator.source.twitter.model.Tweet.builder()
                .id(TWEET_ID)
                .url("https://x.com/e2e/status/" + TWEET_ID)
                .publishedAt(PUBLISHED_AT)
                .media(List.of(photo))
                .build();

        // generate 返回失败 Article (不抛) — 四层收敛在 generator 内部完成
        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration generation =
                gateway.generate(tweet);

        assertThat(generation.article().getId()).isEqualTo(ARTICLE_ID);
        assertThat(generation.embeddedMediaCount()).isZero();
        assertThat(generation.degradedMediaCount()).isEqualTo(1);

        // 第 1 层: media.json sidecar 三阶段证据 — download=FAILED_TERMINAL
        var record = archiveWriter.readSidecar(TWEET_ID, PUBLISHED_AT).orElseThrow();
        assertThat(record.getMedia()).hasSize(1);
        var sidecarMedia = record.getMedia().get(0);
        assertThat(sidecarMedia.getDownload()).isNotNull();
        assertThat(sidecarMedia.getDownload().getStatus())
                .isEqualTo(MediaPhaseStatus.FAILED_TERMINAL);
        assertThat(sidecarMedia.getDownloadStatus())
                .isEqualTo(MediaDownloadStatus.FAILED);

        // 第 2 层: 文章快照 failureStage=MEDIA_DOWNLOAD
        ArchivedArticle snapshot = articleArchiveRepository.findByArticleId(ARTICLE_ID).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo(ArticleStatus.DELIVERY_FAILED);
        assertThat(snapshot.getFailureStage()).isEqualTo("MEDIA_DOWNLOAD");
        assertThat(snapshot.getFailureCode()).isEqualTo("MEDIA_DOWNLOAD_FAILED");
        assertThat(snapshot.getFailureTaskId()).isEqualTo(TASK_ID);

        // 第 3 层: Redis 镜像 article:{id}:status=DELIVERY_FAILED
        String redisStatus = redisTemplate.opsForValue().get(RedisKeys.articleStatus(ARTICLE_ID));
        assertThat(redisStatus).isEqualTo(ArticleStatus.DELIVERY_FAILED.name());

        // 第 4 层: TaskQueue 终态 — state=DEAD_LETTER + dead-letter 集合成员
        Map<Object, Object> state = redisTemplate.opsForHash()
                .entries(RedisKeys.taskState(TASK_ID));
        assertThat(state).isNotEmpty();
        assertThat(state.get("status")).isEqualTo("DEAD_LETTER");
        assertThat(state.get("articleId")).isEqualTo(ARTICLE_ID);
        Set<String> deadLetters = redisTemplate.opsForSet()
                .members(RedisKeys.taskDeadLetter());
        assertThat(deadLetters).contains(TASK_ID);

        // 草稿 0 次: WxMpService.addDraft 零交互
        verify(wxMpService.getDraftService(), never()).addDraft(any(WxMpAddDraft.class));

        // Story 10.12 指标: 下载终态失败入表; 下载无 retry_scheduled; prepare/reference 全矩阵恒为 0
        // (MediaMetrics 预注册 18 组合, 零值计数器存在 — 用 counterValue 断言值而非计数器缺失)
        assertThat(counterValue("photo", "download", "failed_terminal", "retryable")).isEqualTo(1.0);
        assertThat(meterRegistry.find("aiaggregator.media.phase.result")
                .tag("phase", "download").tag("outcome", "retry_scheduled").counter()).isNull();
        assertThat(counterValue("photo", "wechat_prepare", "succeeded", "none")).isZero();
        assertThat(counterValue("video", "article_reference", "succeeded", "none")).isZero();
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

    private double counterValue(String type, String phase, String outcome, String errorClass) {
        return meterRegistry.get("aiaggregator.media.phase.result")
                .tag("type", type)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .tag("errorClass", errorClass)
                .counter().count();
    }
}
