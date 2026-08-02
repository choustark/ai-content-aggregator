package com.choucj.aiaggregator.content.rag.service;

import com.choucj.aiaggregator.content.embedding.EmbeddingService;
import com.choucj.aiaggregator.content.rag.config.RagProperties;
import com.choucj.aiaggregator.content.rag.model.ReferenceArticle;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 检索 RAG 历史参考文章，因为改写质量增强层需要复用 EmbeddingService 的向量语义并在失败时安全降级.
 *
 * <p>引用源: Story 5.2 创建。实现层只调用 EmbeddingService，不直接操作 Redis 或 RediSearch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "features.rag", name = "enabled", havingValue = "true")
public class ReferenceRetrieverImpl implements ReferenceRetriever {

    private static final int TITLE_MAX_CODEPOINTS = 80;
    private static final int SUMMARY_MAX_CODEPOINTS = 120;
    private static final Pattern LIST_LINE = Pattern.compile("^([*+-]\\s+|\\d+[.)]\\s+).+");

    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;

    @Override
    public List<ReferenceArticle> retrieveReferences(String sourceId, String text) {
        requireNonBlank(sourceId, "sourceId");
        requireNonBlank(text, "text");
        long startNanos = System.nanoTime();

        try {
            float[] vector = embeddingService.embedText(sourceId, text);
            int maxReferences = ragProperties.getMaxReferences();
            EmbeddingSearchResult<TextSegment> result = embeddingService.searchSimilar(
                    vector, maxReferences + 1, ragProperties.getSimilarityThreshold());
            List<ReferenceArticle> references = toReferences(result, sourceId, maxReferences);
            log.info("RAG 参考检索成功: sourceId={}, 文本长度={}, maxReferences={}, threshold={}, 返回数量={}, 耗时={}ms",
                    sourceId,
                    codePointLength(text),
                    maxReferences,
                    ragProperties.getSimilarityThreshold(),
                    references.size(),
                    elapsedMs(startNanos));
            return references;
        } catch (RuntimeException e) {
            log.warn("RAG 参考检索降级为空: sourceId={}, errorType={}, elapsedMs={}",
                    sourceId,
                    e.getClass().getSimpleName(),
                    elapsedMs(startNanos));
            return List.of();
        }
    }

    private List<ReferenceArticle> toReferences(EmbeddingSearchResult<TextSegment> result,
                                                String sourceId,
                                                int maxReferences) {
        if (result == null || result.matches() == null || result.matches().isEmpty()) {
            return List.of();
        }
        return result.matches().stream()
                .filter(Objects::nonNull)
                .filter(match -> !isBlank(match.embeddingId()))
                .filter(match -> !Objects.equals(match.embeddingId(), sourceId))
                .sorted(Comparator.comparingDouble((EmbeddingMatch<TextSegment> match) -> normalizedScore(match.score()))
                        .reversed())
                .limit(maxReferences)
                .map(this::toReferenceArticle)
                .toList();
    }

    private ReferenceArticle toReferenceArticle(EmbeddingMatch<TextSegment> match) {
        String rawText = match.embedded() == null ? null : match.embedded().text();
        return ReferenceArticle.builder()
                .articleId(match.embeddingId())
                .title(extractTitle(rawText))
                .summary(extractSummary(rawText))
                .styleFeatures(extractStyleFeatures(rawText))
                .similarityScore(normalizedScore(match.score()))
                .build();
    }

    private static String extractTitle(String text) {
        if (isBlank(text)) {
            return "(无标题)";
        }
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") || trimmed.startsWith("## ")) {
                return truncateByCodePoints(trimmed.replaceFirst("^#{1,2}\\s+", ""), TITLE_MAX_CODEPOINTS);
            }
        }
        return firstNonBlankLine(text, "(无标题)", TITLE_MAX_CODEPOINTS);
    }

    private static String extractSummary(String text) {
        if (isBlank(text)) {
            return "(无摘要)";
        }
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("> 摘要:")) {
                String summary = trimmed.substring("> 摘要:".length()).trim();
                return isBlank(summary) ? "(无摘要)" : truncateByCodePoints(summary, SUMMARY_MAX_CODEPOINTS);
            }
        }
        String normalized = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.matches("^#{1,6}\\s+.+"))
                .filter(line -> !line.startsWith("> 摘要:"))
                .map(line -> line.replaceFirst("^>\\s*", ""))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return isBlank(normalized) ? "(无摘要)" : truncateByCodePoints(normalized, SUMMARY_MAX_CODEPOINTS);
    }

    private static String extractStyleFeatures(String text) {
        if (isBlank(text)) {
            return "(无风格特征)";
        }

        boolean hasHeading = false;
        boolean hasList = false;
        boolean hasQuote = false;
        boolean hasCodeFence = false;
        int paragraphCount = 0;
        int paragraphCodePoints = 0;
        int currentParagraphCodePoints = 0;
        boolean inCodeFence = false;

        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                hasCodeFence = true;
                if (currentParagraphCodePoints > 0) {
                    paragraphCount++;
                    paragraphCodePoints += currentParagraphCodePoints;
                    currentParagraphCodePoints = 0;
                }
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence) {
                continue;
            }
            if (trimmed.isEmpty()) {
                if (currentParagraphCodePoints > 0) {
                    paragraphCount++;
                    paragraphCodePoints += currentParagraphCodePoints;
                    currentParagraphCodePoints = 0;
                }
                continue;
            }
            hasHeading = hasHeading || trimmed.startsWith("#");
            hasList = hasList || LIST_LINE.matcher(trimmed).matches();
            hasQuote = hasQuote || trimmed.startsWith(">");
            if (!trimmed.startsWith("#") && !trimmed.startsWith(">") && !LIST_LINE.matcher(trimmed).matches()) {
                currentParagraphCodePoints += codePointLength(trimmed);
            }
        }
        if (currentParagraphCodePoints > 0) {
            paragraphCount++;
            paragraphCodePoints += currentParagraphCodePoints;
        }

        List<String> features = new ArrayList<>();
        if (hasHeading) {
            features.add("含小标题");
        }
        if (hasList) {
            features.add("有列表");
        }
        if (hasQuote) {
            features.add("有引用");
        }
        if (hasCodeFence) {
            features.add("含代码块");
        }
        if (paragraphCount > 0) {
            int average = paragraphCodePoints / paragraphCount;
            if (average < 40) {
                features.add("段落简短");
            } else if (average <= 120) {
                features.add("段落中等");
            } else {
                features.add("段落较长");
            }
        }
        return features.isEmpty() ? "(无风格特征)" : String.join("; ", features);
    }

    private static String firstNonBlankLine(String text, String fallback, int maxCodePoints) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .map(line -> truncateByCodePoints(line, maxCodePoints))
                .orElse(fallback);
    }

    private static String truncateByCodePoints(String value, int maxCodePoints) {
        return value == null ? null : TextTruncateUtil.truncateByCodePoints(value, maxCodePoints);
    }

    private static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static double normalizedScore(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static void requireNonBlank(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
