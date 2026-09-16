package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.observability.CorrelationContext;
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

    /**
     * 立即发布指定文章并建立请求级日志关联上下文，以便从 HTTP 入口检索整条发布链路。
     *
     * @param id 确定性文章标识
     * @return 保持既有字段不变的发布结果
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publishNow(@PathVariable("id") String id) {
        CorrelationContext.begin(null);
        CorrelationContext.putArticleId(id);
        try {
            long start = System.currentTimeMillis();
            try {
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
            } catch (RuntimeException e) {
                log.error("人工发布失败: articleId={}, errorType={}, durationMs={}",
                        id, e.getClass().getSimpleName(), System.currentTimeMillis() - start, e);
                throw e;
            }
        } finally {
            CorrelationContext.end();
        }
    }
}
