package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.model.Article;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Story 5.4 MVP 本地规则评分器，避免额外 LLM 裁判调用.
 */
@Component
public class RuleBasedModelScorer implements ModelScorer {

    private static final List<String> UNSUPPORTED_FACT_MARKERS = List.of(
            "官方宣布", "最新数据显示", "研究表明", "专家表示", "据报道", "业内人士", "消息称");
    private static final List<String> AI_TONE_MARKERS = List.of(
            "作为ai", "作为 AI", "首先", "其次", "最后", "综上所述", "本文将", "改写如下");

    @Override
    public ScoreResult score(Article candidate, SourceContext sourceContext, String model) {
        List<String> reasonCodes = new ArrayList<>();
        if (candidate == null) {
            return new ScoreResult(model, 0, 0, 0, 0, 0, List.of("NULL_CANDIDATE"));
        }

        String title = safe(candidate.getTitle());
        String content = safe(candidate.getContent());
        String digest = safe(candidate.getDigest());
        String combined = (title + "\n" + content + "\n" + digest).toLowerCase(Locale.ROOT);
        if (content.isBlank()) {
            return new ScoreResult(model, 0, 0, 0, 0, 0, List.of("BLANK_CONTENT"));
        }

        int coherenceScore = scoreCoherence(title, content, digest, reasonCodes);
        int accuracyScore = scoreAccuracy(combined, sourceContext, reasonCodes);
        int readabilityScore = scoreReadability(content, digest, reasonCodes);
        int humanToneScore = scoreHumanTone(combined, reasonCodes);
        int total = coherenceScore + accuracyScore + readabilityScore + humanToneScore;

        if (reasonCodes.isEmpty()) {
            reasonCodes.add("PASS");
        }
        return new ScoreResult(model, total, coherenceScore, accuracyScore, readabilityScore,
                humanToneScore, List.copyOf(reasonCodes));
    }

    private static int scoreCoherence(String title, String content, String digest, List<String> reasonCodes) {
        int score = 25;
        if (title.isBlank() || SingleModelRewriter.FALLBACK_TITLE.equals(title)) {
            score -= 8;
            reasonCodes.add("TITLE_WEAK");
        }
        if (digest.isBlank()) {
            score -= 5;
            reasonCodes.add("DIGEST_MISSING");
        }
        if (!content.contains("\n") && content.length() > 240) {
            score -= 5;
            reasonCodes.add("STRUCTURE_FLAT");
        }
        if (content.length() < 80) {
            score -= 6;
            reasonCodes.add("CONTENT_TOO_SHORT");
        }
        return clamp(score);
    }

    private static int scoreAccuracy(String combined,
                                     SourceContext sourceContext,
                                     List<String> reasonCodes) {
        int score = 25;
        Set<String> sourceTerms = extractTerms(sourceContext == null ? "" : sourceContext.sourceText());
        if (sourceTerms.isEmpty()) {
            reasonCodes.add("SOURCE_TERMS_EMPTY");
            return 15;
        }

        long matchedTerms = sourceTerms.stream().filter(combined::contains).count();
        double coverage = (double) matchedTerms / sourceTerms.size();
        if (coverage < 0.25) {
            score -= 10;
            reasonCodes.add("LOW_SOURCE_COVERAGE");
        } else if (coverage < 0.45) {
            score -= 5;
            reasonCodes.add("PARTIAL_SOURCE_COVERAGE");
        }

        for (String marker : UNSUPPORTED_FACT_MARKERS) {
            if (combined.contains(marker.toLowerCase(Locale.ROOT))) {
                score -= 4;
                reasonCodes.add("UNSUPPORTED_FACT_MARKER");
                break;
            }
        }
        return clamp(score);
    }

    private static int scoreReadability(String content, String digest, List<String> reasonCodes) {
        int score = 25;
        int contentLength = content.codePointCount(0, content.length());
        if (contentLength < 120) {
            score -= 5;
            reasonCodes.add("READABILITY_SHORT");
        } else if (contentLength > 3000) {
            score -= 5;
            reasonCodes.add("READABILITY_LONG");
        }
        if (digest.codePointCount(0, digest.length()) > 120) {
            score -= 5;
            reasonCodes.add("DIGEST_TOO_LONG");
        }
        for (String paragraph : content.split("\\R\\s*\\R|\\R")) {
            if (paragraph.codePointCount(0, paragraph.length()) > 500) {
                score -= 4;
                reasonCodes.add("PARAGRAPH_TOO_LONG");
                break;
            }
        }
        return clamp(score);
    }

    private static int scoreHumanTone(String combined, List<String> reasonCodes) {
        int score = 25;
        for (String marker : AI_TONE_MARKERS) {
            if (combined.contains(marker.toLowerCase(Locale.ROOT))) {
                score -= 4;
                reasonCodes.add("AI_TONE_MARKER");
            }
        }
        return clamp(score);
    }

    private static Set<String> extractTerms(String sourceText) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = safe(sourceText).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9_+#.-]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && terms.size() < 32) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(25, score));
    }
}
