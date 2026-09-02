package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.3a {@link LangChain4jLlmClient} 单测.
 *
 * <p>覆盖:
 * <ul>
 *   <li>happy 路径(DeepSeek 主)</li>
 *   <li>DeepSeek 失败降级 GLM</li>
 *   <li>双失败抛 RetryableException(EXTERNAL_API_ERROR)</li>
 *   <li>chatWithModel 显式切换 (含不支持的 model)</li>
 *   <li>空 prompt / systemPrompt 校验</li>
 *   <li>chat(system, user) 走 ChatResponse.aiMessage().text()</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LangChain4jLlmClientTest {

    @Mock
    private ChatModel deepSeekChatModel;

    @Mock
    private ChatModel glmChatModel;

    private LangChain4jLlmClient client;

    @BeforeEach
    void setUp() {
        client = new LangChain4jLlmClient(deepSeekChatModel, glmChatModel);
    }

    @Test
    void shouldReturnDeepSeekResponseOnHappyPath() {
        when(deepSeekChatModel.chat("hello")).thenReturn("hi from deepseek");

        String result = client.chat("hello");

        assertThat(result).isEqualTo("hi from deepseek");
        verify(glmChatModel, never()).chat(anyString());
    }

    @Test
    void shouldFallbackToGlmWhenDeepSeekFails() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("deepseek timeout"));
        when(glmChatModel.chat("hello")).thenReturn("hi from glm");

        String result = client.chat("hello");

        assertThat(result).isEqualTo("hi from glm");
        verify(deepSeekChatModel, times(1)).chat("hello");
        verify(glmChatModel, times(1)).chat("hello");
    }

    @Test
    void shouldReturnModelNameWhenDefaultChatUsesDeepSeek() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("deepseek rewrite"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        LlmClient.ChatResult result = client.chatWithResult("system", "user");

        assertThat(result.model()).isEqualTo("deepseek");
        assertThat(result.text()).isEqualTo("deepseek rewrite");
    }

    @Test
    void shouldReturnModelNameWhenDefaultChatFallsBackToGlm() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("glm rewrite"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("deepseek timeout"));
        when(glmChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        LlmClient.ChatResult result = client.chatWithResult("system", "user");

        assertThat(result.model()).isEqualTo("glm");
        assertThat(result.text()).isEqualTo("glm rewrite");
    }

    @Test
    void shouldThrowRetryableWhenBothModelsFail() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat("hello")).thenThrow(new RuntimeException("glm down"));

        assertThatThrownBy(() -> client.chat("hello"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldUseExplicitModelWithoutFallback() {
        when(glmChatModel.chat("hello")).thenReturn("glm only");

        String result = client.chatWithModel("glm", "hello");

        assertThat(result).isEqualTo("glm only");
        verify(deepSeekChatModel, never()).chat(anyString());
    }

    @Test
    void shouldUseExplicitModelWithSystemUserPromptWithoutFallback() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("glm rewrite"))
                .build();
        when(glmChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        String result = client.chatWithModel("glm", "system", "user");

        assertThat(result).isEqualTo("glm rewrite");
        verify(deepSeekChatModel, never()).chat(any(ChatMessage[].class));
    }

    @Test
    void shouldReturnExplicitModelNameWhenChatWithModelResult() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("glm rewrite"))
                .build();
        when(glmChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        LlmClient.ChatResult result = client.chatWithModelResult("glm", "system", "user");

        assertThat(result.model()).isEqualTo("glm");
        assertThat(result.text()).isEqualTo("glm rewrite");
    }

    @Test
    void shouldThrowRetryableWhenExplicitModelFailsWithoutFallback() {
        when(deepSeekChatModel.chat("hello")).thenThrow(new RuntimeException("ds down"));

        assertThatThrownBy(() -> client.chatWithModel("deepseek", "hello"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        verify(glmChatModel, never()).chat(anyString());
    }

    @Test
    void shouldSanitizeExplicitModelFailureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(LangChain4jLlmClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(deepSeekChatModel.chat("hello"))
                    .thenThrow(new RuntimeException("SECRET_UPSTREAM_BODY prompt=response"));

            Throwable thrown = catchThrowable(() -> client.chatWithModel("deepseek", "hello"));

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(thrown)
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("RuntimeException")
                    .hasMessageNotContaining("SECRET_UPSTREAM_BODY")
                    .hasNoCause();
            assertThat(logs)
                    .contains("model=deepseek", "RuntimeException")
                    .doesNotContain("SECRET_UPSTREAM_BODY", "prompt=response");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotLogPromptOrExceptionMessagesOnDefaultFallbackPath() {
        Logger logger = (Logger) LoggerFactory.getLogger(LangChain4jLlmClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(deepSeekChatModel.chat("SECRET_PROMPT_BODY"))
                    .thenThrow(new RuntimeException("SECRET_DEEPSEEK_ERROR"));
            when(glmChatModel.chat("SECRET_PROMPT_BODY"))
                    .thenThrow(new RuntimeException("SECRET_GLM_ERROR"));

            Throwable thrown = catchThrowable(() -> client.chat("SECRET_PROMPT_BODY"));

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(thrown)
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("primaryType=RuntimeException")
                    .hasMessageContaining("fallbackType=RuntimeException")
                    .hasMessageNotContaining("SECRET_DEEPSEEK_ERROR")
                    .hasMessageNotContaining("SECRET_GLM_ERROR")
                    .hasNoCause();
            assertThat(logs)
                    .contains("primaryType=RuntimeException", "fallbackType=RuntimeException")
                    .doesNotContain("SECRET_PROMPT_BODY", "SECRET_DEEPSEEK_ERROR", "SECRET_GLM_ERROR");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldRejectUnknownModel() {
        assertThatThrownBy(() -> client.chatWithModel("unknown-model", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的模型");
    }

    @Test
    void shouldRejectBlankPrompt() {
        assertThatThrownBy(() -> client.chat(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankSystemOrUserPrompt() {
        assertThatThrownBy(() -> client.chat("", "user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("system", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleSystemUserChatWithChatResponse() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("score: 8"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class))).thenReturn(response);

        String result = client.chat("you are scorer", "score this");

        assertThat(result).isEqualTo("score: 8");
    }

    @Test
    void shouldFallbackToGlmOnSystemUserChatWhenDeepSeekFails() {
        ChatResponse dsResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("glm score"))
                .build();
        when(deepSeekChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat(any(ChatMessage[].class))).thenReturn(dsResponse);

        String result = client.chat("system", "user");

        assertThat(result).isEqualTo("glm score");
    }

    @Test
    void shouldThrowRetryableWhenBothFailOnSystemUserChat() {
        when(deepSeekChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("ds down"));
        when(glmChatModel.chat(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("glm down"));

        assertThatThrownBy(() -> client.chat("system", "user"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldDescribeUpstreamHttpErrorFromCauseChain() {
        // 模拟 langchain4j 1.16 异常链: HttpException(400, body) 为 root cause,
        // 被 ExceptionMapper 包装为 InvalidRequestException (今晨 glm 400 的场景).
        dev.langchain4j.exception.HttpException httpException =
                new dev.langchain4j.exception.HttpException(400, "{\"error\":{\"code\":\"1301\"}}");
        InvalidRequestException wrapped = new InvalidRequestException(httpException);

        assertThat(LangChain4jLlmClient.describe(wrapped))
                .isEqualTo("HTTP 400, body={\"error\":{\"code\":\"1301\"}}");
    }

    @Test
    void shouldReturnEmptyDescribeForNonHttpException() {
        assertThat(LangChain4jLlmClient.describe(new RuntimeException("timeout")))
                .isEmpty();
    }

    @Test
    void shouldSanitizeApiKeyPatternsAndTruncateLongBody() {
        assertThat(LangChain4jLlmClient.sanitizeBody("Bearer abc123def456ghi789 done"))
                .isEqualTo("Bearer *** done");
        assertThat(LangChain4jLlmClient.sanitizeBody("{\"api_key\":\"sk-abcdefgh1234567890\"}"))
                .doesNotContain("sk-abcdefgh1234567890");
        String longBody = "x".repeat(500);
        assertThat(LangChain4jLlmClient.sanitizeBody(longBody))
                .hasSize(300 + "...(截断)".length())
                .endsWith("...(截断)");
        assertThat(LangChain4jLlmClient.sanitizeBody(null)).isEqualTo("(空)");
        assertThat(LangChain4jLlmClient.sanitizeBody("  ")).isEqualTo("(空)");
    }
}
