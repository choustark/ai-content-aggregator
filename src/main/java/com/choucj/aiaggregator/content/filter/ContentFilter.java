package com.choucj.aiaggregator.content.filter;

import java.util.List;

/**
 * 内容筛选器抽象接口 — 智能筛选扩展点(PRD FR3).
 *
 * <p>泛型化设计:{@code T} 为待筛选的领域模型类型. 当前已知实现:
 * <ul>
 *   <li>{@code ContentFilter<Tweet>} — 由 {@code CommentFilter} 实现(Story 2.3, 评论数筛选)</li>
 *   <li>{@code ContentFilter<Tweet>} — 由 {@code InnovationFilter} 实现(Story 2.3 &amp; 4.3, AI 创新评分)</li>
 * </ul>
 *
 * <p>责任链模式:多个 {@code ContentFilter} 可串联(如先按评论数筛 → 再按 AI 创新分筛),
 * 由 Pipeline(Story 2.6 / 4.4)编排.
 *
 * @param <T> 待筛选的领域模型类型(Tweet / GitHubRepo / 其他)
 */
public interface ContentFilter<T> {

    /**
     * 对输入列表执行筛选, 返回通过筛选的子集.
     *
     * <p>实现要求:
     * <ul>
     *   <li>不修改输入列表(无副作用)</li>
     *   <li>返回新列表(可为空, 但非 {@code null})</li>
     *   <li>保持原始顺序(除非显式重排, 如按评分降序)</li>
     * </ul>
     *
     * @param items 待筛选列表; 若为空直接返回空列表
     * @return 通过筛选的子集
     */
    List<T> filter(List<T> items);
}
