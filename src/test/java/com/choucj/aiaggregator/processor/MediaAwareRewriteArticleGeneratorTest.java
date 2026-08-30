package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.publish.storage.MediaArchiveRecord;
import com.choucj.aiaggregator.publish.storage.TweetMediaArchiveWriter;
import com.choucj.aiaggregator.publish.wechat.converter.MarkdownMediaInserter;
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 9.1 Task 2: {@link MediaAwareRewriteArticleGenerator} 单元测试.
 *
 * <p>覆盖 (AC 3, 4, 8, 9):
 * <ul>
 *   <li>编排顺序固定: rewrite → archiveMedia → gate.evaluate → prepareMedia →
 *       readSidecar → MarkdownMediaInserter (AD-2, InOrder 断言)</li>
 *   <li>LLM 媒体隔离: ContentRewriter 只接收同一 Tweet 实例且全程仅调用一次 (AC 4)</li>
 *   <li>纯文本无媒体仍生成 REWRITE_WITH_MEDIA 纯文字草稿 (AC 8/9)</li>
 *   <li>fail-fast: publishedAt null / archiver 缺失 / writer 缺失 / 推文级 BLOCKED /
 *       sidecar 缺失 / W1+W2 意外异常包装</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MediaAwareRewriteArticleGeneratorTest {

    private static final String TWEET_ID = "2083615699260313955";
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 30, 12, 0);
    private static final String WECHAT_URL = "https://mmbiz.qpic.cn/mmbiz/abc123/640";
    private static final String REWRITTEN_MARKDOWN = "## 改写标题\n\n正文段落。";

    @Mock
    private ContentRewriter contentRewriter;
    @Mock
    private TweetMediaArchiver archiver;
    @Mock
    private TweetPublishabilityGate gate;
    @Mock
    private WeChatMediaPreparer preparer;
    @Mock
    private TweetMediaArchiveWriter writer;

    private MediaAwareRewriteArticleGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new MediaAwareRewriteArticleGenerator(
                contentRewriter, Optional.of(archiver), Optional.of(gate), preparer,
                Optional.of(writer), new MarkdownMediaInserter());
    }

    // ===== 全链成功 + 编排顺序 (AD-2) =====

    @Test
    void should_generate_rewrite_with_media_article_when_full_chain_succeeds() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(1, 0, 0, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(uploadedSidecarPhoto()))
                        .build()));

        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration result = generator.generate(tweet);

        Article article = result.article();
        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(article.isAiGenerated()).isTrue();
        assertThat(article.getId()).isEqualTo("tw-" + TWEET_ID);
        // 正文 = LLM Markdown + sidecar wechatUrl 图片 syntax (AC 3/7)
        assertThat(article.getContent())
                .isEqualTo(REWRITTEN_MARKDOWN + "\n\n![原帖图片-1](" + WECHAT_URL + ")");
        // 审计表从 sidecar 权威状态生成, 不进微信正文
        assertThat(article.getMediaAuditMarkdown())
                .isNotNull()
                .contains(WECHAT_URL);
        assertThat(result.embeddedMediaCount()).isEqualTo(1);
        assertThat(result.degradedMediaCount()).isZero();

        InOrder order = inOrder(contentRewriter, archiver, gate, preparer, writer);
        order.verify(contentRewriter).rewrite(any(Tweet.class));
        order.verify(archiver).archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList());
        order.verify(gate).evaluate(any(Tweet.class));
        order.verify(preparer).prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList());
        order.verify(writer).readSidecar(TWEET_ID, PUBLISHED_AT);
    }

    // ===== AC 4: LLM 媒体隔离 =====

    @Test
    void should_pass_only_original_tweet_to_llm_and_call_it_once() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(1, 0, 0, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(uploadedSidecarPhoto()))
                        .build()));

        generator.generate(tweet);

        // 同一 Tweet 实例 (provider 原始元数据, 无 wechatUrl/localPath/sidecar/上传状态), 仅一次
        verify(contentRewriter, times(1)).rewrite(same(tweet));
    }

    // ===== 纯文本无媒体 (AC 8/9) =====

    @Test
    void should_generate_plain_text_draft_when_tweet_has_no_media() {
        Tweet tweet = pureTextTweet();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());

        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration result = generator.generate(tweet);

        assertThat(result.article().getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(result.article().getContent()).isEqualTo(REWRITTEN_MARKDOWN);
        assertThat(result.article().getMediaAuditMarkdown()).isNull();
        assertThat(result.embeddedMediaCount()).isZero();
        assertThat(result.degradedMediaCount()).isZero();
        verifyNoInteractions(archiver, preparer, writer);
    }

    // ===== AC 8: 全部媒体降级仍生成纯文字草稿 =====

    @Test
    void should_still_generate_draft_when_all_media_degraded() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 0, 1, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(0, 0, 1, List.of()));
        // sidecar 权威状态: 上传失败, 无 wechatUrl → 不可嵌入, 只降级
        TweetMedia failedPhoto = TweetMedia.builder()
                .id("m-1")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.FAILED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .failureReason("wechat upload error")
                .build();
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of(failedPhoto))
                        .build()));

        MediaAwareRewriteGenerationGateway.MediaAwareRewriteGeneration result = generator.generate(tweet);

        assertThat(result.article().getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(result.article().getContent()).isEqualTo(REWRITTEN_MARKDOWN);
        assertThat(result.embeddedMediaCount()).isZero();
        assertThat(result.degradedMediaCount()).isEqualTo(1);
        assertThat(result.article().getMediaAuditMarkdown()).isNotNull();
    }

    // ===== fail-fast (AC 9) =====

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
        MediaAwareRewriteArticleGenerator noArchiver = new MediaAwareRewriteArticleGenerator(
                contentRewriter, Optional.empty(), Optional.of(gate), preparer,
                Optional.of(writer), new MarkdownMediaInserter());
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());

        assertThatThrownBy(() -> noArchiver.generate(tweetWithPhoto()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("twitter.media.enabled");
        verifyNoInteractions(preparer, writer);
    }

    @Test
    void should_fail_fast_when_media_present_but_archive_writer_missing() {
        MediaAwareRewriteArticleGenerator noWriter = new MediaAwareRewriteArticleGenerator(
                contentRewriter, Optional.of(archiver), Optional.of(gate), preparer,
                Optional.empty(), new MarkdownMediaInserter());
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());

        assertThatThrownBy(() -> noWriter.generate(tweetWithPhoto()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("twitter.media.enabled");
        verify(preparer, never()).prepareMedia(anyString(), any(), anyList());
    }

    @Test
    void should_fail_fast_when_tweet_blocked_by_publishability_gate() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(0, 1, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(blockedResult());

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(TweetPublishabilityBlockedException.class)
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("BLOCKED");
        // 推文级 BLOCKED 阻断整篇草稿: 无微信上传 (AC 8)
        verify(preparer, never()).prepareMedia(anyString(), any(), anyList());
        verifyNoInteractions(writer);
    }

    @Test
    void should_fail_retryable_when_sidecar_missing_after_prepare_media() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
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

    @Test
    void should_fail_retryable_when_sidecar_empty_after_prepare_media() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));
        when(gate.evaluate(any(Tweet.class))).thenReturn(publishableResult());
        when(preparer.prepareMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new MediaPreparationResult(0, 1, 0, List.of()));
        when(writer.readSidecar(TWEET_ID, PUBLISHED_AT))
                .thenReturn(Optional.of(MediaArchiveRecord.builder()
                        .tweetId(TWEET_ID)
                        .media(List.of())
                        .build()));

        assertThatThrownBy(() -> generator.generate(tweet))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("sidecar empty");
    }

    @Test
    void should_fail_fast_when_media_present_but_publishability_gate_missing() {
        MediaAwareRewriteArticleGenerator noGate = new MediaAwareRewriteArticleGenerator(
                contentRewriter, Optional.of(archiver), Optional.empty(), preparer,
                Optional.of(writer), new MarkdownMediaInserter());
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
        when(archiver.archiveMedia(eq(TWEET_ID), eq(PUBLISHED_AT), anyList()))
                .thenReturn(new TweetMediaArchiver.ArchiveResult(1, 0, 0, List.of()));

        assertThatThrownBy(() -> noGate.generate(tweetWithPhoto()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining(TWEET_ID)
                .hasMessageContaining("TweetPublishabilityGate")
                .hasMessageContaining("twitter.media.enabled");
        verify(preparer, never()).prepareMedia(anyString(), any(), anyList());
        verify(writer, never()).readSidecar(anyString(), any());
    }

    // ===== W1+W2: 意外 RuntimeException 包装为 Retryable =====

    @Test
    void should_wrap_unexpected_runtime_exception_as_retryable() {
        Tweet tweet = tweetWithPhoto();
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(rewrittenArticle());
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

    private static Tweet tweetWithPhoto() {
        return pureTextTweet().toBuilder()
                .media(List.of(TweetMedia.builder()
                        .id("m-1")
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://pbs.twimg.com/media/photo-1.jpg")
                        .build()))
                .build();
    }

    /** LLM 改写输出 (ContentRewriter mock 返回; 不含任何媒体信息, AC 4)。 */
    private static Article rewrittenArticle() {
        return Article.builder()
                .id("tw-" + TWEET_ID)
                .title("改写标题")
                .content(REWRITTEN_MARKDOWN)
                .source("来源:@alice")
                .originalUrl("https://x.com/alice/status/" + TWEET_ID)
                .aiGenerated(true)
                .generationMode(ContentGenerationMode.REWRITE)
                .build();
    }

    private static TweetMedia uploadedSidecarPhoto() {
        return TweetMedia.builder()
                .id("m-1")
                .type(TweetMediaType.PHOTO)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .localPath("media/twitter/2026-08-30/" + TWEET_ID + "/photo-1.jpg")
                .wechatUrl(WECHAT_URL)
                .build();
    }

    private static com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult publishableResult() {
        return com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult.builder()
                .tweetId(TWEET_ID)
                .tweetStatus(PublishabilityStatus.PUBLISHABLE)
                .mediaDecisions(List.of())
                .build();
    }

    private static com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult blockedResult() {
        return com.choucj.aiaggregator.source.twitter.media.model.PublishabilityResult.builder()
                .tweetId(TWEET_ID)
                .tweetStatus(PublishabilityStatus.BLOCKED)
                .tweetReason("推文已被删除")
                .mediaDecisions(List.of())
                .build();
    }
}
