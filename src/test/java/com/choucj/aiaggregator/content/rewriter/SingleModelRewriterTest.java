package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.content.rewriter.config.RewriterProperties;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 2.4 {@link SingleModelRewriter} 单测.
 *
 * <p>覆盖 AC-1,8-26,31-36 — 正常路径 / 边界 / 重试 / 异常 / Article 字段填充 /
 * Markdown 解析 / prompt 安全 (B2 %/N2 emoji) / 日志 (W11 INFO/WARN/ERROR).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SingleModelRewriterTest {

    private static final String LLM_RESPONSE = """
            # DeepSeek 革命性提升推理速度

            最近 DeepSeek 发布的新模型在推理任务上达到 SOTA 表现.

            ## 关键创新

            - MoE 架构优化
            - 训练效率提升 30%

            > 摘要: DeepSeek 推出新模型, 推理速度大幅提升.
            """;

    @Mock
    private LlmClient llmClient;

    @Mock
    private TokenUsageTracker tokenUsageTracker;

    private RewriterProperties properties;

    private SingleModelRewriter rewriter;

    @BeforeEach
    void setUp() {
        properties = new RewriterProperties();
        properties.setMaxRetries(3);
        properties.setRetryBackoffMs(100L); // 测试用 100ms 加速
        properties.setContentMaxCodePoints(2000);
        rewriter = new SingleModelRewriter(llmClient, properties, tokenUsageTracker);
    }

    // T5.1: 正常路径用例

    @Test
    void shouldRewriteTweetSuccessfullyAndFillAllFields() {
        Tweet tweet = Tweet.builder()
                .id("12345")
                .author("elonmusk")
                .content("Just shipped something amazing")
                .url("https://twitter.com/elonmusk/status/12345")
                .innovationScore(8.5)
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article.getId()).isEqualTo("tw-12345");
        assertThat(article.getTitle()).isEqualTo("DeepSeek 革命性提升推理速度");
        assertThat(article.getContent()).contains("MoE 架构优化");
        assertThat(article.getDigest()).isEqualTo("DeepSeek 推出新模型, 推理速度大幅提升.");
        assertThat(article.getSource()).isEqualTo("来源:@elonmusk");
        assertThat(article.isAiGenerated()).isTrue();
        assertThat(article.getInnovationScore()).isEqualTo(8);
        assertThat(article.getOriginalUrl()).isEqualTo("https://twitter.com/elonmusk/status/12345");
        assertThat(article.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldSetAiGeneratedFlagTrue() {
        Tweet tweet = sampleTweet("123", "hello");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article.isAiGenerated()).isTrue();
    }

    @Test
    void shouldGenerateDeterministicArticleIdFromTweetId() {
        Tweet tweet = sampleTweet("same-tweet-id", "hello");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article first = rewriter.rewrite(tweet);
        com.choucj.aiaggregator.common.model.Article second = rewriter.rewrite(tweet);

        assertThat(first.getId()).isEqualTo("tw-same-tweet-id");
        assertThat(second.getId()).isEqualTo("tw-same-tweet-id");
    }

    // T5.2: 边界用例 (content null fallback summary / 都 null 兜底空串)

    @Test
    void shouldUseSummaryWhenContentIsNull() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("user")
                .summary("summary text")
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        rewriter.rewrite(tweet);

        verify(llmClient).chat(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("summary text");
    }

    @Test
    void shouldUseEmptyStringWhenContentAndSummaryBothNull() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("user")
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        rewriter.rewrite(tweet);

        verify(llmClient).chat(anyString(), promptCaptor.capture());
        // 空字符串仍然替换 {{CONTENT}}, 标签内为空
        assertThat(promptCaptor.getValue()).contains("<content>");
        assertThat(promptCaptor.getValue()).doesNotContain("{{CONTENT}}");
    }

    // T5.3: 重试用例

    @Test
    void shouldRetryAndSucceedOnTransientFailure() {
        Tweet tweet = sampleTweet("123", "hello");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RetryableException("transient"))
                .thenThrow(new RetryableException("transient"))
                .thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article).isNotNull();
        verify(llmClient, times(3)).chat(anyString(), anyString());
    }

    @Test
    void shouldThrowRetryableAfterMaxRetries() {
        Tweet tweet = sampleTweet("123", "hello");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RetryableException("perm fail"));

        assertThatThrownBy(() -> rewriter.rewrite(tweet))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("tweetId=123")
                .hasMessageContaining("4 次尝试均失败");

        // 1 初始 + 3 重试 = 4 次
        verify(llmClient, times(4)).chat(anyString(), anyString());
    }

    @Test
    void shouldNotRetryOnNonRetryableException() {
        // Patch-2: NonRetryableException 表永久错误 (配置错 / 凭证失效), 立即抛出不重试,
        // 保持项目异常分类语义 (review finding: 重试 NonRetryable 破坏分类).
        Tweet tweet = sampleTweet("perm-123", "hello");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new NonRetryableException("permanent config error"));

        assertThatThrownBy(() -> rewriter.rewrite(tweet))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("permanent config error");

        // 只调一次 — 没有重试 (与 RetryableException 的 4 次对比)
        verify(llmClient, times(1)).chat(anyString(), anyString());
    }

    // T5.4: 异常用例

    @Test
    void shouldThrowNonRetryableWhenTweetIsNull() {
        // Patch-10 (Round 3 review): 改抛 NonRetryableException 而非 NPE, 调试模式下被 processQueueOnce
        // 正确分类为 NonRetryable → complete(taskId) 移除避免阻塞整批 (AC-3).
        assertThatThrownBy(() -> rewriter.rewrite((Tweet) null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("tweet 或 tweet.id 为 null");
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldThrowNonRetryableWhenTweetIdIsNull() {
        // Patch-10: 同上, NonRetryableException 替代 NPE 让调试模式分类正确.
        Tweet tweet = Tweet.builder().author("a").build();
        assertThatThrownBy(() -> rewriter.rewrite(tweet))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("tweet 或 tweet.id 为 null");
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldWrapInterruptedExceptionAsRetryable() throws InterruptedException {
        // Patch-R2-1: 本测试方法内单独覆盖 setUp 的 retryBackoffMs=100L 调到 1000ms,
        // 给 interrupt() (50ms 后触发) 必然落在首次 Thread.sleep(1000ms) 窗口内的足够余量,
        // 消除原 100ms 退避下 "mock 抛错耗时 + log.warn 耗时可能 > 50ms 时点" 的微秒级非确定窗口.
        // 其他测试用例仍用 setUp 的 100ms 默认值保持快速运行.
        properties.setRetryBackoffMs(1000L);
        Tweet tweet = sampleTweet("interrupt-123", "hello");
        when(llmClient.chat(anyString(), anyString())).thenThrow(new RetryableException("fail"));

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread testThread = new Thread(() -> {
            try {
                rewriter.rewrite(tweet);
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        testThread.start();
        // 等到第一次重试退避 (1000ms) 窗口内, 中断线程 (Patch-R2-1: 扩大退避余量消除 50ms 时序竞争)
        Thread.sleep(50);
        testThread.interrupt();
        testThread.join(2000);

        // Patch-1: 用 AtomicReference 捕获异常验证 InterruptedException 被包装为 RetryableException
        // (旧版 `assertThat(testThread.isInterrupted() || true).isTrue()` 是重言式, 零覆盖率)
        assertThat(caught.get())
                .as("中断后应抛 RetryableException, 实际捕获: %s", caught.get())
                .isNotNull()
                .isInstanceOf(RetryableException.class);
        assertThat(caught.get().getMessage()).contains("中断");
        assertThat(caught.get().getCause()).isInstanceOf(InterruptedException.class);
    }

    // T5.5: Article 字段用例

    @Test
    void shouldFillSourceWithNormalizedAuthorWithAtPrefix() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("@elonmusk")
                .content("hello")
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article.getSource()).isEqualTo("来源:@elonmusk");
    }

    @Test
    void shouldFillSourceWithUnknownWhenAuthorIsNull() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author(null)
                .content("hello")
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article.getSource()).isEqualTo("来源:@unknown");
    }

    @Test
    void shouldConvertInnovationScoreSafelyWhenNonNull() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("user")
                .content("hello")
                .innovationScore(8.7)
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        // Double 8.7 intValue() = 8
        assertThat(article.getInnovationScore()).isEqualTo(8);
    }

    @Test
    void shouldDefaultInnovationScoreToZeroWhenNull() {
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("user")
                .content("hello")
                .innovationScore(null)  // D3 关键测试
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        // D3: 不抛 NPE, 默认 0
        assertThat(article.getInnovationScore()).isEqualTo(0);
    }

    // T5.6: Markdown 解析用例

    @Test
    void shouldParseTitleFromMarkdownHeading() {
        assertThat(SingleModelRewriter.parseTitle("# Hello World\nbody"))
                .isEqualTo("Hello World");
    }

    @Test
    void shouldFallbackTitleWhenNoHeading() {
        assertThat(SingleModelRewriter.parseTitle("no heading here"))
                .isEqualTo("未命名文章");
    }

    @Test
    void shouldReturnDefaultTitleForNullOrBlank() {
        assertThat(SingleModelRewriter.parseTitle(null)).isEqualTo("未命名文章");
        assertThat(SingleModelRewriter.parseTitle("  ")).isEqualTo("未命名文章");
    }

    @Test
    void shouldParseDigestFromSummaryLine() {
        String resp = "# T\nbody\n> 摘要: 这是摘要";
        assertThat(SingleModelRewriter.parseDigest(resp)).isEqualTo("这是摘要");
    }

    @Test
    void shouldFallbackDigestFromContentWhenNoSummaryLine() {
        // content = 去掉标题行和摘要行后剩 "body 文本..." 但这里没有摘要行
        String longBody = "a".repeat(200);
        String resp = "# Title\n" + longBody;
        String digest = SingleModelRewriter.parseDigest(resp);
        assertThat(digest).hasSize(120);
    }

    @Test
    void shouldParseContentStrippingTitleAndSummary() {
        String resp = "# Title\n\nmain body line 1\n\nmain body line 2\n> 摘要: summary";
        String content = SingleModelRewriter.parseContent(resp);
        assertThat(content).contains("main body line 1", "main body line 2");
        assertThat(content).doesNotContain("# Title");
        assertThat(content).doesNotContain("> 摘要");
    }

    // T5.7: prompt 安全用例 (B2 % / N2 emoji)

    @Test
    void shouldHandlePercentSignInContentGracefully() {
        Tweet tweet = sampleTweet("123", "100% completion rate 50% done");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        // B2 模式: String.replace 占位符, content 含 % 不抛 IllegalFormatException
        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);

        assertThat(article).isNotNull();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("100% completion rate 50% done");
    }

    @Test
    void shouldTruncateContentByCodePointsWithoutSplittingSurrogatePairs() {
        // emoji 🎉 是 2 char (UTF-16 代理对), 1 code point. 截断到 1 code point 不应切在中间.
        String emoji = "🎉"; // U+1F389, 2 chars, 1 code point
        String content = "abc" + emoji + "def"; // 7 chars, 7 code points
        String truncated = SingleModelRewriter.truncateByCodePoints(content, 4);
        // 截到 4 code points = "abc" + "🎉"
        assertThat(truncated).isEqualTo("abc" + emoji);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(4);
    }

    @Test
    void shouldTruncateLongContentToMaxCodePoints() {
        properties.setContentMaxCodePoints(10);
        String longContent = "abcdefghijklmnopqrstuvwxyz"; // 26 chars / code points
        String truncated = SingleModelRewriter.truncateByCodePoints(longContent, 10);
        assertThat(truncated).hasSize(10);
        assertThat(truncated).isEqualTo("abcdefghij");
    }

    @Test
    void shouldNotTruncateShortContent() {
        String shortContent = "abc";
        assertThat(SingleModelRewriter.truncateByCodePoints(shortContent, 2000))
                .isEqualTo("abc");
    }

    @Test
    void shouldReturnEmptyStringWhenContentIsNull() {
        assertThat(SingleModelRewriter.truncateByCodePoints(null, 2000)).isEmpty();
    }

    // T5.8: 日志断言用例 (W11 INFO / WARN / ERROR)

    @Test
    void shouldLogInfoOnSuccess(CapturedOutput output) {
        Tweet tweet = sampleTweet("log-info-123", "hello content");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        assertThat(output.getOut())
                .contains("改写成功")
                .contains("tweetId=log-info-123")
                .contains("DeepSeek 革命性提升推理速度");
    }

    @Test
    void shouldLogWarnOnRetry(CapturedOutput output) {
        Tweet tweet = sampleTweet("log-warn-123", "hello");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RetryableException("transient"))
                .thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        assertThat(output.getOut())
                .contains("改写失败")
                .contains("第 1 次重试")
                .contains("tweetId=log-warn-123");
    }

    @Test
    void shouldLogErrorOnFinalFailure(CapturedOutput output) {
        Tweet tweet = sampleTweet("log-error-123", "hello");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RetryableException("perm fail"));

        assertThatThrownBy(() -> rewriter.rewrite(tweet))
                .isInstanceOf(RetryableException.class);

        assertThat(output.getOut())
                .contains("改写最终失败")
                .contains("tweetId=log-error-123");
    }

    @Test
    void shouldLogWarnWhenTitleFallbackUsed(CapturedOutput output) {
        // Patch-4: LLM 响应首行非 '# 标题' 格式时, buildArticle 通过 FALLBACK_TITLE 常量
        // 识别并 log.warn, 便于运维定位 LLM 输出格式漂移.
        Tweet tweet = sampleTweet("no-title-123", "hello content");
        String malformedResponse = "no heading here\nbody text"; // 首行非 '# '
        when(llmClient.chat(anyString(), anyString())).thenReturn(malformedResponse);

        rewriter.rewrite(tweet);

        assertThat(output.getOut())
                .contains("LLM 响应未识别到 '# 标题' 格式")
                .contains("tweetId=no-title-123");
    }

    // Round 2 patch 回归测试 (2026-06-28 第三轮补丁修复)

    @Test
    void shouldTruncateExplicitDigestSummaryToMaxLength() {
        // Patch-R2-3: 显式 "> 摘要:" 路径也要截断, 防 LLM 输出超长摘要破坏 Article.digest
        // 120 字符契约 (DB schema 对应). 旧实现直接 return trimmed.substring(...).trim() 不截断.
        String longSummary = "a".repeat(200);
        String resp = "# Title\nbody\n> 摘要: " + longSummary;
        String digest = SingleModelRewriter.parseDigest(resp);
        assertThat(digest).hasSize(120);
        assertThat(digest.codePointCount(0, digest.length())).isEqualTo(120);
    }

    @Test
    void shouldTruncateExplicitDigestSummaryByCodePointsWithoutSplittingSurrogatePairs() {
        // Patch-R2-3 + N2: 摘要含 emoji, 截断到 120 codepoints 不应切断代理对.
        String emoji = "🎉"; // 2 chars, 1 code point
        // 构造 119 ASCII + 1 emoji = 120 codepoints / 121 chars; 截到 120 codepoints 应保留 emoji 完整.
        String summary = "x".repeat(119) + emoji;
        String resp = "# Title\n> 摘要: " + summary;
        String digest = SingleModelRewriter.parseDigest(resp);
        assertThat(digest.codePointCount(0, digest.length())).isEqualTo(120);
        assertThat(digest).endsWith(emoji);
    }

    @Test
    void shouldTruncateForLogByCodePointsWithoutSplittingSurrogatePairs() {
        // Patch-R2-2: truncateForLog 改用 codepoint 截断, 与 truncateByCodePoints (N2) 一致,
        // 不再在 LOG_TITLE_MAX_LENGTH=50 / LOG_MSG_MAX_LENGTH=200 边界切断代理对产生乱码流入日志.
        String emoji = "🎉"; // 2 chars, 1 code point
        // 49 ASCII + 1 emoji = 50 codepoints / 51 chars; 截到 50 codepoints 应保留 emoji 完整.
        String title = "a".repeat(49) + emoji;
        String truncated = SingleModelRewriter.truncateForLog(title, 50);
        assertThat(truncated).isEqualTo(title);
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(50);
    }

    @Test
    void shouldTruncateForLogAtCodePointBoundaryAndAppendEllipsis() {
        // Patch-R3-1: 超长内容截断 + "..." 总 codepoints ≤ max (修复长度契约回归).
        // Patch-R2-2 旧实现返回 max + 3 = 53 codepoints, 破坏调用方 LOG_MSG_MAX_LENGTH=200 上限契约.
        String emoji = "🎉";
        // 50 ASCII + 1 emoji = 51 codepoints; max=50, 截到 (50-3)=47 ASCII + "..." = 50 codepoints.
        String content = "b".repeat(50) + emoji;
        String truncated = SingleModelRewriter.truncateForLog(content, 50);
        assertThat(truncated).isEqualTo("b".repeat(47) + "...");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(50);
    }

    // 静态方法测试 - normalizeAuthor

    @Test
    void shouldNormalizeAuthorStripAtPrefix() {
        assertThat(SingleModelRewriter.normalizeAuthor("@user")).isEqualTo("user");
        assertThat(SingleModelRewriter.normalizeAuthor("user")).isEqualTo("user");
        assertThat(SingleModelRewriter.normalizeAuthor(null)).isEqualTo("unknown");
        assertThat(SingleModelRewriter.normalizeAuthor("")).isEqualTo("unknown");
        assertThat(SingleModelRewriter.normalizeAuthor("  ")).isEqualTo("unknown");
    }

    // TokenUsageTracker 集成测试

    @Test
    void shouldInvokeTokenUsageTrackerAfterSuccess() {
        Tweet tweet = sampleTweet("track-123", "hello content here");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        verify(tokenUsageTracker, times(1))
                .track(anyString(), eq(LLM_RESPONSE), any(java.time.LocalDate.class));
    }

    @Test
    void shouldContinueRewriteWhenTokenTrackingThrows() {
        // Patch-6: 修正注释矛盾.
        // AC-29: Token 追踪是观测侧路径, 失败不应中断改写主流程.
        // SingleModelRewriter.rewrite 用 try/catch (RuntimeException) 包裹 tracker.track 调用,
        // 即使 mock 抛出 RuntimeException (绕过真实 TokenUsageTracker 的内部 catch),
        // SingleModelRewriter 仍 log.warn 后继续返回 Article — 双重防护.
        Tweet tweet = sampleTweet("track-fail", "hello content");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(tokenUsageTracker)
                .track(anyString(), anyString(), any(java.time.LocalDate.class));

        // 不抛 — SingleModelRewriter.rewrite 内部 catch (RuntimeException) 后 log.warn, 主流程返回 Article
        com.choucj.aiaggregator.common.model.Article article = rewriter.rewrite(tweet);
        assertThat(article).isNotNull();
    }

    // 测试辅助

    private Tweet sampleTweet(String id, String content) {
        return Tweet.builder()
                .id(id)
                .author("sampleuser")
                .content(content)
                .url("https://twitter.com/sample/status/" + id)
                .innovationScore(7.0)
                .build();
    }
}
