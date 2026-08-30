package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.source.twitter.model.Tweet;

/**
 * REWRITE_WITH_MEDIA 生成边界 (Story 9.1 Task 2, AD-1).
 *
 * <p>与 {@link OriginalPostGenerationGateway} (PRESERVE 专用) 平行的独立接口 —
 * 不得复用 PRESERVE 接口表示 REWRITE_WITH_MEDIA (Story 9.1 guardrail)。
 * 经 {@code Optional<T>} 注入 {@code TwitterProcessor} (真实现挂
 * {@code wechat.mp.enabled=true} 条件注册, D6 规则), 命中但缺失时由 processor
 * 显式 fail-fast (AC 9)。
 *
 * <p>引用源: Story 9.1 / architecture-rewrite-with-media AD-1 + AD-2.
 */
public interface MediaAwareRewriteGenerationGateway {

    /**
     * 生成 AI 改写 + 原帖媒体草稿 (编排顺序固定 AD-2).
     *
     * @param tweet 原始 Tweet
     * @return 生成结果: generationMode=REWRITE_WITH_MEDIA 的 Article + 嵌入/降级媒体计数
     *         (processor summary 观测用, AC 10)
     */
    MediaAwareRewriteGeneration generate(Tweet tweet);

    /**
     * 生成结果载体.
     *
     * @param article            generationMode=REWRITE_WITH_MEDIA 的草稿
     * @param embeddedMediaCount 嵌入正文的微信图片数 (去重后, 来自 MarkdownMediaInserter)
     * @param degradedMediaCount 未嵌入的 sidecar 条目数 (降级审计, AC 8)
     */
    record MediaAwareRewriteGeneration(Article article, int embeddedMediaCount, int degradedMediaCount) {
    }
}
