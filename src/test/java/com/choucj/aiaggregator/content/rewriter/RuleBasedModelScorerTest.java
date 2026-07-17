package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.model.Article;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedModelScorerTest {

    private final RuleBasedModelScorer scorer = new RuleBasedModelScorer();

    @Test
    void shouldGiveHigherScoreToStructuredSourceGroundedArticle() {
        Article strong = Article.builder()
                .title("Java 21 Virtual Threads 让服务端更简单")
                .content("""
                        Java 21 的 virtual threads 把并发代码写法拉回同步风格。

                        对服务端来说，它的价值不是炫技，而是减少线程池调参和回调嵌套。
                        旧代码里常见的阻塞 I/O 场景，可以用更轻的线程承载更多请求。""")
                .digest("Java 21 virtual threads 让阻塞式服务端代码更简单。")
                .build();
        Article weak = Article.builder()
                .title(SingleModelRewriter.FALLBACK_TITLE)
                .content("首先，本文将介绍一个趋势。最新数据显示，这件事非常重要。综上所述，大家都应该关注。")
                .digest("")
                .build();
        ModelScorer.SourceContext sourceContext = new ModelScorer.SourceContext(
                "tw-42", "Java 21 virtual threads make servers simpler and reduce thread pool tuning");

        ModelScorer.ScoreResult strongScore = scorer.score(strong, sourceContext, "deepseek");
        ModelScorer.ScoreResult weakScore = scorer.score(weak, sourceContext, "glm");

        assertThat(strongScore.totalScore()).isGreaterThan(weakScore.totalScore());
        assertThat(strongScore.reasonCodes()).doesNotContain("TITLE_WEAK", "UNSUPPORTED_FACT_MARKER", "AI_TONE_MARKER");
        assertThat(weakScore.reasonCodes())
                .contains("TITLE_WEAK", "DIGEST_MISSING", "UNSUPPORTED_FACT_MARKER", "AI_TONE_MARKER");
    }

    @Test
    void shouldReturnZeroForNullOrBlankCandidate() {
        assertThat(scorer.score(null, new ModelScorer.SourceContext("tw-1", "source"), "deepseek")
                .totalScore()).isZero();
        assertThat(scorer.score(Article.builder().title("x").content("").digest("").build(),
                new ModelScorer.SourceContext("tw-1", "source"), "glm").totalScore()).isZero();
    }

    @Test
    void shouldKeepDimensionScoresWithinBounds() {
        Article article = Article.builder()
                .title("标题")
                .content("Java virtual threads improve service concurrency.\n\n这是一段结构清楚的说明。")
                .digest("摘要")
                .build();

        ModelScorer.ScoreResult result = scorer.score(article,
                new ModelScorer.SourceContext("tw-1", "Java virtual threads service concurrency"), "deepseek");

        assertThat(result.coherenceScore()).isBetween(0, 25);
        assertThat(result.accuracyScore()).isBetween(0, 25);
        assertThat(result.readabilityScore()).isBetween(0, 25);
        assertThat(result.humanToneScore()).isBetween(0, 25);
        assertThat(result.totalScore()).isBetween(0, 100);
    }
}
