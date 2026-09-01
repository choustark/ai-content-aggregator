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

    private String archiveFile;

    private Article article;
}
