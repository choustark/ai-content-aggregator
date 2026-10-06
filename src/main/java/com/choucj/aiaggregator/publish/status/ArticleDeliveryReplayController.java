package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.task.queue.TaskQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** 提供与任务补跑共用开关和令牌保护的文章交付补跑入口。 */
@RestController
@RequestMapping("/api/articles")
@ConditionalOnProperty(prefix = "task.replay", name = "enabled", havingValue = "true")
public class ArticleDeliveryReplayController {
    private final ArticleDeliveryReplayService replayService;
    private final String configuredToken;

    public ArticleDeliveryReplayController(ArticleDeliveryReplayService replayService,
                                           @Value("${task.replay.token:}") String configuredToken) {
        this.replayService = replayService;
        this.configuredToken = configuredToken;
    }

    /** 创建可关联的新任务；默认复用快照 Article，不触发内容生成。 */
    @PostMapping("/{articleId}/delivery/replay")
    public ResponseEntity<Map<String, Object>> replay(
            @PathVariable String articleId,
            @RequestHeader(value = "X-Replay-Token", required = false) String token,
            @RequestHeader(value = "X-Replay-Request-Id", required = false) String requestId) {
        if (configuredToken == null || configuredToken.isBlank() || token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                configuredToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "补跑访问令牌无效"));
        }
        TaskQueue.ReplayResult result = replayService.replay(articleId, requestId);
        if (result.outcome() == TaskQueue.ReplayOutcome.REPLAYED) {
            return ResponseEntity.ok(Map.of("success", true, "articleId", articleId,
                    "newTaskId", result.newTaskId()));
        }
        int status = result.outcome() == TaskQueue.ReplayOutcome.NOT_DEAD_LETTER ? 404 : 409;
        return ResponseEntity.status(status).body(Map.of("success", false,
                "message", "补跑被拒绝: " + result.outcome()));
    }
}
