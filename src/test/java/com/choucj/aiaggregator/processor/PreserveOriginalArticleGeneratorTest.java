package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.converter.OriginalPostRenderer;
import com.choucj.aiaggregator.publish.wechat.media.MediaPreparationResult;
import com.choucj.aiaggregator.publish.wechat.media.WeChatMediaPreparer;
import com.choucj.aiaggregator.source.twitter.media.TweetMediaArchiver;
import com.choucj.aiaggregator.source.twitter.media.TweetPublishabilityGate;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 8.6 Task 3: {@link PreserveOriginalArticleGenerator} 真 gateway 单测.
 *
 * <p>覆盖 Task 3.6 全部分支:
 * <ul>
 *   <li>全链成功 (archiver → gate → preparer → readSidecar → render → toArticle,
 *       顺序断言 + generationMode/mediaAuditMarkdown 装配)</li>
 *   <li>纯文本无媒体 (archiver/preparer/writer 零交互)</li>
 *   <li>防御: publishedAt null / 有媒体但 archiver 缺失 / 有媒体但 writer 缺失 /
 *       gate 推文级 BLOCKED / html &gt; 19500 (全部 NonRetryable, message 含 tweetId, N4)</li>
     *   <li>sidecar 缺失 → Retryable fail-fast, 防缺图片/缺审计草稿静默发布</li>
 *   <li>render/toArticle 同源配对断言 (8.5 defer#6)</li>
 *   <li>W1+W2: 外部调用意外 RuntimeException → RetryableException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PreserveOriginalArticleGeneratorTest {

    private static final String TWEET_ID = "2083615699260313955";

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 29, 12, 0);

    private static final String WECHAT_URL = "https://mmbiz.qpic.cn/mmbiz/abc123/640";

    @Mock
    private TweetMediaArchiver archiver;

    @Mock
    private TweetPublishabilityGate gate;

    @Mock
    private WeChatMediaPreparer preparer;

    @Mock
    private TweetMediaArchiveWriter writer;

    private PreserveOriginalArticleGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PreserveOriginalArticleGenerator(
                Optional.of(archiver), Optional.of(gate), preparer,
                Optional.of(writer), new OriginalPostRenderer());
    }

    // ===== 全链成功 + 审计 + 同源配对 =====

    @Test
    void should_generate_preserve_original_article_when_full_chain_succeeds() {
        Tweet tweet = tweetWithOneUploadedPhoto();
        TweetMedia sidecarMedia = uploadedPhotoMedia(null);

        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(1, 0, 0, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(sidecarMedia))
                        .build()));

        Article article = generator.generate(tweet);

        assertThat(article).isNotNull();
        assertThat(article.getId()).isEqualTo("tw-" + TWEET_ID);
        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL);
        assertThat(article.isAiGenerated()).isFalse();
        assertThat(article.getInnovationScore()).isZero();
        // render/toArticle 同源: article.content 即 renderer HTML (含已上传图片内嵌)
        assertThat(article.getContent())
                .contains("<img src=\"" + WECHAT_URL + "\"/>")
                .doesNotContain("&lt;img");
        // mediaAuditMarkdown 从 sidecar 权威状态生成 (AC4 字段列)
        assertThat(article.getMediaAuditMarkdown())
                .isNotNull()
                .contains("类型")
                .contains("PHOTO")
                .contains(String.valueOf(MediaUploadStatus.UPLOADED))
                .contains(String.valueOf(PublishabilityStatus.PUBLISHABLE))
                .contains(WECHAT_URL)
                .contains(sidecarMedia.getLocalPath());

        InOrder order = inOrder(archiver, gate, preparer, writer);
        order.verify(archiver).archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList());
        order.verify(gate).evaluate(any(Tweet.class));
        order.verify(preparer).prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList());
        order.verify(writer).readSidecar(TWEET_ID, PUBLISHED_AT);
    }

    @Test
    void should_keep_render_and_article_from_same_source() {
        Tweet tweet = tweetWithOneUploadedPhoto();
        TweetMedia sidecarMedia = uploadedPhotoMedia(null);
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(1, 0, 0, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(sidecarMedia))
                        .build()));

        Article article = generator.generate(tweet);

        // 同源配对 (8.5 defer#6): 单测内用同一输入直接调 renderer, 结果与 gateway 产出逐字段一致
        OriginalPostRenderer renderer = new OriginalPostRenderer();
        var result = renderer.render(tweet, List.of(sidecarMedia));
        Article expected = renderer.toArticle(tweet, result);
        assertThat(article.getContent()).isEqualTo(expected.getContent());
        assertThat(article.getTitle()).isEqualTo(expected.getTitle());
        assertThat(article.getDigest()).isEqualTo(expected.getDigest());
        assertThat(article.getOriginalUrl()).isEqualTo(expected.getOriginalUrl());
    }

    // ===== 纯文本无媒体 =====

    @Test
    void should_render_pure_text_without_media_interactions_when_tweet_has_no_media() {
        Tweet tweet = pureTextTweet();

        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());

        Article article = generator.generate(tweet);

        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL);
        assertThat(article.getId()).isEqualTo("tw-" + TWEET_ID);
        assertThat(article.getContent()).contains("纯文本正文");
        // 无媒体: mediaAuditMarkdown 恒 null, archiver/preparer/writer 零交互 (AC4/D-E)
        assertThat(article.getMediaAuditMarkdown()).isNull();
        verifyNoInteractions(archiver, preparer, writer);
    }

    // ===== 防御分支 =====

    @Test
    void should_fail_fast_when_published_at_missing() {
        Tweet tweet = pureTextTweet().toBuilder().publishedAt(null).build();

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("publishedAt");
        verifyNoInteractions(archiver, gate, preparer, writer);
    }

    @Test
    void should_fail_fast_when_media_present_but_archiver_missing() {
        PreserveOriginalArticleGenerator noArchiver = new PreserveOriginalArticleGenerator(
                Optional.empty(), Optional.of(gate), preparer,
                Optional.of(writer), new OriginalPostRenderer());

        assertThatThrownBy(() -> noArchiver.generate(tweetWithOneUploadedPhoto()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("twitter.media.enabled");
        verifyNoInteractions(preparer, writer);
    }

    @Test
    void should_fail_fast_when_media_present_but_archive_writer_missing() {
        PreserveOriginalArticleGenerator noWriter = new PreserveOriginalArticleGenerator(
                Optional.of(archiver), Optional.of(gate), preparer,
                Optional.empty(), new OriginalPostRenderer());

        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 1, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());

        assertThatThrownBy(() -> noWriter.generate(tweetWithOneUploadedPhoto()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("twitter.media.enabled");
        verifyNoInteractions(preparer);
    }

    @Test
    void should_fail_fast_when_tweet_blocked_by_publishability_gate() {
        Tweet tweet = tweetWithOneUploadedPhoto();

        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 1, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(PublishabilityResultFixture.blocked());

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("BLOCKED");
        verify(preparer, never()).prepareMedia(anyString(), any(), anyList());
        verifyNoInteractions(writer);
    }

    @Test
    void should_fail_fast_when_rendered_html_exceeds_budget() {
        // 文本 20000 codepoint → 渲染 HTML 必然超过 19500 预算 (AC7)
        Tweet tweet = pureTextTweet().toBuilder()
                .formattedText("字".repeat(20000))
                .build();

        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("htmlLength");
    }

    @Test
    void should_fail_retryable_when_sidecar_missing_after_prepare_media() {
        Tweet tweet = tweetWithOneUploadedPhoto();

        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(0, 0, 1, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("sidecar");
    }

    // ===== W1+W2: 意外 RuntimeException 包装为 RetryableException =====

    @Test
    void should_wrap_unexpected_runtime_exception_as_retryable() {
        Tweet tweet = tweetWithOneUploadedPhoto();
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("connection reset");
    }

    // ===== 媒体审计 failureReason 截断 (N2/R3-1 + AC4 ≤120) =====

    @Test
    void should_truncate_long_failure_reason_in_media_audit_markdown() {
        Tweet tweet = tweetWithOneUploadedPhoto();
        TweetMedia failedMedia = uploadedPhotoMedia("图片下载失败: https://pbs.twimg.com/media/very-long-url-"
                + "x".repeat(300));
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 0, 1, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(0, 0, 1, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(failedMedia))
                        .build()));

        Article article = generator.generate(tweet);

        assertThat(article.getMediaAuditMarkdown()).isNotNull();
        assertThat(article.getMediaAuditMarkdown()).contains("<url>");
        assertThat(article.getMediaAuditMarkdown()).doesNotContain("https://pbs.twimg.com");
        // 截断后 failureReason 列单元格 ≤ 120 codepoint (R3-1: "..." 占预算)
        for (String line : article.getMediaAuditMarkdown().split("\n")) {
            if (line.startsWith("| 1 ")) {
                String[] cells = line.split("\\|");
                String reasonCell = cells[cells.length - 2].trim();
                assertThat(reasonCell.codePointCount(0, reasonCell.length())).isLessThanOrEqualTo(120);
            }
        }
    }

    @Test
    void should_include_original_url_and_escape_markdown_table_cells_in_media_audit_markdown() {
        Tweet tweet = tweetWithOneUploadedPhoto();
        TweetMedia trickyMedia = uploadedPhotoMedia("下载失败|需要人工\n复核");
        trickyMedia.setLocalPath("media/twitter/2026-08-29/" + TWEET_ID + "/photo|1.jpg");
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 0, 1, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(0, 0, 1, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(trickyMedia))
                        .build()));

        Article article = generator.generate(tweet);

        assertThat(article.getMediaAuditMarkdown())
                .contains("原帖 URL")
                .contains("https://x.com/alice/status/" + TWEET_ID)
                .contains("下载失败\\|需要人工 复核")
                .contains("photo\\|1.jpg")
                .doesNotContain("下载失败|需要人工\n复核");
    }

    // ===== 辅助 =====

    private static Tweet pureTextTweet() {
        return Tweet.builder()
                .id(TWEET_ID)
                .author("alice")
                .formattedText("纯文本正文第一行")
                .url("https://x.com/alice/status/" + TWEET_ID)
                .publishedAt(PUBLISHED_AT)
                .build();
    }

    private static Tweet tweetWithOneUploadedPhoto() {
        return pureTextTweet().toBuilder()
                .media(List.of(uploadedPhotoMedia(null)))
                .build();
    }

    private static TweetMedia uploadedPhotoMedia(String failureReason) {
        return TweetMedia.builder()
                .id("m-1")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .localPath("media/twitter/2026-08-29/" + TWEET_ID + "/photo-1.jpg")
                .wechatUrl(WECHAT_URL)
                .failureReason(failureReason)
                .build();
    }

    private static com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult publishableResult() {
        return com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult.builder()
                .tweetId(TWEET_ID)
                .tweetStatus(PublishabilityStatus.PUBLISHABLE)
                .mediaDecisions(List.of())
                .build();
    }

    /** BLOCKED 判定 fixture (静态内部类, 避免与被测类概念混淆)。 */
    private static final class PublishabilityResultFixture {
        private static com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult blocked() {
            return com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult.builder()
                    .tweetId(TWEET_ID)
                    .tweetStatus(PublishabilityStatus.BLOCKED)
                    .tweetReason("推文已被删除")
                    .mediaDecisions(List.of())
                    .build();
        }
    }
}
