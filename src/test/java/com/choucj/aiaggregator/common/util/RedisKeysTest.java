package com.choucj.aiaggregator.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 1.5a/1.6 {@link RedisKeys} 单测 — 验证键命名合规(冒号分隔小写).
 *
 * <p>合规要求(架构 L1299-1312): Redis 键必须 {@code lowercase:colon:separated},
 * 禁止 camelCase / SCREAMING_SNAKE_CASE.
 */
class RedisKeysTest {

    @Test
    void shouldGenerateCacheKeyWithNamespaceAndKey() {
        assertThat(RedisKeys.cache("rsshub", "openai.com/blog"))
                .isEqualTo("cache:rsshub:openai.com/blog");
    }

    @Test
    void shouldGenerateTaskKeyWithQueueName() {
        assertThat(RedisKeys.task("default"))
                .isEqualTo("task:default");
    }

    /**
     * Story 10.4: 旧任务键访问器已改名 legacy*, 键字面值不变, 仅供迁移器读取.
     */
    @Test
    void shouldReturnLegacyTaskQueueKeyForMigrator() {
        assertThat(RedisKeys.legacyTaskQueue())
                .isEqualTo("task:queue");
    }

    @Test
    void shouldReturnLegacyTaskProcessingKeyForMigrator() {
        assertThat(RedisKeys.legacyTaskProcessing())
                .isEqualTo("task:processing");
    }

    /**
     * Story 10.4: 同槽键族 — 5 个 task 新键 + 迁移账本键共享同一固定 hash tag {@code queue}.
     */
    @Test
    void shouldShareSingleHashTableAcrossAllTaskQueueKeys() {
        java.util.List<String> taggedKeys = java.util.List.of(
                RedisKeys.taskPending(),
                RedisKeys.taskProcessing(),
                RedisKeys.taskRetry(),
                RedisKeys.taskDeadLetter(),
                RedisKeys.taskState("task-1"),
                RedisKeys.taskLegacyMigrated());

        for (String key : taggedKeys) {
            int open = key.indexOf('{');
            int close = key.indexOf('}', open);
            assertThat(open).as("键 %s 必须含 hash tag 开括号", key).isGreaterThan(0);
            assertThat(close).as("键 %s 必须含 hash tag 闭括号", key).isGreaterThan(open);
            assertThat(key.substring(open + 1, close))
                    .as("键 %s 的 hash tag 必须全等", key)
                    .isEqualTo("queue");
        }
    }

    /**
     * Story 10.4: 同槽键族字面值契约 + taskState 含 taskId.
     */
    @Test
    void shouldFormatTaskQueueSameSlotKeys() {
        assertThat(RedisKeys.taskPending()).isEqualTo("task:{queue}:pending");
        assertThat(RedisKeys.taskProcessing()).isEqualTo("task:{queue}:processing");
        assertThat(RedisKeys.taskRetry()).isEqualTo("task:{queue}:retry");
        assertThat(RedisKeys.taskDeadLetter()).isEqualTo("task:{queue}:dead-letter");
        assertThat(RedisKeys.taskState("twitter:run"))
                .isEqualTo("task:{queue}:state:twitter:run");
        assertThat(RedisKeys.taskLegacyMigrated()).isEqualTo("task:{queue}:legacy-migrated");
    }

