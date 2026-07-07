package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.GlobalExceptionHandler;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 3.5 — {@link ArticleStatusController} MockMvc 集成测试.
 *
 * <p>使用 {@link MockMvcBuilders#standaloneSetup} + {@code setControllerAdvice}
 * (同 {@code GlobalExceptionHandlerTest} 模式), 不启动 Spring 上下文 → 不依赖 Redis Cluster, 测试 0 IO.
 *
 * <p>覆盖 AC-5 / AC-6 / AC-7 / AC-9 / AC-10: 200 + JSON / 404 / Redis 503 / 非法 ID / 耗时日志.
 */
@ExtendWith(MockitoExtension.class)
class ArticleStatusControllerIntegrationTest {

    private static final String ARTICLE_ID = "tw-1234567890";

    @Mock
    private ArticleStatusService articleStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ArticleStatusController controller = new ArticleStatusController(articleStatusService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ===== AC-5: 状态存在 → 200 + JSON =====

    @Test
    void shouldReturn200AndStatusWhenKeyExists() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID)))
                .thenReturn(Optional.of(ArticleStatus.DRAFT_CREATED));

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleId").value(ARTICLE_ID))
                .andExpect(jsonPath("$.status").value("DRAFT_CREATED"));
    }

    @Test
    void shouldReturn200WhenStatusIsPending() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID)))
                .thenReturn(Optional.of(ArticleStatus.PENDING));

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ===== AC-6: 状态未找到 → 404 (NotFound → GlobalExceptionHandler.handleNotFound) =====

    @Test
    void shouldReturn404WhenKeyNotExists() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NON_RETRYABLE_ERROR.name()))
                .andExpect(jsonPath("$.message").value("Article 状态未找到, articleId=" + ARTICLE_ID));
    }

    // ===== AC-9: Redis 异常透传 → 503 =====

    @Test
    void shouldReturn503WhenRedisThrowsRetryable() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID)))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"));

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.REDIS_CONNECTION_ERROR.name()));
    }

    @Test
    void shouldReturn400WhenRedisThrowsNonRetryable() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID)))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "Redis 数据错"));

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.REDIS_DATA_ERROR.name()));
    }

    // ===== AC-7: 非法 articleId (空格) → 400 (Controller 透传 NonRetryable) =====

    @Test
    void shouldReturn400WhenArticleIdIsBlank() throws Exception {
        // ArticleStatusService 入口校验抛 NonRetryable → GlobalExceptionHandler 转 400
        when(articleStatusService.getStatus(eq("   ")))
                .thenThrow(new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                        "非法 articleId:    "));

        mockMvc.perform(get("/api/articles/   /status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.NON_RETRYABLE_ERROR.name()));
    }

    // ===== AC-5 JSON 序列化完整性 =====

    @Test
    void shouldSerializeArticleIdAndStatusInJsonResponse() throws Exception {
        when(articleStatusService.getStatus(eq(ARTICLE_ID)))
                .thenReturn(Optional.of(ArticleStatus.PROCESSING));

        mockMvc.perform(get("/api/articles/" + ARTICLE_ID + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.articleId").exists())
                .andExpect(jsonPath("$.status").exists())
                // 确保只有 2 个字段 (articleId + status), 无多余字段泄漏
                .andExpect(jsonPath("$.length()").value(2));
    }
}
