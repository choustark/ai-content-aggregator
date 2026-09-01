package com.choucj.aiaggregator.publish.wechat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 人工发布入口: 将本地发布池文章推进到微信草稿.
 */
@Slf4j
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
@ConditionalOnBean(ArticlePublicationWorkflow.class)
public class ArticlePublishController {

    private final ArticlePublicationWorkflow publicationWorkflow;

    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publishNow(@PathVariable("id") String id) {
        long start = System.currentTimeMillis();
        String mediaId = publicationWorkflow.publishNow(id);
        long duration = System.currentTimeMillis() - start;
        log.info("人工发布完成: articleId={}, mediaId={}, durationMs={}", id, mediaId, duration);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "articleId", id,
                "status", "DRAFT_CREATED",
                "wechatDraftMediaId", mediaId,
                "durationMs", duration,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
