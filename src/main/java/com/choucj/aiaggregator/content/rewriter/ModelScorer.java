package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.model.Article;

/**
 * 多模型候选文章评分器.
 */
public interface ModelScorer {

    ScoreResult score(Article candidate, SourceContext sourceContext, String model);

    record SourceContext(String sourceId, String sourceText) {
    }

    record ScoreResult(String model,
                       int totalScore,
                       int coherenceScore,
                       int accuracyScore,
                       int readabilityScore,
                       int humanToneScore,
                       java.util.List<String> reasonCodes) {
    }
}
