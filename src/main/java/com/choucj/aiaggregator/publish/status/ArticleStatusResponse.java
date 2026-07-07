package com.choucj.aiaggregator.publish.status;

/**
 * Story 3.5 — 文章状态查询响应 DTO.
 *
 * <p>由 {@link ArticleStatusController#getStatus(String)} 返回, JSON 序列化给客户端.
 *
 * <p>字段:
 * <ul>
 *   <li>{@code articleId} — 文章 ID (与请求路径 {@code @PathVariable} 一致)</li>
 *   <li>{@code status} — {@link ArticleStatus#name()} 字符串 (PENDING / PROCESSING / DRAFT_CREATED)</li>
 * </ul>
 *
 * <p>引用源: Story 3.5 创建.
 *
 * @param articleId 文章 ID
 * @param status    状态枚举名 (与 Redis 存储值一致)
 */
public record ArticleStatusResponse(String articleId, String status) {
}
