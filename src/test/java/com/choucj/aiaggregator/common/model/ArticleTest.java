package com.choucj.aiaggregator.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 8.6 Task 1: {@link Article} generationMode / mediaAuditMarkdown schema 扩展测试.
 *
 * <p>覆盖:
 * <ul>
 *   <li>默认值双路径 — {@code new Article()} (noargs, Jackson 反序列化路径) 与
 *       {@code Article.builder().build()} 均得到 {@code generationMode=REWRITE}
 *       ({@code @Builder.Default} + field initializer 双保险)</li>
 *   <li>旧 Redis JSON 兼容 — 无 {@code generationMode} 字段的历史 Article JSON
 *       反序列化后仍为 REWRITE (批量队列向后兼容, D-A 决策)</li>
 *   <li>mediaAuditMarkdown 默认 null — REWRITE 恒 null (D-E 决策)</li>
 *   <li>新 JSON 序列化/反序列化往返 — generationMode 显式携带</li>
 * </ul>
 */
class ArticleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void should_default_generation_mode_to_rewrite_when_created_via_builder() {
        Article article = Article.builder().build();

        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE);
        assertThat(article.getMediaAuditMarkdown()).isNull();
    }

    @Test
    void should_default_generation_mode_to_rewrite_when_created_via_noargs() {
        Article article = new Article();

        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE);
        assertThat(article.getMediaAuditMarkdown()).isNull();
    }

    @Test
    void should_default_generation_mode_to_rewrite_when_deserializing_legacy_json_without_field() throws Exception {
        // 旧 Redis 队列 JSON: 无 generationMode / mediaAuditMarkdown 字段 — Jackson 走 noargs
        // 构造 + setter, field initializer 兜底 REWRITE (D-A 向后兼容论证)
        String legacyJson = """
                {"id":"tw-1","title":"t","content":"c","aiGenerated":true,"innovationScore":8}
                """;

        Article article = objectMapper.readValue(legacyJson, Article.class);

        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.REWRITE);
        assertThat(article.getMediaAuditMarkdown()).isNull();
        assertThat(article.getId()).isEqualTo("tw-1");
    }

    @Test
    void should_round_trip_generation_mode_through_json() throws Exception {
        Article article = Article.builder()
                .id("tw-2")
                .title("t")
                .content("c")
                .generationMode(ContentGenerationMode.PRESERVE_ORIGINAL)
                .mediaAuditMarkdown("| 类型 | 状态 |\n|---|---|\n")
                .build();

        String json = objectMapper.writeValueAsString(article);
        Article deserialized = objectMapper.readValue(json, Article.class);

        assertThat(deserialized.getGenerationMode()).isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL);
        assertThat(deserialized.getMediaAuditMarkdown()).isNotNull();
    }

    @Test
    void should_deserialize_generation_mode_from_json_when_field_present() throws Exception {
        String json = """
                {"id":"tw-3","generationMode":"PRESERVE_ORIGINAL","mediaAuditMarkdown":"audit"}
                """;

        Article article = objectMapper.readValue(json, Article.class);

        assertThat(article.getGenerationMode()).isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL);
        assertThat(article.getMediaAuditMarkdown()).isEqualTo("audit");
    }
}
