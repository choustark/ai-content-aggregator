package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.common.observability.LogEventCapture;
import com.choucj.aiaggregator.common.exception.GlobalExceptionHandler;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证人工发布入口建立并清理文章关联上下文，以免 HTTP 工作线程复用时串号。
 *
 * <p>引用源：Story 10.2（创建）。
 */
class ArticlePublishControllerTest {

    @Test
    void should_log_correlated_failure_and_preserve_response_when_workflow_throws() throws Exception {
        ArticlePublicationWorkflow workflow = mock(ArticlePublicationWorkflow.class);
        AtomicReference<String> correlation = new AtomicReference<>();
        when(workflow.publishNow("tw-42")).thenAnswer(invocation -> {
            correlation.set(CorrelationContext.require());
            throw new IllegalStateException("failed");
        });
        var mvc = MockMvcBuilders.standaloneSetup(new ArticlePublishController(workflow))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var logs = new LogEventCapture(ArticlePublishController.class)) {
            mvc.perform(post("/api/articles/tw-42/publish"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("系统错误"));

            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("人工发布失败", "IllegalStateException", "durationMs=");
                assertThat(event.getMDCPropertyMap())
                        .containsEntry("correlationId", correlation.get())
                        .containsEntry("articleId", "tw-42");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            });
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void should_expose_article_context_when_publish_succeeds() {
        ArticlePublicationWorkflow workflow = mock(ArticlePublicationWorkflow.class);
        when(workflow.publishNow("tw-42")).thenAnswer(invocation -> {
            assertThat(CorrelationContext.require()).isNotBlank();
            assertThat(MDC.get(CorrelationContext.ARTICLE_ID_KEY)).isEqualTo("tw-42");
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isNull();
            return "media-42";
        });
        ArticlePublishController controller = new ArticlePublishController(workflow);

        var response = controller.publishNow("tw-42");

        assertThat(response.getBody()).containsEntry("wechatDraftMediaId", "media-42");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_clear_context_when_publish_fails() {
        ArticlePublicationWorkflow workflow = mock(ArticlePublicationWorkflow.class);
        when(workflow.publishNow("tw-42")).thenThrow(new IllegalStateException("failed"));
        ArticlePublishController controller = new ArticlePublishController(workflow);

        assertThatThrownBy(() -> controller.publishNow("tw-42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
