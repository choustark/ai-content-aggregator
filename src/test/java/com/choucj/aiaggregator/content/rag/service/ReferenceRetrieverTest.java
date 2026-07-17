package com.choucj.aiaggregator.content.rag.service;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.embedding.EmbeddingService;
import com.choucj.aiaggregator.content.rag.config.RagConfig;
import com.choucj.aiaggregator.content.rag.config.RagProperties;
import com.choucj.aiaggregator.content.rag.model.ReferenceArticle;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.2 — RAG 参考文章检索器单元测试.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReferenceRetrieverTest {

    private EmbeddingService embeddingService;
    private ReferenceRetriever retriever;

    @BeforeEach
    void setUp() {
        embeddingService = Mockito.mock(EmbeddingService.class);
        RagProperties properties = new RagProperties();
        retriever = new ReferenceRetrieverImpl(embeddingService, properties);
    }

    @Test
    void shouldEmbedCurrentContentAndPassConfiguredSearchCriteria() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-1", "当前内容")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-1", "当前内容");

        assertThat(references).isEmpty();
        verify(embeddingService).embedText("tw-1", "当前内容");
        verify(embeddingService).searchSimilar(vector, 4, 0.75);
    }

    @Test
    void shouldReturnThreeMappedReferencesOrderedBySimilarityDescending() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-2", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-low", 0.81, """
                        ## 次高标题
                        这是一段次高内容。
                        """),
                match("article-high", 0.96, """
                        # 历史标题
                        > 摘要: 这是一段人工摘要
                        - 要点一
                        - 要点二
                        正文段落。
                        """),
                match("article-mid", 0.88, """
                        # 中间标题
                        > 摘要: 中间摘要
                        正文段落。
                        """))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-2", "检索文本");

        assertThat(references).hasSize(3);
        assertThat(references).extracting(ReferenceArticle::articleId)
                .containsExactly("article-high", "article-mid", "article-low");
        assertThat(references.getFirst().title()).isEqualTo("历史标题");
        assertThat(references.getFirst().summary()).isEqualTo("这是一段人工摘要");
        assertThat(references.getFirst().styleFeatures()).contains("含小标题", "有列表", "有引用");
        assertThat(references.getFirst().similarityScore()).isEqualTo(0.96);
        assertThat(references.get(1).title()).isEqualTo("中间标题");
        assertThat(references.get(1).summary()).isEqualTo("中间摘要");
        assertThat(references.get(2).title()).isEqualTo("次高标题");
        assertThat(references.get(2).summary()).isEqualTo("这是一段次高内容。");
    }

    @Test
    void shouldLogSearchContextWhenRetrievalSucceeds(CapturedOutput output) {
        float[] vector = vector();
        when(embeddingService.embedText("tw-log", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-1", 0.9, "# 标题\n正文"))));

        retriever.retrieveReferences("tw-log", "检索文本");

        assertThat(output).contains("sourceId=tw-log")
                .contains("文本长度=4")
                .contains("maxReferences=3")
                .contains("threshold=0.75")
                .contains("返回数量=1")
                .contains("耗时=");
    }

    @Test
    void shouldLimitReferencesToConfiguredMaximum() {
        RagProperties properties = new RagProperties();
        properties.setMaxReferences(2);
        retriever = new ReferenceRetrieverImpl(embeddingService, properties);
        float[] vector = vector();
        when(embeddingService.embedText("tw-3", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 3, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-1", 0.93, "# A\n正文"),
                match("article-2", 0.92, "# B\n正文"),
                match("article-3", 0.91, "# C\n正文"))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-3", "检索文本");

        assertThat(references).hasSize(2);
    }

    @Test
    void shouldOverFetchAndExcludeCurrentSourceIdBeforeLimit() {
        RagProperties properties = new RagProperties();
        properties.setMaxReferences(2);
        retriever = new ReferenceRetrieverImpl(embeddingService, properties);
        float[] vector = vector();
        when(embeddingService.embedText("tw-self", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 3, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("tw-self", 0.99, "# 当前文章\n正文"),
                match("article-1", 0.93, "# A\n正文"),
                match("article-2", 0.92, "# B\n正文"))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-self", "检索文本");

        assertThat(references).extracting(ReferenceArticle::articleId)
                .containsExactly("article-1", "article-2");
        verify(embeddingService).searchSimilar(vector, 3, 0.75);
    }

    @Test
    void shouldExcludeSelfWhenSelfAppearsInMiddleOrTail() {
        RagProperties properties = new RagProperties();
        properties.setMaxReferences(3);
        retriever = new ReferenceRetrieverImpl(embeddingService, properties);
        float[] vector = vector();
        when(embeddingService.embedText("gh-owner-repo", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-high", 0.96, "# A\n正文"),
                match("gh-owner-repo", 0.95, "# 当前文章\n正文"),
                match("article-mid", 0.90, "# B\n正文"),
                match("article-low", 0.80, "# C\n正文"))));

        List<ReferenceArticle> references = retriever.retrieveReferences("gh-owner-repo", "检索文本");

        assertThat(references).extracting(ReferenceArticle::articleId)
                .containsExactly("article-high", "article-mid", "article-low");
    }

    @Test
    void shouldReturnFewerReferencesWhenOnlySelfAndInvalidMatchesExist() {
        RagProperties properties = new RagProperties();
        properties.setMaxReferences(3);
        retriever = new ReferenceRetrieverImpl(embeddingService, properties);
        float[] vector = vector();
        when(embeddingService.embedText("tw-limited", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(Arrays.asList(
                null,
                match("tw-limited", 0.99, "# 当前文章\n正文"),
                match("article-1", 0.91, "# A\n正文"))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-limited", "检索文本");

        assertThat(references).extracting(ReferenceArticle::articleId)
                .containsExactly("article-1");
    }

    @Test
    void shouldUseFallbackSummaryWhenNoExplicitSummaryExists() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-4", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-1", 0.9, """
                        # Markdown 标题

                        纯正文内容，应该被截取为摘要。
                        """))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-4", "检索文本");

        assertThat(references.getFirst().title()).isEqualTo("Markdown 标题");
        assertThat(references.getFirst().summary()).isEqualTo("纯正文内容，应该被截取为摘要。");
        assertThat(references.getFirst().styleFeatures()).isNotBlank();
    }

    @Test
    void shouldTruncateSummaryByCodePointWithoutBreakingEmoji() {
        float[] vector = vector();
        String longText = "正文" + "🙂".repeat(130);
        when(embeddingService.embedText("tw-emoji", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-emoji", 0.9, longText))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-emoji", "检索文本");

        String summary = references.getFirst().summary();
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(120);
        assertThat(summary.codePointBefore(summary.length())).isEqualTo("🙂".codePointAt(0));
    }

    @Test
    void shouldCalculateParagraphStyleOutsideFencedCodeBlocks() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-style", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-style", 0.9, """
                        # 标题

                        这是一个短段落。

                        ```
                        这是一段很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的代码注释
                        ```
                        """))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-style", "检索文本");

        assertThat(references.getFirst().styleFeatures()).contains("含代码块", "段落简短");
        assertThat(references.getFirst().styleFeatures()).doesNotContain("段落中等", "段落较长");
    }

    @Test
    void shouldReturnDefaultsForBlankSegmentText() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-5", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75)).thenReturn(new EmbeddingSearchResult<>(List.of(
                match("article-1", 0.9, null))));

        List<ReferenceArticle> references = retriever.retrieveReferences("tw-5", "检索文本");

        assertThat(references.getFirst().title()).isEqualTo("(无标题)");
        assertThat(references.getFirst().summary()).isEqualTo("(无摘要)");
        assertThat(references.getFirst().styleFeatures()).isEqualTo("(无风格特征)");
    }

    @Test
    void shouldReturnEmptyListWhenNoMatchesExist() {
        float[] vector = vector();
        when(embeddingService.embedText("tw-empty", "检索文本")).thenReturn(vector);
        when(embeddingService.searchSimilar(vector, 4, 0.75))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        assertThat(retriever.retrieveReferences("tw-empty", "检索文本")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmbeddingServiceThrowsRetryableException() {
        when(embeddingService.embedText("tw-retry", "检索文本"))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "timeout"));

        assertThat(retriever.retrieveReferences("tw-retry", "检索文本")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmbeddingServiceThrowsNonRetryableException() {
        when(embeddingService.embedText("tw-nonretry", "检索文本"))
                .thenThrow(new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, "bad request"));

        assertThat(retriever.retrieveReferences("tw-nonretry", "检索文本")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmbeddingServiceThrowsUnexpectedRuntimeException() {
        when(embeddingService.embedText("tw-runtime", "检索文本"))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(retriever.retrieveReferences("tw-runtime", "检索文本")).isEmpty();
    }

    @Test
    void shouldNotLogRootCauseMessageWhenRetrievalDegrades(CapturedOutput output) {
        when(embeddingService.embedText("tw-secret", "检索文本"))
                .thenThrow(new IllegalStateException("vector index contains SECRET_REFERENCE_TEXT"));

        assertThat(retriever.retrieveReferences("tw-secret", "检索文本")).isEmpty();

        assertThat(output).contains("sourceId=tw-secret")
                .contains("errorType=IllegalStateException")
                .doesNotContain("SECRET_REFERENCE_TEXT")
                .doesNotContain("cause=");
    }

    @Test
    void shouldRejectBlankInputBeforeCallingEmbeddingService() {
        assertThatThrownBy(() -> retriever.retrieveReferences(" ", "正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId");
        assertThatThrownBy(() -> retriever.retrieveReferences("tw-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void shouldNotRegisterReferenceRetrieverWhenRagDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RagConfig.class, ReferenceRetrieverImpl.class, MockEmbeddingConfig.class)
                .withPropertyValues("features.rag.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ReferenceRetriever.class));
    }

    @Test
    void shouldRegisterReferenceRetrieverWhenRagEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RagConfig.class, ReferenceRetrieverImpl.class, MockEmbeddingConfig.class)
                .withPropertyValues("features.rag.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ReferenceRetriever.class));
    }

    @Configuration
    static class MockEmbeddingConfig {

        @Bean
        EmbeddingService embeddingService() {
            return Mockito.mock(EmbeddingService.class);
        }
    }

    private static EmbeddingMatch<TextSegment> match(String id, double score, String text) {
        return new EmbeddingMatch<>(score, id, Embedding.from(vector()), text == null ? null : TextSegment.from(text));
    }

    private static float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
