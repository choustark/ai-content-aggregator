package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rewriter.ContentRewriter;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.status.ArticleStatusService;
import com.choucj.aiaggregator.source.github.GitHubSource;
import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.github.service.GitHubValueAnalyzer;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 4.4 — {@link GitHubProcessor} 单元测试.
 *
 * <p>核心覆盖 (镜像 {@link TwitterProcessorTest} 模式):
 * <ul>
 *   <li>AC-1 编排顺序: fetch → README 补全 → value analyze → rewrite → publish</li>
 *   <li>AC-4 fetch RetryableException (配额耗尽): summary discovered=0, 不抛</li>
 *   <li>AC-5 summary 6 字段日志</li>
 *   <li>AC-8 per-article 隔离: 第 2 条 rewrite 抛 → failure=1, 其他正常完成</li>
 *   <li>AC-8 faultIsolationEnabled=false: 单条异常透传到 process() 顶层</li>
 *   <li>AC-9 D3 nullable: valueScore=null 的 fallback repo 不 NPE</li>
 *   <li>AC-10 markPending: verify 调用次数 = analyzed.size()</li>
 *   <li>README 补全失败: 走元数据评分分支, log.warn + continue</li>
 *   <li>多 publisher: 第 1 个抛 → 第 2 个不调 (L2 per-article 隔离)</li>
 *   <li>{@link #buildDeterministicArticleId} 合法 / 非法 fullName</li>
 * </ul>
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GitHubProcessorTest {

    @Mock
    private GitHubSource githubSource;
    @Mock
    private GitHubClient githubClient;
    @Mock
    private GitHubValueAnalyzer gitHubValueAnalyzer;
    @Mock
    private ContentRewriter contentRewriter;
    @Mock
    private ContentPublisher contentPublisher;
    @Mock
    private ContentPublisher secondContentPublisher;
    @Mock
    private ArticleStatusService articleStatusService;

    private ProcessorProperties properties;
    private GitHubProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new ProcessorProperties();
        processor = new GitHubProcessor(githubSource, githubClient, gitHubValueAnalyzer,
                contentRewriter, List.of(contentPublisher), properties, articleStatusService);
    }

    @Test
    void shouldRunFullPipelineHappyPath() {
        GitHubRepo r1 = repo("octocat/Hello-World");
        GitHubRepo r2 = repo("langchain4j/langchain4j");
        when(githubSource.fetch()).thenReturn(List.of(r1, r2));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# Hello");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1, r2));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-octocat-Hello-World"))
                .thenReturn(article("gh-langchain4j-langchain4j"));

        processor.process();

        verify(githubClient, times(2)).fetchReadme(anyString(), anyString());
        verify(articleStatusService, times(2)).markPending(anyString());
        verify(articleStatusService).markPending(eq("gh-octocat-Hello-World"));
        verify(articleStatusService).markPending(eq("gh-langchain4j-langchain4j"));
        verify(contentRewriter, times(2)).rewrite(any(GitHubRepo.class));
        verify(contentPublisher, times(2)).publish(any());
    }

    @Test
    void shouldHandleEmptyFetchGracefully() {
        when(githubSource.fetch()).thenReturn(List.of());
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of());

        processor.process();

        verify(githubClient, never()).fetchReadme(anyString(), anyString());
        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
        verify(contentPublisher, never()).publish(any());
    }

    @Test
    void shouldReturnZeroSummaryWhenGitHubDisabled(CapturedOutput output) {
        processor = new GitHubProcessor(java.util.Optional.of(githubSource),
                java.util.Optional.of(githubClient),
                java.util.Optional.of(gitHubValueAnalyzer),
                contentRewriter, List.of(contentPublisher), properties, articleStatusService,
                false, true, true);

        processor.process();

        verify(githubSource, never()).fetch();
        verify(githubClient, never()).fetchReadme(anyString(), anyString());
        verify(gitHubValueAnalyzer, never()).analyzeAndFilter(any());
        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
        assertThat(output.getAll()).contains("GitHub Pipeline disabled");
        assertThat(output.getAll()).contains("发现=0");
        assertThat(output.getAll()).contains("README 补全失败=0");
        assertThat(output.getAll()).contains("价值分析通过=0");
        assertThat(output.getAll()).contains("改写成功=0");
        assertThat(output.getAll()).contains("归档成功=0");
        assertThat(output.getAll()).contains("失败=0");
    }

    @Test
    void shouldNormalizeNullFetchResultAsEmptyBatch(CapturedOutput output) {
        when(githubSource.fetch()).thenReturn(null);
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of());

        processor.process();

        verify(githubClient, never()).fetchReadme(anyString(), anyString());
        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
        assertThat(output.getAll()).contains("GitHubSource.fetch 返回 null");
        assertThat(output.getAll()).contains("发现=0");
    }

    @Test
    void shouldTolerateFetchRetryableExceptionAsEmptyBatch() {
        // AC-4 配额耗尽场景 — GitHubClientImpl 已映射为 RetryableException
        when(githubSource.fetch()).thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "GitHub rate limit exhausted"));
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of());

        processor.process();

        // fetch 兜底后 repos=[], 仍会进入 Stage 2 (空列表), 但 rewrite / publish 不应调用
        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
        verify(contentPublisher, never()).publish(any());
    }

    @Test
    void shouldTolerateFetchRuntimeExceptionAsEmptyBatch() {
        // L1 兜底: 其他 RuntimeException (NPE 等) 不应穿透到 ContentScheduler
        when(githubSource.fetch()).thenThrow(new IllegalStateException("unexpected"));
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of());

        processor.process();

        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
    }

    @Test
    void shouldContinueWhenReadmeFetchFails() {
        // AC: README 补全失败 → log.warn + continue, repo 仍进入 valueAnalyzer
        GitHubRepo r1 = repo("octocat/Hello-World");
        GitHubRepo r2 = repo("langchain4j/langchain4j");
        when(githubSource.fetch()).thenReturn(List.of(r1, r2));
        when(githubClient.fetchReadme(eq("octocat"), eq("Hello-World")))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "rate limited"));
        when(githubClient.fetchReadme(eq("langchain4j"), eq("langchain4j")))
                .thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r2));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-langchain4j-langchain4j"));

        processor.process();

        // r1 的 readmeContent 保持 null, r2 进入分析
        verify(gitHubValueAnalyzer).analyzeAndFilter(any());
        verify(contentRewriter, times(1)).rewrite(any(GitHubRepo.class));
    }

    @Test
    void shouldIsolatePerArticleFailureAndContinue() {
        // AC-8 per-article 隔离: 第 2 条 rewrite 抛 → failure=1, 其他正常
        GitHubRepo r1 = repo("octocat/Hello-World");
        GitHubRepo r2 = repo("langchain4j/langchain4j");
        GitHubRepo r3 = repo("spring-projects/spring-boot");
        when(githubSource.fetch()).thenReturn(List.of(r1, r2, r3));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1, r2, r3));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-octocat-Hello-World"))
                .thenThrow(new RuntimeException("LLM down"))
                .thenReturn(article("gh-spring-projects-spring-boot"));

        processor.process();

        verify(contentRewriter, times(3)).rewrite(any(GitHubRepo.class));
        // r1+r3 成功 (2 publish), r2 rewrite 抛 → 无 publish
        verify(contentPublisher, times(2)).publish(any());
    }

    @Test
    void shouldPropagateExceptionWhenFaultIsolationDisabled() {
        // AC-8 faultIsolationEnabled=false: 单条异常透传到 process() 顶层
        properties.setFaultIsolationEnabled(false);
        GitHubRepo r1 = repo("octocat/Hello-World");
        when(githubSource.fetch()).thenReturn(List.of(r1));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> processor.process())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void shouldHandleNullValueScoreFromFallbackReposWithoutNpe() {
        // AC-9 D3 nullable: Star fallback 路径返回的 repo valueScore/valueSummary=null
        GitHubRepo r1 = repo("octocat/Hello-World");
        r1.setValueScore(null);
        r1.setValueSummary(null);
        when(githubSource.fetch()).thenReturn(List.of(r1));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-octocat-Hello-World"));

        processor.process(); // 不抛 NPE

        verify(contentPublisher, times(1)).publish(any());
    }

    @Test
    void shouldStopCallingSubsequentPublishersWhenFirstFails() {
        // 多 publisher L2 隔离: 第 1 个 publisher 抛 → 第 2 个本条不再调
        processor = new GitHubProcessor(githubSource, githubClient, gitHubValueAnalyzer,
                contentRewriter, List.of(contentPublisher, secondContentPublisher),
                properties, articleStatusService);
        GitHubRepo r1 = repo("octocat/Hello-World");
        when(githubSource.fetch()).thenReturn(List.of(r1));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-octocat-Hello-World"));
        doThrow(new RuntimeException("wechat down")).when(contentPublisher).publish(any());

        processor.process();

        verify(contentPublisher, times(1)).publish(any());
        verify(secondContentPublisher, never()).publish(any());
    }

    @Test
    void shouldTolerateValueAnalyzerUnexpectedException() {
        // L3 兜底: analyzer 异常逃逸时不中断 Pipeline
        when(githubSource.fetch()).thenReturn(List.of(repo("octocat/Hello-World")));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any()))
                .thenThrow(new IllegalStateException("analyzer bug"));

        processor.process(); // 不抛

        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
    }

    @Test
    void shouldNormalizeNullValueAnalyzerResultAsEmptyBatch(CapturedOutput output) {
        when(githubSource.fetch()).thenReturn(List.of(repo("octocat/Hello-World")));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(null);

        processor.process();

        verify(contentRewriter, never()).rewrite(any(GitHubRepo.class));
        assertThat(output.getAll()).contains("GitHubValueAnalyzer.analyzeAndFilter 返回 null");
        assertThat(output.getAll()).contains("价值分析通过=0");
    }

    @Test
    void shouldRejectRewrittenArticleIdMismatchBeforePublish() {
        GitHubRepo r1 = repo("octocat/Hello-World");
        when(githubSource.fetch()).thenReturn(List.of(r1));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1));
        when(contentRewriter.rewrite(any(GitHubRepo.class))).thenReturn(article("gh-other-repo"));

        processor.process();

        verify(articleStatusService).markPending("gh-octocat-Hello-World");
        verify(contentPublisher, never()).publish(any());
    }

    @Test
    void shouldEmitSummaryWithSixFields(CapturedOutput output) {
        // AC-5 summary 日志 6 字段
        GitHubRepo r1 = repo("octocat/Hello-World");
        when(githubSource.fetch()).thenReturn(List.of(r1));
        when(githubClient.fetchReadme(anyString(), anyString())).thenReturn("# readme");
        when(gitHubValueAnalyzer.analyzeAndFilter(any())).thenReturn(List.of(r1));
        when(contentRewriter.rewrite(any(GitHubRepo.class)))
                .thenReturn(article("gh-octocat-Hello-World"));

        processor.process();

        String out = output.getAll();
        assertThat(out).contains("GitHub Pipeline 完成");
        assertThat(out).contains("发现=1");
        assertThat(out).contains("价值分析通过=1");
        assertThat(out).contains("改写成功=1");
        assertThat(out).contains("归档成功=1");
        assertThat(out).contains("失败=0");
    }

    @Test
    void shouldBuildDeterministicArticleIdWithGhPrefix() {
        GitHubRepo repo = repo("octocat/Hello-World");
        assertThat(GitHubProcessor.buildDeterministicArticleId(repo)).isEqualTo("gh-octocat-Hello-World");
    }

    @Test
    void shouldRejectNullRepoForArticleId() {
        assertThatThrownBy(() -> GitHubProcessor.buildDeterministicArticleId(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("非法 repo.fullName");
    }

    @Test
    void shouldRejectBlankFullNameForArticleId() {
        GitHubRepo repo = repo("octocat/Hello-World");
        repo.setFullName("  ");
        assertThatThrownBy(() -> GitHubProcessor.buildDeterministicArticleId(repo))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldRejectMultipleSlashesForArticleId() {
        GitHubRepo repo = repo("a/b/c");
        assertThatThrownBy(() -> GitHubProcessor.buildDeterministicArticleId(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须 owner/repo");
    }

    @Test
    void shouldRejectIllegalCharsForArticleId() {
        GitHubRepo repo = repo("octo cat/Hello-World");
        assertThatThrownBy(() -> GitHubProcessor.buildDeterministicArticleId(repo))
                .isInstanceOf(NonRetryableException.class);
    }

    private GitHubRepo repo(String fullName) {
        int slash = fullName.indexOf('/');
        String name = slash >= 0 ? fullName.substring(slash + 1) : fullName;
        return GitHubRepo.builder()
                .id("123")
                .fullName(fullName)
                .name(name)
                .description("Sample")
                .language("Java")
                .stars(100)
                .forks(20)
                .url("https://github.com/" + fullName)
                .valueScore(8.0)
                .valueSummary("一款优秀的项目.")
                .build();
    }

    private Article article(String id) {
        return Article.builder()
                .id(id)
                .title("title")
                .content("content")
                .digest("digest")
                .source("GitHub Repo:octocat/Hello-World")
                .aiGenerated(true)
                .innovationScore(8)
                .build();
    }
}
