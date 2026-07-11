package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.content.filter.ContentFilter;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.source.twitter.TwitterSource;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.6 {@link TwitterProcessor} 单元测试.
 *
 * <p>核心覆盖:
 * <ul>
 *   <li>AC-1 顺序执行 (fetch → filter → rewrite → publish)</li>
 *   <li>AC-3 per-article 故障隔离 (单条失败不阻塞其他)</li>
 *   <li>AC-4 fetch 阶段 catch Exception 兜底</li>
 *   <li>AC-5 summary 6 字段日志</li>
 *   <li>AC-8 W11/N4/Patch-5/N2 日志规范</li>
 *   <li>Patch-1 修复验证 — 不再调 TaskQueue.push/complete (ContentScheduler 在 twitter:run 级别跟踪)</li>
 *   <li>Patch-2 修复验证 — faultIsolationEnabled=false 时 per-article 异常透传到顶层</li>
 * </ul>
 *
 * <p><b>contentFilters mock 策略:</b>
 * TwitterProcessor 用 {@code filter.getClass().getSimpleName().contains("Comment"|"Innovation")}
 * 匹配阶段名 — Mockito mock 的 getClass() 返回 CGLIB 子类名不含 "Comment",
 * 故用 {@link CommentFilterStub} / {@link InnovationFilterStub} 具名静态内部类包装 delegate mock,
 * 让 getClass().getSimpleName() 返回 "CommentFilterStub" / "InnovationFilterStub" 触发分支匹配.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TwitterProcessorTest {

    @Mock
    private TwitterSource twitterSource;
    @Mock
    private ContentRewriter contentRewriter;
    @Mock
    private ContentPublisher contentPublisher;
    @Mock
    private ContentPublisher secondContentPublisher;
    @Mock
    private ContentFilter<Tweet> commentFilterDelegate;
    @Mock
    private ContentFilter<Tweet> innovationFilterDelegate;
    @Mock
    private ArticleStatusService articleStatusService;

    private ProcessorProperties properties;
    private TwitterProcessor processor;

    /**
     * 用真实 ProcessorProperties 实例 (taskIdPrefix 默认 "twitter", faultIsolationEnabled=true) +
     * 具名 stub 包装的 filter list 构造 TwitterProcessor — 不依赖 Spring Context.
     */
    @BeforeEach
    void setUp() {
        properties = new ProcessorProperties();
        List<ContentFilter<Tweet>> filters = List.of(
                new CommentFilterStub(commentFilterDelegate),
                new InnovationFilterStub(innovationFilterDelegate));
        processor = new TwitterProcessor(twitterSource, filters, contentRewriter,
                List.of(contentPublisher), properties, articleStatusService);
    }

    @Test
    void shouldRunFullPipelineHappyPath() {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1")).thenReturn(article("art-2"));

        processor.process();

        verify(contentRewriter, times(2)).rewrite(any(Tweet.class));
        verify(contentPublisher, times(2)).publish(any());
    }

    @Test
    void shouldInvokeAllContentPublishersForEachSuccessfulArticle() {
        processor = new TwitterProcessor(twitterSource, List.of(
                new CommentFilterStub(commentFilterDelegate),
                new InnovationFilterStub(innovationFilterDelegate)),
                contentRewriter, List.of(contentPublisher, secondContentPublisher), properties,
                articleStatusService);
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        Article a1 = article("art-1");
        Article a2 = article("art-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(t1)).thenReturn(a1);
        when(contentRewriter.rewrite(t2)).thenReturn(a2);

        processor.process();

        verify(contentPublisher).publish(a1);
        verify(contentPublisher).publish(a2);
        verify(secondContentPublisher).publish(a1);
        verify(secondContentPublisher).publish(a2);
    }

    @Test
    void shouldIsolatePerArticleFailureInRewriter() {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(t1))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "改写失败 id-1"));
        when(contentRewriter.rewrite(t2)).thenReturn(article("art-2"));

        processor.process();

        // 第 1 条失败但第 2 条继续处理 — per-article 隔离生效
        verify(contentPublisher, times(1)).publish(any());
    }

    @Test
    void shouldIsolatePerArticleFailureInPublisher() {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(t1)).thenReturn(article("art-1"));
        when(contentRewriter.rewrite(t2)).thenReturn(article("art-2"));
        doThrow(new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR, "归档失败"))
                .when(contentPublisher).publish(argThat(a -> "art-1".equals(a.getTitle())));

        processor.process();

        // 第 1 条 publish 失败但第 2 条继续处理 — per-article 隔离生效
        verify(contentPublisher, times(2)).publish(any());
    }

    @Test
    void shouldIsolateRuntimeExceptionInPublisher() {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));
        doThrow(new IllegalStateException("未预期异常")).when(contentPublisher).publish(any());

        processor.process();

        // RuntimeException (非 Retryable/NonRetryable) 仍被 catch(Exception) 捕获, 不穿透
        verify(contentPublisher, times(1)).publish(any());
    }

    @Test
    void shouldCatchFetchExceptionAndContinue(CapturedOutput output) {
        when(twitterSource.fetch()).thenThrow(new IllegalStateException("NPE 穿透"));

        processor.process();

        // L1 兜底: fetch 异常被吞, summary 正常输出
        assertThat(output.getOut()).contains("Pipeline 完成: 发现=0");
        assertThat(output.getOut()).contains("TwitterSource.fetch 失败");
        verify(contentRewriter, never()).rewrite(any(Tweet.class));
    }

    @Test
    void shouldCatchFetchRetryableAndContinue(CapturedOutput output) {
        when(twitterSource.fetch()).thenThrow(
                new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "fetch 失败"));

        processor.process();

        assertThat(output.getOut()).contains("Pipeline 完成: 发现=0");
        verify(contentRewriter, never()).rewrite(any(Tweet.class));
    }

    @Test
    void shouldDegradeWhenFilterFails(CapturedOutput output) {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        // CommentFilter 抛异常 → L3 阶段降级透传
        when(commentFilterDelegate.filter(any()))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "filter 失败"));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));

        processor.process();

        // 阶段降级后仍走到 rewrite — rewriteSuccess > 0
        verify(contentRewriter, times(1)).rewrite(any(Tweet.class));
        assertThat(output.getOut()).contains("评论筛选降级: 输入=1, 透传=1");
        assertThat(output.getOut()).contains("Pipeline 完成: 发现=1, 评论筛选通过=0");
    }

    @Test
    void shouldDegradeWhenFilterReturnsNull(CapturedOutput output) {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(null);
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));

        processor.process();

        verify(contentRewriter, times(1)).rewrite(any(Tweet.class));
        assertThat(output.getOut()).contains("筛选阶段返回 null");
        assertThat(output.getOut()).contains("评论筛选降级: 输入=1, 透传=1");
    }

    // ============ Patch-1 修复验证: 不再调 TaskQueue.push/complete ============

    /**
     * Patch-1 修复: per-article 循环不再 push/complete TaskQueue.
     * 早期实现 {@code push("twitter:tweet:{id}")} 让 taskId 残留 task:queue List,
     * 后续被 ContentScheduler 重新 poll 触发整批重跑. 现在 TwitterProcessor 不持有 TaskQueue.
     */
    @Test
    void shouldNotTouchTaskQueueAtPerArticleLevel() {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));

        processor.process();

        // 跟踪由 ContentScheduler 在 twitter:run 外层任务级别负责, Pipeline 不参与
        verify(contentPublisher, times(2)).publish(any());
    }

    /**
     * Patch-1 修复: per-article 失败时也不调 TaskQueue — failure++ 后 continue.
     */
    @Test
    void shouldNotTouchTaskQueueOnPerArticleFailure() {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class)))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "失败"));

        processor.process();

        // 无 taskQueue.push / complete 调用 — 跟踪在外层 twitter:run 任务级别
        verify(contentPublisher, never()).publish(any());
    }

    // ============ Patch-2 修复验证: faultIsolationEnabled 开关 ============

    /**
     * Patch-2 修复: faultIsolationEnabled=true (默认) 时 per-article 异常被吞, 继续下一条.
     */
    @Test
    void shouldSwallowPerArticleExceptionWhenFaultIsolationEnabled() {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        when(contentRewriter.rewrite(t1))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "改写失败"));
        when(contentRewriter.rewrite(t2)).thenReturn(article("art-2"));

        assertThat(properties.isFaultIsolationEnabled()).isTrue();
        processor.process();

        // 第 1 条失败被吞, 第 2 条仍被处理
        verify(contentPublisher, times(1)).publish(any());
    }

    /**
     * Patch-2 修复: faultIsolationEnabled=false 时 per-article 异常透传到 process() 顶层,
     * 由 ContentScheduler 接管分类 (Retryable 留 processing / NonRetryable complete).
     */
    @Test
    void shouldPropagatePerArticleExceptionWhenFaultIsolationDisabled() {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class)))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "改写失败"));

        properties.setFaultIsolationEnabled(false);

        // 调试模式: 异常透传到 process() 顶层抛出 (ContentScheduler 会捕获并按 Retryable 分类)
        assertThatThrownBy(() -> processor.process())
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("改写失败");
        // failure++ 仍计入 summary (log.error 已先记), 但不再 continue
        verify(contentPublisher, never()).publish(any());
    }

    @Test
    void shouldHandleEmptyTweetsFromFetch(CapturedOutput output) {
        when(twitterSource.fetch()).thenReturn(List.of());

        processor.process();

        assertThat(output.getOut()).contains(
                "Pipeline 完成: 发现=0, 评论筛选通过=0, 创新筛选通过=0, 改写成功=0, 归档成功=0, 失败=0");
        verify(contentRewriter, never()).rewrite(any(Tweet.class));
    }

    @Test
    void shouldHandleEmptyCommentPassed(CapturedOutput output) {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of());

        processor.process();

        // CommentFilter 全过滤掉, innovationFilter 仍被调用 (空 list), 但 rewrite 阶段循环 0 次
        assertThat(output.getOut()).contains("Pipeline 完成: 发现=1, 评论筛选通过=0");
        verify(contentRewriter, never()).rewrite(any(Tweet.class));
    }

    @Test
    void shouldLogSummaryWithCorrectCounts(CapturedOutput output) {
        Tweet t1 = tweet("id-1", "content-1");
        Tweet t2 = tweet("id-2", "content-2");
        Tweet t3 = tweet("id-3", "content-3");
        when(twitterSource.fetch()).thenReturn(List.of(t1, t2, t3));
        // CommentFilter 剔除 t3
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1, t2));
        // InnovationFilter 剔除 t2
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));

        processor.process();

        // 6 字段全在: discovered=3, commentPassed=2, innovationPassed=1, rewrite=1, archive=1, failure=0
        assertThat(output.getOut()).contains(
                "Pipeline 完成: 发现=3, 评论筛选通过=2, 创新筛选通过=1, 改写成功=1, 归档成功=1, 失败=0");
    }

    @Test
    void shouldNotIncludeContentInExceptionLog(CapturedOutput output) {
        // tweet.content 含敏感字符串 — 验证 Pipeline 只记 tweetId, 不记 content
        Tweet t1 = tweet("id-1", "敏感正文不应出现在日志");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        // 业务侧异常 message 是结构化错误码, 不携带正文 (N4 规范)
        when(contentRewriter.rewrite(any(Tweet.class)))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "LLM 调用失败"));

        processor.process();

        // N4 模式: log.error 输出不含 tweet.content (只记 tweetId + 截断 cause)
        assertThat(output.getOut()).doesNotContain("敏感正文不应出现在日志");
        assertThat(output.getOut()).contains("tweetId=id-1");
    }

    @Test
    void shouldTruncateCauseMessageViaCodePointForLongError() {
        // 复用 SingleModelRewriter.truncateForLog 验证 R3-1 修复: 返回值 ≤ max codepoint
        String longMsg = "异常正文".repeat(100); // 800 char
        String truncated = SingleModelRewriter.truncateForLog(longMsg, 200);
        assertThat(truncated.codePointCount(0, truncated.length())).isLessThanOrEqualTo(200);
        assertThat(truncated).endsWith("...");
    }

    // ============ Story 3.5 集成验证: PENDING + PROCESSING 写入 ============

    /**
     * Story 3.5 AC-1: Stage 3 入口 (rewrite 前) 调 markPending — 用确定性 ID `tw-{tweetId}`.
     */
    @Test
    void shouldMarkPendingBeforeRewriteLoop() {
        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));

        processor.process();

        // rewrite 前用 tw-{tweetId} 调 markPending
        verify(articleStatusService).markPending(eq("tw-id-1"));
    }

    @Test
    void shouldNotMarkProcessingInTwitterProcessorBeforePublish() {
        Tweet t1 = tweet("id-1", "content-1");
        Article a1 = article("art-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(t1)).thenReturn(a1);

        processor.process();

        // Story 3.5 Round 2: PROCESSING 只在真正微信 addDraft 前由 WeChatPublisher 写,
        // 避免批量入队文章被 TwitterProcessor 提前标为 PROCESSING.
        verify(articleStatusService, never()).markProcessing(any());
        verify(contentPublisher).publish(a1);
    }

    @Test
    void shouldNotCreatePendingStatusWhenTweetIdIsNull() {
        Tweet t1 = tweet(null, "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));

        processor.process();

        verify(articleStatusService, never()).markPending(any());
        verify(contentRewriter, never()).rewrite(any(Tweet.class));
        verify(contentPublisher, never()).publish(any());
    }

    /**
     * Story 3.5 §5.2 软失败验证: Redis 写 PENDING 抛 Retryable 被真实 ArticleStatusService 内部吞,
     * Pipeline 仍正常完成.
     */
    @Test
    void shouldNotPropagateStatusServiceSoftFailure(CapturedOutput output) {
        RedisRepository redisRepository = org.mockito.Mockito.mock(RedisRepository.class);
        ArticleStatusService realStatusService = new ArticleStatusService(redisRepository);
        processor = new TwitterProcessor(twitterSource, List.of(
                new CommentFilterStub(commentFilterDelegate),
                new InnovationFilterStub(innovationFilterDelegate)),
                contentRewriter, List.of(contentPublisher), properties, realStatusService);

        Tweet t1 = tweet("id-1", "content-1");
        when(twitterSource.fetch()).thenReturn(List.of(t1));
        when(commentFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(innovationFilterDelegate.filter(any())).thenReturn(List.of(t1));
        when(contentRewriter.rewrite(any(Tweet.class))).thenReturn(article("art-1"));
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"))
                .when(redisRepository).set(eq("article:tw-id-1:status"),
                        eq(ArticleStatus.PENDING.name()), eq(java.time.Duration.ofDays(30)));

        processor.process();

        // 仍调用了 publish, 软失败未阻塞流水线
        verify(contentPublisher, times(1)).publish(any());
        verify(redisRepository).set(eq("article:tw-id-1:status"),
                eq(ArticleStatus.PENDING.name()), eq(java.time.Duration.ofDays(30)));
        assertThat(output.getOut()).contains("状态写入失败, 跳过");
    }

    // ============ helpers ============

    private static Tweet tweet(String id, String content) {
        return Tweet.builder().id(id).content(content).build();
    }

    private static Article article(String title) {
        return Article.builder().id(title).title(title).content("body").build();
    }

    /**
     * 名字含 "Comment" 的具名 stub — 让 {@code getClass().getSimpleName()} 返回
     * "CommentFilterStub" 触发 TwitterProcessor 的 {@code filterName.contains("Comment")} 分支.
     */
    static final class CommentFilterStub implements ContentFilter<Tweet> {
        private final ContentFilter<Tweet> delegate;

        CommentFilterStub(ContentFilter<Tweet> delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Tweet> filter(List<Tweet> items) {
            return delegate.filter(items);
        }
    }

    /**
     * 名字含 "Innovation" 的具名 stub — 让 {@code getClass().getSimpleName()} 返回
     * "InnovationFilterStub" 触发 TwitterProcessor 的 {@code filterName.contains("Innovation")} 分支.
     */
    static final class InnovationFilterStub implements ContentFilter<Tweet> {
        private final ContentFilter<Tweet> delegate;

        InnovationFilterStub(ContentFilter<Tweet> delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Tweet> filter(List<Tweet> items) {
            return delegate.filter(items);
        }
    }
}
