package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.config.RewriterProperties;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.4 — MultiModelRewriter 并行、降级和选优测试.
 */
@ExtendWith(MockitoExtension.class)
class MultiModelRewriterTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private TokenUsageTracker tokenUsageTracker;

    @Mock
    private ModelScorer modelScorer;

    private MultiModelRewriter rewriter;

    @BeforeEach
    void setUp() {
        RewriterProperties properties = new RewriterProperties();
        properties.setMaxRetries(0);
        properties.setRetryBackoffMs(100L);
        properties.setContentMaxCodePoints(2000);
        rewriter = new MultiModelRewriter(llmClient, properties, tokenUsageTracker, Optional.empty(), modelScorer);
    }

    @Test
    void shouldCallDeepSeekAndGlmWithSamePromptAndReturnHigherScoredCandidate() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenReturn(response("DeepSeek 标题", "DeepSeek 正文"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM 标题", "GLM 正文"));
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class),
                org.mockito.ArgumentMatchers.eq("deepseek")))
                .thenReturn(score("deepseek", 70));
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class),
                org.mockito.ArgumentMatchers.eq("glm")))
                .thenReturn(score("glm", 85));

        Article article = rewriter.rewrite(sampleTweet());

        assertThat(article.getTitle()).isEqualTo("GLM 标题");
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"),
                systemCaptor.capture(), userCaptor.capture());
        verify(llmClient).chatWithModel(org.mockito.ArgumentMatchers.eq("glm"),
                systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getAllValues().get(0)).isEqualTo(systemCaptor.getAllValues().get(1));
        assertThat(userCaptor.getAllValues().get(0)).isEqualTo(userCaptor.getAllValues().get(1));
        verify(tokenUsageTracker).track(org.mockito.ArgumentMatchers.eq("deepseek"),
                anyString(), anyString(), any(LocalDate.class));
        verify(tokenUsageTracker).track(org.mockito.ArgumentMatchers.eq("glm"),
                anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void shouldPickDeepSeekWhenScoresTie() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenReturn(response("DeepSeek 标题", "DeepSeek 正文"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM 标题", "GLM 正文"));
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class), anyString()))
                .thenReturn(score("any", 80));

        Article article = rewriter.rewrite(sampleTweet());

        assertThat(article.getTitle()).isEqualTo("DeepSeek 标题");
    }

    @Test
    void shouldReturnSuccessfulCandidateWithoutScoringWhenOtherModelFails() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenThrow(new RetryableException("deepseek down"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM 标题", "GLM 正文"));

        Article article = rewriter.rewrite(sampleTweet());

        assertThat(article.getTitle()).isEqualTo("GLM 标题");
        verify(modelScorer, never()).score(any(), any(), anyString());
        verify(tokenUsageTracker, times(1)).track(org.mockito.ArgumentMatchers.eq("glm"),
                anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void shouldReturnSuccessfulCandidateWhenOtherModelFailsWithUnexpectedRuntimeException() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenThrow(new IllegalStateException("SECRET_RUNTIME_FAILURE"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM 标题", "GLM 正文"));

        Article article = rewriter.rewrite(sampleTweet());

        assertThat(article.getTitle()).isEqualTo("GLM 标题");
        verify(modelScorer, never()).score(any(), any(), anyString());
    }

    @Test
    void shouldUseCompletedFallbackEvenWhenFirstAwaitExhaustsDeadline() throws Exception {
        RewriterProperties deadlineProperties = new RewriterProperties();
        deadlineProperties.setMaxRetries(0);
        deadlineProperties.setRetryBackoffMs(100L);
        deadlineProperties.setContentMaxCodePoints(2000);
        deadlineProperties.setMultiModelDeadlineMs(150L);
        rewriter = new MultiModelRewriter(llmClient, deadlineProperties, tokenUsageTracker, Optional.empty(), modelScorer);
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(300L);
                    return response("DeepSeek late", "正文");
                });
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM fallback", "正文"));

        Article article = rewriter.rewrite(sampleTweet());

        assertThat(article.getTitle()).isEqualTo("GLM fallback");
        verify(tokenUsageTracker).track(org.mockito.ArgumentMatchers.eq("glm"),
                anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void shouldThrowRetryableAfterBothModelsCompleteAndFail() {
        when(llmClient.chatWithModel(anyString(), anyString(), anyString()))
                .thenThrow(new RetryableException("model down"));

        assertThatThrownBy(() -> rewriter.rewrite(sampleTweet()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("多模型改写失败");
    }

    @Test
    void shouldSanitizeExceptionChainWhenBothModelsFailWithSensitiveMessages() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenThrow(new RetryableException("SECRET_DEEPSEEK_PROMPT"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenThrow(new RetryableException("SECRET_GLM_RESPONSE"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> rewriter.rewrite(sampleTweet()));

        assertThat(thrown)
                .isInstanceOf(RetryableException.class)
                .hasMessageNotContaining("SECRET_DEEPSEEK_PROMPT")
                .hasMessageNotContaining("SECRET_GLM_RESPONSE");
        assertThat(thrown.getCause())
                .hasMessageNotContaining("SECRET_DEEPSEEK_PROMPT")
                .hasMessageNotContaining("SECRET_GLM_RESPONSE")
                .hasNoCause();
        assertThat(thrown.getSuppressed())
                .singleElement()
                .satisfies(suppressed -> assertThat(suppressed)
                        .hasMessageNotContaining("SECRET_DEEPSEEK_PROMPT")
                        .hasMessageNotContaining("SECRET_GLM_RESPONSE")
                        .hasNoCause());
    }

    @Test
    void shouldThrowRetryableWhenModelsDoNotCompleteWithinRequestDeadline() throws Exception {
        RewriterProperties deadlineProperties = new RewriterProperties();
        deadlineProperties.setMaxRetries(0);
        deadlineProperties.setRetryBackoffMs(100L);
        deadlineProperties.setContentMaxCodePoints(2000);
        deadlineProperties.setMultiModelDeadlineMs(500L);
        rewriter = new MultiModelRewriter(llmClient, deadlineProperties, tokenUsageTracker, Optional.empty(), modelScorer);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        when(llmClient.chatWithModel(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new RetryableException("interrupted", e);
                    }
                    return response("late", "late");
                });

        assertThatThrownBy(() -> rewriter.rewrite(sampleTweet()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("多模型改写失败")
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldNotParseOrTrackWhenCancelledModelCallReturnsAfterInterrupt() throws Exception {
        RewriterProperties deadlineProperties = new RewriterProperties();
        deadlineProperties.setMaxRetries(0);
        deadlineProperties.setRetryBackoffMs(100L);
        deadlineProperties.setContentMaxCodePoints(2000);
        deadlineProperties.setMultiModelDeadlineMs(300L);
        rewriter = new MultiModelRewriter(llmClient, deadlineProperties, tokenUsageTracker, Optional.empty(), modelScorer);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        when(llmClient.chatWithModel(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                    } catch (InterruptedException ignored) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        return response("late", "late");
                    }
                    return response("unexpected", "unexpected");
                });

        assertThatThrownBy(() -> rewriter.rewrite(sampleTweet()))
                .isInstanceOf(RetryableException.class);

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(tokenUsageTracker, never()).track(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void shouldKeepScoringWithinRequestDeadline() throws Exception {
        RewriterProperties deadlineProperties = new RewriterProperties();
        deadlineProperties.setMaxRetries(0);
        deadlineProperties.setRetryBackoffMs(100L);
        deadlineProperties.setContentMaxCodePoints(2000);
        deadlineProperties.setMultiModelDeadlineMs(300L);
        rewriter = new MultiModelRewriter(llmClient, deadlineProperties, tokenUsageTracker, Optional.empty(), modelScorer);
        CountDownLatch scorerStarted = new CountDownLatch(1);
        CountDownLatch scorerInterrupted = new CountDownLatch(1);
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenReturn(response("DeepSeek 标题", "DeepSeek 正文"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM 标题", "GLM 正文"));
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class), anyString()))
                .thenAnswer(invocation -> {
                    scorerStarted.countDown();
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                    } catch (InterruptedException e) {
                        scorerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return score(invocation.getArgument(2), 90);
                });

        long start = System.currentTimeMillis();
        Article article = rewriter.rewrite(sampleTweet());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(article.getTitle()).isEqualTo("DeepSeek 标题");
        assertThat(scorerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(scorerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsed).isLessThan(1500L);
    }

    @Test
    void shouldStartBothModelCallsBeforeWaitingForResult() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(llmClient.chatWithModel(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    bothStarted.countDown();
                    assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
                    release.await(1, TimeUnit.SECONDS);
                    return response(invocation.getArgument(0) + " 标题", "正文");
                });
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class), anyString()))
                .thenReturn(score("any", 80));

        Article article = rewriter.rewrite(sampleTweet());
        release.countDown();

        assertThat(article.getTitle()).contains("deepseek");
    }

    @Test
    void shouldRewriteGitHubRepoThroughBothModels() {
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                .thenReturn(response("DeepSeek GitHub", "正文"));
        when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                .thenReturn(response("GLM GitHub", "正文"));
        when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class), anyString()))
                .thenReturn(score("any", 80));

        Article article = rewriter.rewrite(sampleRepo());

        assertThat(article.getId()).isEqualTo("gh-octocat-Hello-World");
        assertThat(article.getTitle()).isEqualTo("DeepSeek GitHub");
    }

    @Test
    void shouldLogModelStatusAndWinnerWithoutSourcePromptOrResponseText() {
        Logger logger = (Logger) LoggerFactory.getLogger(MultiModelRewriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Tweet tweet = Tweet.builder()
                    .id("99")
                    .author("sample")
                    .content("SECRET_SOURCE_TEXT Java virtual threads")
                    .url("https://twitter.com/sample/status/99")
                    .innovationScore(8.0)
                    .build();
            when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                    .thenReturn(response("DeepSeek 标题", "SECRET_RESPONSE_BODY deepseek"));
            when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                    .thenReturn(response("GLM 标题", "SECRET_RESPONSE_BODY glm"));
            when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class),
                    org.mockito.ArgumentMatchers.eq("deepseek")))
                    .thenReturn(score("deepseek", 90));
            when(modelScorer.score(any(Article.class), any(ModelScorer.SourceContext.class),
                    org.mockito.ArgumentMatchers.eq("glm")))
                    .thenReturn(score("glm", 70));

            rewriter.rewrite(tweet);

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs)
                    .contains("model=deepseek", "model=glm", "winner=deepseek", "deepseekScore=90", "glmScore=70",
                            "deepseekDims=coherence=20,accuracy=20,readability=20,humanTone=20",
                            "glmDims=coherence=20,accuracy=20,readability=20,humanTone=20", "elapsedMs=")
                    .doesNotContain("SECRET_SOURCE_TEXT", "SECRET_RESPONSE_BODY", SingleModelRewriter.SYSTEM_PROMPT);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotLogRetryableRootMessageFromCandidateFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(MultiModelRewriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("deepseek"), anyString(), anyString()))
                    .thenThrow(new RetryableException("SECRET_ROOT_MESSAGE prompt=response"));
            when(llmClient.chatWithModel(org.mockito.ArgumentMatchers.eq("glm"), anyString(), anyString()))
                    .thenReturn(response("GLM 标题", "GLM 正文"));

            Article article = rewriter.rewrite(sampleTweet());

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(article.getTitle()).isEqualTo("GLM 标题");
            assertThat(logs)
                    .contains("model=deepseek", "errorType=RetryableException")
                    .doesNotContain("SECRET_ROOT_MESSAGE", "prompt=response");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static Tweet sampleTweet() {
        return Tweet.builder()
                .id("42")
                .author("sample")
                .content("Java 21 virtual threads make servers simpler")
                .url("https://twitter.com/sample/status/42")
                .innovationScore(8.0)
                .build();
    }

    private static GitHubRepo sampleRepo() {
        return GitHubRepo.builder()
                .id("1")
                .fullName("octocat/Hello-World")
                .description("Useful Java project")
                .language("Java")
                .stars(100)
                .forks(10)
                .readmeContent("# Hello\nREADME")
                .valueScore(8.0)
                .valueSummary("有价值")
                .url("https://github.com/octocat/Hello-World")
                .build();
    }

    private static String response(String title, String body) {
        return "# " + title + "\n\n" + body + "\n\n> 摘要: 摘要";
    }

    private static ModelScorer.ScoreResult score(String model, int total) {
        return new ModelScorer.ScoreResult(model, total, 20, 20, 20, 20, java.util.List.of("OK"));
    }
}
