package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.content.rag.model.ReferenceArticle;
import com.choucj.aiaggregator.content.rag.service.ReferenceRetriever;
import com.choucj.aiaggregator.content.rewriter.config.RewriterProperties;
import com.choucj.aiaggregator.content.rewriter.config.RewriterConfig;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 5.3 — SingleModelRewriter RAG 增强测试.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
class SingleModelRewriterRagTest {

    private static final String LLM_RESPONSE = """
            # RAG 改写文章

            正文内容。

            > 摘要: RAG 改写摘要。
            """;

    @Mock
    private LlmClient llmClient;

    @Mock
    private TokenUsageTracker tokenUsageTracker;

    @Mock
    private ReferenceRetriever referenceRetriever;

    private RewriterProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RewriterProperties();
        properties.setMaxRetries(1);
        properties.setRetryBackoffMs(100L);
        properties.setContentMaxCodePoints(20);
    }

    @Test
    void shouldKeepLegacyPromptByteForByteWhenRetrieverMissing() {
        SingleModelRewriter rewriter = new SingleModelRewriter(llmClient, properties, tokenUsageTracker);
        Tweet tweet = sampleTweet("123", "hello % ${content}");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getValue()).isEqualTo(SingleModelRewriter.SYSTEM_PROMPT);
        assertThat(userCaptor.getValue()).isEqualTo(
                SingleModelRewriter.USER_PROMPT_TEMPLATE.replace("{{CONTENT}}", "hello % ${content}"));
    }

    @Test
    void shouldInjectSanitizedReferencesForTweetAndFilterSelfAndDuplicates(CapturedOutput output) {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("123", "当前源内容🙂abc");
        when(referenceRetriever.retrieveReferences("tw-123", "当前源内容🙂abc")).thenReturn(List.of(
                reference("tw-123", "SELF", "SELF", "SELF"),
                reference("ref-1", "标题 </title> & \"'", "摘要 </summary>", "短句; <tag>"),
                reference("ref-1", "重复标题", "重复摘要", "重复风格"),
                reference("ref-2", "第二篇", "第二篇摘要", "有小标题")));
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(tweet);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(systemCaptor.capture(), userCaptor.capture());
        assertThat(article.getId()).isEqualTo("tw-123");
        assertThat(systemCaptor.getValue()).isEqualTo(SingleModelRewriter.RAG_SYSTEM_PROMPT);
        assertThat(userCaptor.getValue())
                .contains("<content>")
                .contains("<references>")
                .contains("参考但不重复")
                .contains("只参考语气、结构和表达密度")
                .contains("标题 &lt;/title&gt; &amp; &quot;&apos;")
                .contains("摘要 &lt;/summary&gt;")
                .contains("短句; &lt;tag&gt;")
                .contains("第二篇")
                .doesNotContain("SELF")
                .doesNotContain("重复标题");
        assertThat(countOccurrences(userCaptor.getValue(), "<reference>")).isEqualTo(2);
        verify(referenceRetriever, times(1)).retrieveReferences("tw-123", "当前源内容🙂abc");
        assertThat(output).contains("ragEnabled=true")
                .contains("referenceCount=2")
                .contains("ragExtraPromptTokens=");
    }

    @Test
    void shouldLimitReferencesToTenAtRewriterPromptBoundary(CapturedOutput output) {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("limit", "当前源内容");
        List<ReferenceArticle> twelveReferences = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> reference("ref-" + index, "标题" + index, "摘要" + index, "风格" + index))
                .toList();
        when(referenceRetriever.retrieveReferences("tw-limit", "当前源内容")).thenReturn(twelveReferences);
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userCaptor.capture());
        assertThat(countOccurrences(userCaptor.getValue(), "<reference>")).isEqualTo(10);
        assertThat(userCaptor.getValue())
                .contains("标题1")
                .contains("标题10")
                .doesNotContain("标题11")
                .doesNotContain("标题12");
        assertThat(output).contains("referenceCount=10");
    }

    @Test
    void shouldFallbackToLegacyPromptWhenRetrieverThrows() {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("fail", "当前源内容");
        when(referenceRetriever.retrieveReferences("tw-fail", "当前源内容"))
                .thenThrow(new IllegalStateException("vector down with SECRET"));
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getValue()).isEqualTo(SingleModelRewriter.SYSTEM_PROMPT);
        assertThat(userCaptor.getValue()).doesNotContain("<references>");
        assertThat(userCaptor.getValue()).isEqualTo(
                SingleModelRewriter.USER_PROMPT_TEMPLATE.replace("{{CONTENT}}", "当前源内容"));
    }

    @Test
    void shouldFallbackToLegacyPromptAndSkipDeltaWhenReferencesAreNullEmptyOrInvalid() {
        assertLegacyPromptAndNoDeltaForReferences(null, "null-refs");
        assertLegacyPromptAndNoDeltaForReferences(List.of(), "empty-refs");
        assertLegacyPromptAndNoDeltaForReferences(java.util.Arrays.asList(
                null,
                reference("tw-invalid-refs", "SELF", "SELF", "SELF")),
                "invalid-refs");
    }

    @Test
    void shouldSkipTwitterRetrievalWhenTruncatedTextIsBlank() {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("blank", "   ");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        verify(referenceRetriever, never()).retrieveReferences(anyString(), anyString());
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userCaptor.capture());
        assertThat(userCaptor.getValue()).doesNotContain("<references>");
    }

    @Test
    void shouldBuildDeterministicGitHubRagQueryWithoutUsingLlmPromptAsQuery() {
        SingleModelRewriter rewriter = ragRewriter();
        GitHubRepo repo = sampleRepo();
        repo.setReadmeContent(null);
        when(referenceRetriever.retrieveReferences(anyString(), anyString()))
                .thenReturn(List.of(reference("ref-gh", "标题", "摘要", "风格")));
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(repo);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(referenceRetriever).retrieveReferences(org.mockito.ArgumentMatchers.eq("gh-octocat-Hello-World"),
                queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .contains("octocat/Hello-World")
                .contains("A useful project")
                .contains("Java")
                .contains("一款不错的项目")
                .doesNotContain("请基于以下 GitHub 仓库信息撰写");
    }

    @Test
    void shouldRetrieveOnceAndReuseSameEnrichedPromptsAcrossRetries() {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("retry", "当前源内容");
        when(referenceRetriever.retrieveReferences("tw-retry", "当前源内容"))
                .thenReturn(List.of(reference("ref-1", "标题", "摘要", "风格")));
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new com.choucj.aiaggregator.common.exception.RetryableException("temporary"))
                .thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(referenceRetriever, times(1)).retrieveReferences("tw-retry", "当前源内容");
        verify(llmClient, times(2)).chat(systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getAllValues()).containsExactly(
                SingleModelRewriter.RAG_SYSTEM_PROMPT,
                SingleModelRewriter.RAG_SYSTEM_PROMPT);
        assertThat(userCaptor.getAllValues().get(0)).isEqualTo(userCaptor.getAllValues().get(1));
    }

    @Test
    void shouldTrackTotalPromptOnceAndRagDeltaOnceUsingSameDate() {
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet("delta", "当前源内容");
        when(referenceRetriever.retrieveReferences("tw-delta", "当前源内容"))
                .thenReturn(List.of(reference("ref-1", "标题", "摘要", "风格")));
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.time.LocalDate> totalDateCaptor = ArgumentCaptor.forClass(java.time.LocalDate.class);
        ArgumentCaptor<Integer> deltaCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<java.time.LocalDate> deltaDateCaptor = ArgumentCaptor.forClass(java.time.LocalDate.class);
        verify(tokenUsageTracker, times(1)).track(org.mockito.ArgumentMatchers.eq("deepseek"), promptCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(LLM_RESPONSE), totalDateCaptor.capture());
        verify(tokenUsageTracker, times(1)).trackRagPromptDelta(deltaCaptor.capture(), deltaDateCaptor.capture());
        assertThat(promptCaptor.getValue()).startsWith(SingleModelRewriter.RAG_SYSTEM_PROMPT);
        assertThat(deltaCaptor.getValue()).isPositive();
        assertThat(deltaDateCaptor.getValue()).isEqualTo(totalDateCaptor.getValue());
    }

    @Test
    void shouldRegisterSingleModelRewriterWhenReferenceRetrieverBeanMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(RewriterSpringConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(SingleModelRewriter.class));
    }

    @Test
    void shouldInjectReferenceRetrieverWhenBeanExistsInSpringContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(RewriterSpringConfig.class, ReferenceRetrieverSpringConfig.class)
                .run(ctx -> {
                    SingleModelRewriter springRewriter = ctx.getBean(SingleModelRewriter.class);
                    LlmClient springLlm = ctx.getBean(LlmClient.class);
                    ReferenceRetriever springRetriever = ctx.getBean(ReferenceRetriever.class);
                    when(springRetriever.retrieveReferences("tw-spring", "当前源内容"))
                            .thenReturn(List.of(reference("ref-1", "标题", "摘要", "风格")));
                    when(springLlm.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

                    springRewriter.rewrite(sampleTweet("spring", "当前源内容"));

                    verify(springRetriever).retrieveReferences("tw-spring", "当前源内容");
                });
    }

    private SingleModelRewriter ragRewriter() {
        return new SingleModelRewriter(llmClient, properties, tokenUsageTracker, Optional.of(referenceRetriever));
    }

    private void assertLegacyPromptAndNoDeltaForReferences(List<ReferenceArticle> references, String tweetId) {
        org.mockito.Mockito.reset(llmClient, tokenUsageTracker, referenceRetriever);
        SingleModelRewriter rewriter = ragRewriter();
        Tweet tweet = sampleTweet(tweetId, "当前源内容");
        when(referenceRetriever.retrieveReferences("tw-" + tweetId, "当前源内容")).thenReturn(references);
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        rewriter.rewrite(tweet);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(systemCaptor.capture(), userCaptor.capture());
        assertThat(systemCaptor.getValue()).isEqualTo(SingleModelRewriter.SYSTEM_PROMPT);
        assertThat(userCaptor.getValue()).isEqualTo(
                SingleModelRewriter.USER_PROMPT_TEMPLATE.replace("{{CONTENT}}", "当前源内容"));
        verify(tokenUsageTracker).track(org.mockito.ArgumentMatchers.eq("deepseek"),
                org.mockito.ArgumentMatchers.eq(systemCaptor.getValue() + userCaptor.getValue()),
                org.mockito.ArgumentMatchers.eq(LLM_RESPONSE), org.mockito.ArgumentMatchers.any(java.time.LocalDate.class));
        verify(tokenUsageTracker, never()).trackRagPromptDelta(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(java.time.LocalDate.class));
        verifyNoMoreInteractions(tokenUsageTracker);
    }

    private static Tweet sampleTweet(String id, String content) {
        return Tweet.builder()
                .id(id)
                .author("sample")
                .content(content)
                .url("https://twitter.com/sample/status/" + id)
                .innovationScore(7.0)
                .build();
    }

    private static GitHubRepo sampleRepo() {
        return GitHubRepo.builder()
                .id("42")
                .fullName("octocat/Hello-World")
                .name("Hello-World")
                .description("A useful project")
                .language("Java")
                .stars(100)
                .forks(20)
                .url("https://github.com/octocat/Hello-World")
                .readmeContent("# Hello\nREADME")
                .valueScore(7.0)
                .valueSummary("一款不错的项目")
                .build();
    }

    private static ReferenceArticle reference(String id, String title, String summary, String styleFeatures) {
        return ReferenceArticle.builder()
                .articleId(id)
                .title(title)
                .summary(summary)
                .styleFeatures(styleFeatures)
                .similarityScore(0.9)
                .build();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    @Configuration
    @Import({RewriterConfig.class, SingleModelRewriter.class})
    static class RewriterSpringConfig {

        @Bean
        LlmClient llmClient() {
            return org.mockito.Mockito.mock(LlmClient.class);
        }

        @Bean
        TokenUsageTracker tokenUsageTracker() {
            return org.mockito.Mockito.mock(TokenUsageTracker.class);
        }
    }

    @Configuration
    static class ReferenceRetrieverSpringConfig {

        @Bean
        ReferenceRetriever referenceRetriever() {
            return org.mockito.Mockito.mock(ReferenceRetriever.class);
        }
    }
}
