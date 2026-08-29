package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.source.twitter.model.Tweet;

/**
 * 原帖复现生成边界.
 *
 * <p>将 {@code PRESERVE_ORIGINAL} 路径从 {@code ContentRewriter} 明确剥离出来, 让 Epic 8
 * 后续故事可以在不污染 AI 改写接口语义的前提下接入确定性渲染/转换逻辑.
 *
 * <p>引用源: Story 8.3 / Epic 8 AD-9.
 */
public interface OriginalPostGenerationGateway {

    /**
     * 生成原帖复现稿.
     *
     * <p>当前 Story 只要求建立正式接线点; 具体渲染细节由 Story 8.5 实现.
     *
     * @param tweet 原始 Tweet
     * @return 供 publisher 使用的文章载体
     */
    Article generate(Tweet tweet);
}
