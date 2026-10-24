package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单篇文章的结构化归档快照, 供定时发布与人工发布恢复 Article 使用.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivedArticle {

    private String articleId;

    private ArticleStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime scheduledPublishAt;

    private LocalDateTime draftCreatedAt;

    private String wechatDraftMediaId;

    /**
     * Story 10.16 — 对账留存 mediaId: 远端 addDraft 已成功(或疑似成功)但快照尚未推进为
     * {@code DRAFT_CREATED} 时, 在 {@code RECONCILING} 快照中尽力留存已知 mediaId,
     * 供自动对账只补本地写回(绝不重发远端)。对账补全成功后由
     * {@code markDraftCreated}/{@code reconcileDraftCreatedFromReplay} 清除。
     */
    private String reconcileMediaId;

    private String archiveFile;

    private String failureStage;

    private String failureCode;

    private String failureSummary;

    private LocalDateTime failedAt;

    private String failureTaskId;

    private Article article;
}
