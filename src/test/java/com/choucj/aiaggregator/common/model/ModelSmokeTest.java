package com.choucj.aiaggregator.common.model;

import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.task.model.ProcessingTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 1.3 核心模型 smoke test — 验证 Lombok 生成方法 + 关键默认值.
 *
 * <p>本测试不覆盖业务逻辑(模型均为 POJO), 仅验证:
 * <ul>
 *   <li>Lombok 四注解组合(@Data / @Builder / @NoArgsConstructor / @AllArgsConstructor)生效</li>
 *   <li>关键字段默认值符合设计意图(特别是 Article.aiGenerated 合规要求 AR8)</li>
 *   <li>嵌套枚举可访问(ProcessingTask.TaskType / TaskStatus)</li>
 * </ul>
 *
 * <p>防回归重点:{@link Article#isAiGenerated()} 必须默认 {@code true}(W2 修复).
 */
class ModelSmokeTest {

    @Test
    void shouldBuildArticleWithAiGeneratedDefaultTrue() {
        Article article = Article.builder()
                .id("test-id")
                .title("Test Article")
                .content("Test content")
                .createdAt(LocalDateTime.now())
                .build();

        assertThat(article.getId()).isEqualTo("test-id");
        assertThat(article.getTitle()).isEqualTo("Test Article");
        assertThat(article.isAiGenerated())
                .as("Article.aiGenerated 必须默认 true (合规要求 AR8: AI 内容必须标识; 显式 false 才表示非 AI)")
                .isTrue();
        assertThat(article.getInnovationScore())
                .as("Article.innovationScore 默认 0 表示未评分")
                .isZero();
    }

    @Test
    void shouldOverrideAiGeneratedToFalseWhenExplicitlySet() {
        Article article = Article.builder()
                .title("Manual edit")
                .aiGenerated(false)
                .build();

        assertThat(article.isAiGenerated()).isFalse();
    }

    @Test
    void shouldNewArticleAlsoHaveAiGeneratedDefaultTrue() {
        Article article = new Article();

        assertThat(article.isAiGenerated())
                .as("无参构造也必须保持 aiGenerated=true 默认值, 与 builder 一致")
                .isTrue();
    }

    @Test
    void shouldBuildTweetWithAllFieldsMutable() {
        LocalDateTime now = LocalDateTime.now();
        Tweet tweet = Tweet.builder()
                .id("123")
                .author("@test")
                .content("content")
                .summary("summary")
                .url("https://twitter.com/test/status/123")
                .publishedAt(now)
                .replyCount(5)
                .retweetCount(10)
                .likeCount(50)
                .imageUrls(java.util.List.of("https://img.example.com/1.jpg"))
                .build();

        assertThat(tweet.getId()).isEqualTo("123");
        assertThat(tweet.getAuthor()).isEqualTo("@test");
        assertThat(tweet.getPublishedAt()).isEqualTo(now);
        assertThat(tweet.getReplyCount()).isEqualTo(5);
        assertThat(tweet.getImageUrls()).containsExactly("https://img.example.com/1.jpg");
    }

    @Test
    void shouldBuildGitHubRepoWithAllFieldsMutable() {
        GitHubRepo repo = GitHubRepo.builder()
                .id("12345")
                .fullName("owner/repo")
                .name("repo")
                .description("Test repo")
                .language("Java")
                .stars(100)
                .forks(20)
                .readmeUrl("https://api.github.com/repos/owner/repo/readme")
                .readmeContent("# Test")
                .url("https://github.com/owner/repo")
                .build();

        assertThat(repo.getFullName()).isEqualTo("owner/repo");
        assertThat(repo.getStars()).isEqualTo(100);
        assertThat(repo.getReadmeContent()).isEqualTo("# Test");
    }

    @Test
    void shouldBuildProcessingTaskWithNestedEnums() {
        ProcessingTask task = ProcessingTask.builder()
                .id("task-uuid")
                .type(ProcessingTask.TaskType.TWITTER)
                .payload("{\"tweetId\":\"123\"}")
                .status(ProcessingTask.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .build();

        assertThat(task.getType()).isEqualTo(ProcessingTask.TaskType.TWITTER);
        assertThat(task.getStatus()).isEqualTo(ProcessingTask.TaskStatus.PENDING);
        assertThat(task.getRetryCount()).isZero();

        assertThat(ProcessingTask.TaskType.values())
                .containsExactly(ProcessingTask.TaskType.TWITTER, ProcessingTask.TaskType.GITHUB);
        assertThat(ProcessingTask.TaskStatus.values())
                .containsExactly(
                        ProcessingTask.TaskStatus.PENDING,
                        ProcessingTask.TaskStatus.PROCESSING,
                        ProcessingTask.TaskStatus.COMPLETED,
                        ProcessingTask.TaskStatus.FAILED);
    }

    @Test
    void shouldConstructErrorResponseWithCodeAndMessage() {
        ErrorResponse response = new ErrorResponse(ErrorCode.RETRYABLE_ERROR, "Network timeout");

        assertThat(response.getCode()).isEqualTo(ErrorCode.RETRYABLE_ERROR);
        assertThat(response.getMessage()).isEqualTo("Network timeout");
        // Story 2.1 delta: 新增 EXTERNAL_API_ERROR → 6 → 7
        assertThat(ErrorCode.values()).hasSize(7);
    }
}
