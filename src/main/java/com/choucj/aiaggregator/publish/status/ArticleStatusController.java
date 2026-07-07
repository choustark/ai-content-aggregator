package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NotFoundException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Story 3.5 — 文章状态查询 REST 端点 (项目首个 @RestController).
 *
 * <p>提供人工审核支持 API: 用户/前端通过 {@code GET /api/articles/{id}/status} 查询文章当前
 * 在自动流水线中的位置 (PENDING / PROCESSING / DRAFT_CREATED), 用于追踪草稿创建进度.
 *
 * <p><b>路径决策 (Story 3.5 §5.1, M1 已人工确认):</b>
 * 选择 {@code /api/articles/{id}/status} 而非 {@code /api/status/{id}} 或扩展 Article 模型,
 * RESTful 子资源语义清晰, 与未来 Article 详情端点 ({@code /api/articles/{id}}) 共存无冲突.
 *
 * <p><b>异常映射 (W1+W2):</b> Controller 不显式 catch Retryable/NonRetryable,
 * 由 {@link com.choucj.aiaggregator.common.exception.GlobalExceptionHandler} 接管:
 * <ul>
 *   <li>Retryable (Redis 连接失败) → 503 + ErrorResponse</li>
 *   <li>NonRetryable (articleId 非法 / Redis 数据非法) → 400 + ErrorResponse</li>
 *   <li>NotFound (状态未找到 / 已过期) → 404 + ErrorResponse</li>
 *   <li>未预期 Exception → 500 + 通用 "系统错误"</li>
 * </ul>
 *
 * <p><b>性能 (NFR2 < 1s):</b> ArticleStatusService.getStatus 走 Redis O(1) GET,
 * 端到端 < 10ms (单实例), 100% 满足 NFR2.
 *
 * <p><b>可观测性 (W11 + AC-10):</b> log.info 含 articleId + status + 耗时(ms),
 * 用于 NFR2 < 1s 验证与运维监控.
 *
 * <p>引用源: Story 3.5 创建.
 */
@Slf4j
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleStatusController {

    private final ArticleStatusService articleStatusService;

    /**
     * Story 3.5 AC-5 / AC-6 / AC-10 — 查询文章状态.
     *
     * @param id 文章 ID (路径变量, 形如 {@code tw-{tweetId}})
     * @return 200 + {@link ArticleStatusResponse} (articleId + status); 不存在则抛 NotFound 由 GlobalExceptionHandler 转 404
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<ArticleStatusResponse> getStatus(@PathVariable("id") String id) {
        long start = System.nanoTime();
        Optional<ArticleStatus> status = articleStatusService.getStatus(id);
        if (status.isEmpty()) {
            // AC-6: 状态未找到 (尚未进入流水线或已过期) → 404 + ErrorResponse
            throw new NotFoundException(ErrorCode.NON_RETRYABLE_ERROR,
                    "Article 状态未找到, articleId=" + id);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // AC-10 / W11: log.info 含 articleId + status + 耗时(ms), 用于 NFR2 < 1s 可观测性
        log.info("状态查询完成: articleId={}, status={}, 耗时={}ms", id, status.get(), elapsedMs);
        return ResponseEntity.ok(new ArticleStatusResponse(id, status.get().name()));
    }
}