    @Test
    void shouldRejectBlankTaskIdForTaskStateKey() {
        assertThatThrownBy(() -> RedisKeys.taskState(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> RedisKeys.taskState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void shouldGenerateLockKeyWithResource() {
        assertThat(RedisKeys.lock("rss:url1"))
                .isEqualTo("lock:rss:url1");
    }

    @Test
    void shouldBeLowercaseColonSeparated() {
        // 合规元测试: 非 task 键应仅包含小写字母 / 冒号 / 数字 / 连字符 / 标点(URL)
        assertThat(RedisKeys.cache("rsshub", "a")).matches("^[a-z][a-z0-9:./-]*$");
        assertThat(RedisKeys.lock("rss:url1")).matches("^[a-z][a-z0-9:./-]*$");
        assertThat(RedisKeys.articleStatus("tw-123")).matches("^[a-z][a-z0-9:./-]*$");
        // Story 10.4: task 新键含 hash tag 花括号, 打破旧正则, 单独由
        // shouldShareSingleHashTableAcrossAllTaskQueueKeys 分类断言 — 此处确认旧键(legacy*)仍合规
        assertThat(RedisKeys.legacyTaskQueue()).matches("^[a-z][a-z:]*$");
        assertThat(RedisKeys.legacyTaskProcessing()).matches("^[a-z][a-z:]*$");
    }

    /**
     * Story 3.5: articleStatus 键格式 — 服务于 ArticleStatusService 状态机.
     */
    @Test
    void shouldFormatArticleStatusKey() {
        assertThat(RedisKeys.articleStatus("tw-123")).isEqualTo("article:tw-123:status");
    }

    /**
     * Story 4.1: githubTrending 键格式 — 服务于 GitHubSource.fetch() 缓存.
     * spike-4.1 §2.4 命名空间分离.
     */
    @Test
    void shouldFormatGithubTrendingKey() {
        assertThat(RedisKeys.githubTrending("java", 7)).isEqualTo("github:trending:java:7");
        assertThat(RedisKeys.githubTrending("python", 30)).isEqualTo("github:trending:python:30");
        assertThatThrownBy(() -> RedisKeys.githubTrending("", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
        assertThatThrownBy(() -> RedisKeys.githubTrending("java", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lookbackDays");
    }

    /**
     * Story 5.1: embeddingKey 键格式 — 服务于 standalone Redis-Stack 向量库.
     */
    @Test
    void shouldFormatEmbeddingKey() {
        assertThat(RedisKeys.embeddingKey("tw-123")).isEqualTo("rag:embedding:tw-123");
        assertThat(RedisKeys.embeddingPrefix()).isEqualTo("rag:embedding:");
        assertThatThrownBy(() -> RedisKeys.embeddingKey(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("articleId");
    }

    /**
     * Story 5.3: RAG prompt 增量 token 独立键,不能污染原 cost:daily scalar.
     */
    @Test
    void shouldFormatRagExtraCostDailyKey() {
        assertThat(RedisKeys.costDailyRagExtra(java.time.LocalDate.of(2026, 7, 15)))
                .isEqualTo("cost:daily:rag-extra:2026-07-15");
    }

    /**
     * Story 5.5: 模型级成本键必须独立于 cost:daily scalar,防止破坏旧合同.
     */
    @Test
    void shouldFormatCostMonitorKeys() {
        java.time.LocalDate date = java.time.LocalDate.of(2026, 7, 16);
        java.time.YearMonth month = java.time.YearMonth.of(2026, 7);

        assertThat(RedisKeys.costDailyModelInput(date, "deepseek"))
                .isEqualTo("cost:daily:model:2026-07-16:deepseek:input");
        assertThat(RedisKeys.costDailyModelOutput(date, "glm"))
                .isEqualTo("cost:daily:model:2026-07-16:glm:output");
        assertThat(RedisKeys.costDailyModelEstimatedMicroCents(date, "deepseek"))
                .isEqualTo("cost:daily:model:2026-07-16:deepseek:estimated-micro-cents");
        assertThat(RedisKeys.costMonthly(month)).isEqualTo("cost:monthly:2026-07");
        assertThat(RedisKeys.costBudgetHalted(month)).isEqualTo("cost:budget:halted:2026-07");
        assertThat(RedisKeys.costBudgetResume(month)).isEqualTo("cost:budget:resume:2026-07");
    }

    @Test
    void shouldRejectBlankCostModelName() {
        assertThatThrownBy(() -> RedisKeys.costDailyModelInput(java.time.LocalDate.now(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    /** Story 7.4 T5.1 (AC6): tweetMedia 键格式 + 与 tweet({id}) 命名空间分离. */
    @Test
    void shouldFormatTweetMediaKey() {
        assertThat(RedisKeys.tweetMedia("1234567890")).isEqualTo("tweet:1234567890:media");
        // 命名空间分离: tweet({id}) 是 String 缓存, tweetMedia({id}) 是 JSON 状态快照, 互不冲突
        assertThat(RedisKeys.tweetMedia("1234567890")).isNotEqualTo(RedisKeys.tweet("1234567890"));
    }

    /** Story 7.4 T5.1 (AC6): tweetId blank 校验. */
    @Test
    void shouldRejectBlankTweetIdForTweetMediaKey() {
        assertThatThrownBy(() -> RedisKeys.tweetMedia(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tweetId");
        assertThatThrownBy(() -> RedisKeys.tweetMedia(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tweetId");
    }
}
