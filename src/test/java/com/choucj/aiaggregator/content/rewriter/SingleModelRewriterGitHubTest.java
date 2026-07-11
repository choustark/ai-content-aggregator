package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.content.rewriter.config.RewriterProperties;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Story 4.4 — {@link SingleModelRewriter#rewrite(GitHubRepo)} 单测.
 *
 * <p>覆盖 AC-2 (复用 SingleModelRewriter) + AC-6 (Article.id = gh-{owner}-{repo}) +
 * AC-9 (D3 nullable valueScore/valueSummary 处理) + 默认接口方法抛 NonRetryableException.
 *
 * <p>核心断言:
 * <ul>
 *   <li>Article.id 形如 {@code gh-octocat-Hello-World} (owner/repo 内 {@code /} → {@code -})</li>
 *   <li>{@code source = "GitHub Repo:octocat/Hello-World"} (保留原 fullName)</li>
 *   <li>{@code aiGenerated=true} (AR8 合规)</li>
 *   <li>{@code valueScore=null} → {@code innovationScore=0} (D3 空安全)</li>
 *   <li>{@code readmeContent=null} → 走元数据评分分支, 不抛</li>
 *   <li>非法 fullName (无 {@code /} / 多个 {@code /} / 含非法字符) → NonRetryableException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SingleModelRewriterGitHubTest {

    private static final String LLM_RESPONSE = """
            # 革命性 Java 框架发布

            这是一个新框架, 简化微服务开发.

            ## 关键特性

            - 启动速度快
            - 内存占用低

            > 摘要: 一款新 Java 框架让微服务开发更简单.
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
        properties.setRetryBackoffMs(50L);
        properties.setContentMaxCodePoints(2000);
        rewriter = new SingleModelRewriter(llmClient, properties, tokenUsageTracker);
    }

    @Test
    void shouldRewriteGitHubRepoWithGhPrefixIdAndGitHubSource() {
        GitHubRepo repo = GitHubRepo.builder()
                .id("123")
                .fullName("octocat/Hello-World")
                .name("Hello-World")
                .description("My first repo")
                .language("Java")
                .stars(100)
                .forks(20)
                .url("https://github.com/octocat/Hello-World")
                .readmeContent("# Hello-World\nA demo project.")
                .valueScore(8.0)
                .valueSummary("一款优秀的入门级项目.")
                .build();
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getId()).isEqualTo("gh-octocat-Hello-World");
        assertThat(article.getTitle()).isEqualTo("革命性 Java 框架发布");
        assertThat(article.getContent()).contains("关键特性");
        assertThat(article.getDigest()).isEqualTo("一款新 Java 框架让微服务开发更简单.");
        assertThat(article.getSource()).isEqualTo("GitHub Repo:octocat/Hello-World");
        assertThat(article.isAiGenerated()).isTrue();
        assertThat(article.getInnovationScore()).isEqualTo(8);
        assertThat(article.getOriginalUrl()).isEqualTo("https://github.com/octocat/Hello-World");
        assertThat(article.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFallbackToMetadataWhenReadmeNull() {
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setReadmeContent(null);
        repo.setValueScore(7.5);
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getId()).isEqualTo("gh-octocat-Hello-World");
        assertThat(article.getInnovationScore()).isEqualTo(7);
    }

    @Test
    void shouldFallbackToMetadataWhenReadmeBlank() {
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setReadmeContent("   ");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getId()).isEqualTo("gh-octocat-Hello-World");
    }

    @Test
    void shouldUseZeroInnovationScoreWhenValueScoreNull() {
        // D3: AC-4 Star 数降级路径返回的 repo valueScore=null, 不能直接拆箱
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setValueScore(null);
        repo.setValueSummary(null);
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getInnovationScore()).isEqualTo(0);
    }

    @Test
    void shouldHandleSpecialCharsInOwnerOrRepo() {
        // owner/repo 可含 `-` `_` (Story 4.4 Task 3.1 收紧后字符集 [A-Za-z0-9_-]+, 不再允许 `.`),
        // 替换 `/` 后符合 ARTICLE_ID_PATTERN 字符集
        GitHubRepo repo = sampleRepo("my-org_my-team/repo-name");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getId()).isEqualTo("gh-my-org_my-team-repo-name");
    }

    @Test
    void shouldRejectNullRepo() {
        assertThatThrownBy(() -> rewriter.rewrite((GitHubRepo) null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("repo 或 repo.fullName 为 null/blank");
    }

    @Test
    void shouldRejectBlankFullName() {
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setFullName("  ");

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("repo 或 repo.fullName 为 null/blank");
    }

    @Test
    void shouldRejectFullNameWithoutSlash() {
        GitHubRepo repo = sampleRepo("invalid-no-slash");

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须 owner/repo");
    }

    @Test
    void shouldRejectFullNameWithMultipleSlashes() {
        GitHubRepo repo = sampleRepo("a/b/c");

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须 owner/repo");
    }

    @Test
    void shouldRejectFullNameWithEmptySegments() {
        GitHubRepo repo = sampleRepo("/Hello-World");

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须 owner/repo");
    }

    @Test
    void shouldRejectFullNameWithIllegalChars() {
        // `/` 之外不允许其他特殊字符, e.g. 含空格
        GitHubRepo repo = sampleRepo("octo cat/Hello-World");

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("[A-Za-z0-9_-]+");
    }

    @Test
    void shouldPropagateRetryableExceptionFromLlmWithoutLeakingReadme() {
        // N4: 异常 message 不含 README 正文
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setReadmeContent("SECRET-README-CONTENT-LEAK-MARKER");
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new RetryableException(
                        com.choucj.aiaggregator.common.model.ErrorCode.EXTERNAL_API_ERROR,
                        "LLM timeout"));

        assertThatThrownBy(() -> rewriter.rewrite(repo))
                .isInstanceOf(RetryableException.class)
                .hasMessageNotContaining("SECRET-README-CONTENT-LEAK-MARKER");
    }

    @Test
    void shouldPassSpecialCharsInPromptWithoutIllegalFormatExcept() {
        // B2: README 含 `%` / `${}` 不应抛 IllegalFormatException
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        repo.setReadmeContent("100% 真实 %{inject} ${variable} <script>alert(1)</script>");
        when(llmClient.chat(anyString(), anyString())).thenReturn(LLM_RESPONSE);

        Article article = rewriter.rewrite(repo);

        assertThat(article.getId()).isEqualTo("gh-octocat-Hello-World");
    }

    @Test
    void shouldFallbackTitleWhenLlmOutputMissingHashHeader() {
        GitHubRepo repo = sampleRepo("octocat/Hello-World");
        when(llmClient.chat(anyString(), anyString())).thenReturn("no title header here");

        Article article = rewriter.rewrite(repo);

        assertThat(article.getTitle()).isEqualTo(SingleModelRewriter.FALLBACK_TITLE);
    }

    @Test
    void defaultInterfaceMethodShouldThrowNonRetryable() {
        // 验证 ContentRewriter 默认 default 方法抛 NonRetryableException (而非 UnsupportedOperationException)
        // 这样 GitHubProcessor 的 per-article catch(Exception) 仍能 L2 隔离
        ContentRewriter onlyTweet = new ContentRewriter() {
            @Override
            public Article rewrite(com.choucj.aiaggregator.source.twitter.model.Tweet tweet) {
                return null;
            }
        };

        assertThatThrownBy(() -> onlyTweet.rewrite(sampleRepo("octocat/Hello-World")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("rewrite(GitHubRepo) 未实现");
    }

    private GitHubRepo sampleRepo(String fullName) {
        return GitHubRepo.builder()
                .id("123")
                .fullName(fullName)
                .name(fullName.substring(fullName.indexOf('/') + 1))
                .description("Sample")
                .language("Java")
                .stars(150)
                .forks(30)
                .url("https://github.com/" + fullName)
                .readmeContent("# Sample\nSample readme.")
                .valueScore(7.0)
                .valueSummary("一款不错的项目.")
                .build();
    }
}
